/**
 * 인증과 사용량 제한.
 *
 * 분석과 사진 인식 모두 Gemini 호출 비용이 걸려 있어, 누가 얼마나 썼는지는
 * 앱이 아니라 서버가 판단해야 한다. 그 판단 로직을 여기 모아둔다.
 */
const admin = require("firebase-admin");

if (admin.apps.length === 0) {
    admin.initializeApp();
}

/** 무료 사용자의 하루 분석 횟수 */
const DAILY_ANALYSIS_LIMIT = 3;

/**
 * 하루 사진 인식 횟수.
 *
 * 무료 사용자도 쓸 수 있게 열어둔 이유는, 사진 인식이 곧 "입력 부담을 없애는" 기능이라서다.
 * 아예 막으면 무료 사용자는 계속 손으로 타이핑하다 지쳐 나가고, 그러면 구독할 사람도 안 생긴다.
 * 하루 3회면 한두 끼는 사진으로 처리하면서 차이를 몸으로 느끼게 된다.
 *
 * 구독자에게도 상한을 두는 건 무제한 개방이 아니라 비용 사고를 막기 위해서다.
 * 두 숫자 모두 서버 상수라 앱 배포 없이 조정할 수 있다.
 */
const FREE_PHOTO_LIMIT = 3;
const SUBSCRIBER_PHOTO_LIMIT = 30;

/**
 * 하루 사진 인식 "시도" 상한. 성공 여부와 무관하게 세며 환불하지 않는다.
 *
 * 결과를 못 낸 요청은 횟수를 돌려주는데(refundPhoto), 그것만 있으면
 * 음식이 아닌 사진을 계속 보내는 식으로 Gemini 호출을 무한정 끌어낼 수 있다.
 * 정상 사용자는 닿을 일이 없는 높이로 두고 비용 사고만 막는다.
 */
const DAILY_PHOTO_ATTEMPT_LIMIT = 30;

/**
 * 인증 토큰이 없는 요청을 막을지 여부.
 *
 * b24 미만 앱은 토큰을 보내지 않으므로 지금은 false로 둔다. (막으면 구버전이 전부 실패한다)
 * Remote Config의 min_version_code를 24로 올려 구버전을 정리한 뒤 true로 바꾸면,
 * 그때부터 횟수 우회가 완전히 차단된다.
 */
const REQUIRE_AUTH = false;

/**
 * 서울 기준 오늘 날짜(yyyy-MM-dd).
 *
 * 함수 런타임은 UTC라 그냥 new Date()를 쓰면 한국 시간으로 자정을 넘긴 뒤에도
 * 9시간 동안 어제로 계산된다. 그러면 하루 횟수가 엉뚱한 시점에 초기화된다.
 */
function seoulToday() {
    return new Intl.DateTimeFormat("en-CA", {
        timeZone: "Asia/Seoul",
        year: "numeric",
        month: "2-digit",
        day: "2-digit"
    }).format(new Date());
}

/** 횟수 확인이 이 시간을 넘기면 포기하고 분석을 진행한다. */
const QUOTA_TIMEOUT_MS = 3000;

/** 약속이 제한 시간을 넘기면 거부한다. 부가 기능이 본 작업을 붙잡지 못하게 하는 용도. */
function withTimeout(promise, ms) {
    return Promise.race([
        promise,
        new Promise((_, reject) => setTimeout(() => reject(new Error("timeout")), ms))
    ]);
}

/** Authorization 헤더의 Firebase ID 토큰을 검증한다. 없거나 잘못됐으면 null. */
async function verifyUser(req) {
    const header = req.get("Authorization") || "";
    const match = header.match(/^Bearer\s+(.+)$/i);
    if (!match) return null;

    try {
        const decoded = await admin.auth().verifyIdToken(match[1]);
        return { uid: decoded.uid, email: (decoded.email || "").toLowerCase() };
    } catch (error) {
        console.warn("ID 토큰 검증 실패:", error.message);
        return null;
    }
}

/**
 * 무제한 계정 문서를 트랜잭션 안에서 읽는다. 이메일이 없으면 읽지 않는다.
 * 조회가 실패해도 기능을 막지 않도록 null을 돌려준다 (= 무제한 아님으로 취급).
 */
async function readUnlimited(tx, db, email) {
    if (!email) return null;
    try {
        return await tx.get(db.collection("unlimited_users").doc(email));
    } catch (error) {
        console.warn("무제한 계정 조회 실패:", error.message);
        return null;
    }
}

/**
 * 분석 횟수를 먼저 차감(예약)한다.
 *
 * 클라이언트가 세던 것을 서버로 옮긴 이유는, 앱 데이터를 지우거나 문서를 손대면
 * 무제한으로 쓸 수 있었기 때문이다. Gemini 호출 비용이 걸린 문제라 서버가 판단해야 한다.
 *
 * 트랜잭션으로 읽고 쓰므로 동시에 여러 번 눌러도 정확히 세어진다.
 */
async function reserveAnalysis(user) {
    const db = admin.firestore();
    const userRef = db.collection("users").doc(user.uid);
    const today = seoulToday();

    return db.runTransaction(async (tx) => {
        // 사용자 문서와 무제한 계정 여부를 한 트랜잭션 안에서 함께 읽는다.
        // 따로 조회하면 Firestore를 두 번 왕복해 분석 한 번에 1초 가까이 더 걸렸다.
        const [snap, unlimitedSnap] = await Promise.all([
            tx.get(userRef),
            readUnlimited(tx, db, user.email)
        ]);

        const data = snap.exists ? snap.data() : {};
        const subscribed = data.isSubscribed === true;
        const exempt = (unlimitedSnap && unlimitedSnap.exists) || subscribed;

        // 날짜가 바뀌었으면 0부터 다시 센다
        const used = data.lastAnalyzedDate === today ? Number(data.todayAnalysisCount) || 0 : 0;

        if (!exempt && used >= DAILY_ANALYSIS_LIMIT) {
            return { allowed: false, count: used, limit: DAILY_ANALYSIS_LIMIT, unlimited: false };
        }

        const next = used + 1;
        tx.set(userRef, { todayAnalysisCount: next, lastAnalyzedDate: today }, { merge: true });
        return { allowed: true, count: next, limit: DAILY_ANALYSIS_LIMIT, unlimited: exempt };
    });
}

/**
 * 사진 인식 횟수를 세고 상한을 넘었는지 알려준다. 넘지 않았으면 1 올리고 true.
 *
 * 분석(하루 3회)과 따로 세는 이유는 성격이 다르기 때문이다. 사진은 입력을 돕는 단계라
 * 여러 번 다시 찍을 수 있어야 하고, 응답도 짧아 분석보다 훨씬 싸다.
 * 그래서 한도는 넉넉하게 두되, 무제한으로 열어두지는 않는다.
 */
async function reservePhoto(user) {
    const db = admin.firestore();
    const userRef = db.collection("users").doc(user.uid);
    const today = seoulToday();

    try {
        return await db.runTransaction(async (tx) => {
            // 분석과 마찬가지로 왕복을 한 번으로 줄인다
            const [snap, unlimitedSnap] = await Promise.all([
                tx.get(userRef),
                readUnlimited(tx, db, user.email)
            ]);

            const data = snap.exists ? snap.data() : {};
            const unlimited = !!(unlimitedSnap && unlimitedSnap.exists);
            const subscribed = data.isSubscribed === true;
            const sameDay = data.lastPhotoDate === today;
            const used = sameDay ? Number(data.todayPhotoCount) || 0 : 0;
            const attempts = sameDay ? Number(data.todayPhotoAttempts) || 0 : 0;

            // 무제한 계정(운영자 등)은 분석과 마찬가지로 상한을 적용하지 않는다.
            // 횟수 자체는 계속 세어 콘솔에서 사용량을 볼 수 있게 둔다.
            if (!unlimited) {
                if (attempts >= DAILY_PHOTO_ATTEMPT_LIMIT) {
                    return { allowed: false, paid: subscribed };
                }
                const limit = subscribed ? SUBSCRIBER_PHOTO_LIMIT : FREE_PHOTO_LIMIT;
                if (used >= limit) return { allowed: false, paid: subscribed };
            }

            tx.set(userRef, {
                todayPhotoCount: used + 1,
                todayPhotoAttempts: attempts + 1, // 환불 대상이 아님
                lastPhotoDate: today
            }, { merge: true });
            return { allowed: true, paid: unlimited || subscribed };
        });
    } catch (error) {
        // 횟수를 못 세는 상황 때문에 기능 자체를 막지는 않는다
        console.warn("사진 횟수 확인 실패:", error.message);
        return { allowed: true, paid: false };
    }
}

/**
 * 사진 인식이 쓸 만한 결과를 못 냈을 때 차감분을 되돌린다.
 *
 * 서버 오류는 물론이고 "음식을 못 알아봤다"도 환불 대상이다. 사용자가 얻은 게 없는데
 * 무료 3회 중 하나가 날아가면, 사진 몇 장 잘못 찍는 것만으로 그날 기능을 못 쓰게 된다.
 *
 * 시도 횟수(todayPhotoAttempts)는 그대로 두어 악용은 계속 막는다.
 */
async function refundPhoto(uid) {
    try {
        const db = admin.firestore();
        const userRef = db.collection("users").doc(uid);
        await db.runTransaction(async (tx) => {
            const snap = await tx.get(userRef);
            const used = Number(snap.data() && snap.data().todayPhotoCount) || 0;
            if (used > 0) {
                tx.set(userRef, { todayPhotoCount: used - 1 }, { merge: true });
            }
        });
    } catch (error) {
        console.warn("사진 횟수 환불 실패:", error.message);
    }
}

/** 분석이 실패하면 차감했던 횟수를 되돌린다. 실패한 분석까지 세면 사용자만 손해다. */
async function refundAnalysis(uid) {
    try {
        const db = admin.firestore();
        const userRef = db.collection("users").doc(uid);
        await db.runTransaction(async (tx) => {
            const snap = await tx.get(userRef);
            const used = Number(snap.data() && snap.data().todayAnalysisCount) || 0;
            if (used > 0) {
                tx.set(userRef, { todayAnalysisCount: used - 1 }, { merge: true });
            }
        });
    } catch (error) {
        console.warn("횟수 환불 실패:", error.message);
    }
}

module.exports = {
    REQUIRE_AUTH,
    QUOTA_TIMEOUT_MS,
    withTimeout,
    seoulToday,
    verifyUser,
    reserveAnalysis,
    reservePhoto,
    refundAnalysis,
    refundPhoto
};
