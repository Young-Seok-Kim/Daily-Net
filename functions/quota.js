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
 * 하루 사진 인식 "시도" 상한. 사진 장수로 세며, 성공 여부와 무관하고 환불하지 않는다.
 *
 * 사진 인식은 **횟수 제한 없이** 쓰게 둔다. 사진은 입력을 돕는 단계라 몇 번이고 다시 찍을 수 있어야
 * 하고, 응답이 짧아 분석보다 훨씬 싸다. 예전의 무료 3회 / 구독 30회 상한은 유료 관문이었는데
 * 전면 무료 개방 뒤로는 의미가 없어 없앴다.
 *
 * 그래도 상한을 아예 없애지는 않는다. Gemini 호출은 건당 비용이고, 계정 하나가 탈취되거나
 * 스크립트로 하루 수천 장을 보내면 요금이 그대로 청구된다. 여기 숫자는 사람이 하루에 닿을 일이
 * 없는 높이라 "무제한"과 체감이 같고, 비용 사고만 막는다.
 *
 * 한 요청에 여러 장을 보내면 장수만큼 센다. 토큰 비용이 장수에 비례하기 때문이다.
 * (5장 × 60요청 = 300장이면 하루 300장에 닿는다. 그래도 사람이 닿기엔 멀다)
 */
const DAILY_PHOTO_ATTEMPT_LIMIT = 300;

/**
 * 전면 무료 개방 스위치.
 *
 * true면 구독 여부를 따지지 않고 모두에게 구독자와 같은 한도를 준다.
 * 되돌리려면 이 값을 false로 바꾸고 firebase deploy --only functions 만 하면 된다.
 * 앱은 손대지 않으므로 이미 설치된 버전까지 한 번에 되돌아간다.
 *
 * 무료로 열어도 상한을 아예 없애지는 않는다. Gemini 호출은 건당 비용이고,
 * 계정 하나가 스크립트로 하루 수천 번을 부르면 요금이 그대로 청구된다.
 * 여기 숫자는 사람이 하루에 닿을 일이 없는 높이라 "무제한"과 체감이 같다.
 */
const FREE_FOR_ALL = true;

/** 무료 개방 기간의 하루 분석 상한. 유료 관문이 아니라 비용 사고 방지선이다. */
const FREE_MODE_ANALYSIS_LIMIT = 30;

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
 * 지금 구독 중인지 판단한다. isSubscribed 플래그와 만료 시각을 함께 본다.
 *
 * 플래그만 믿으면 RTDN 알림이 유실됐을 때 만료가 영영 반영되지 않는다.
 * (Pub/Sub 처리 중 오류가 나면 그 알림은 사라진다. 앱을 켜지 않는 사용자는
 *  verifySubscription도 호출되지 않아 계속 구독자로 남는다.)
 * 만료 시각을 함께 보면 알림 전달에 의존하지 않고도 스스로 끊어진다.
 *
 * 만료 정보가 없는 문서는 예전처럼 플래그만 믿는다.
 * b24까지 앱이 직접 쓴 기록에는 subscriptionExpiry가 없어서, 여기서 막으면
 * 기존 구독자가 한꺼번에 구독을 잃는다.
 */
function isSubscribedNow(data) {
    if (data.isSubscribed !== true) return false;

    const expiry = data.subscriptionExpiry;
    if (!expiry) return true;

    const expiresAt = new Date(expiry).getTime();
    if (Number.isNaN(expiresAt)) return true; // 값이 깨졌으면 막지 않는다

    return expiresAt > Date.now();
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
            // 무료 개방 중에는 무제한 계정인지 볼 필요가 없다. 어차피 모두 통과한다.
            FREE_FOR_ALL ? null : readUnlimited(tx, db, user.email)
        ]);

        const data = snap.exists ? snap.data() : {};

        /*
         * 무료 개방 기간.
         *
         * 사용량은 todayAnalysisCount가 아니라 freeMode* 필드에 따로 적고,
         * 원래 필드는 0으로 눌러 둔다. 이미 설치된 앱은 서버 응답과 무관하게
         * 이 필드가 3에 닿으면 스스로 결제창을 띄우기 때문이다.
         * (MainViewModel.checkAndAnalyze의 사전 확인 → isOverDailyLimitOnServer)
         *
         * 덕분에 앱을 새로 내지 않아도 결제창이 사라지고,
         * 스위치를 false로 되돌리면 그날부터 다시 하루 3회로 돌아간다.
         */
        if (FREE_FOR_ALL) {
            const usedFree = data.freeModeAnalysisDate === today
                ? Number(data.freeModeAnalysisCount) || 0
                : 0;

            if (usedFree >= FREE_MODE_ANALYSIS_LIMIT) {
                return { allowed: false, count: usedFree, limit: FREE_MODE_ANALYSIS_LIMIT, unlimited: false };
            }

            tx.set(userRef, {
                freeModeAnalysisCount: usedFree + 1,
                freeModeAnalysisDate: today,
                todayAnalysisCount: 0,
                lastAnalyzedDate: today
            }, { merge: true });

            return { allowed: true, count: usedFree + 1, limit: FREE_MODE_ANALYSIS_LIMIT, unlimited: true };
        }

        const subscribed = isSubscribedNow(data);
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
 * 사진 인식 시도를 세고 비용 사고 방지선(DAILY_PHOTO_ATTEMPT_LIMIT)을 넘었는지 알려준다.
 * 넘지 않았으면 장수만큼 올리고 allowed: true.
 *
 * 성공 횟수(todayPhotoCount)는 상한에 쓰지 않고 콘솔에서 사용량을 보기 위해서만 센다.
 * 분석(reserveAnalysis)과 따로 세는 이유는 성격이 다르기 때문이다. 사진은 입력을 돕는 단계라
 * 여러 번 다시 찍을 수 있어야 하고, 응답도 짧아 분석보다 훨씬 싸다.
 *
 * @param {{uid: string, email: string}} user
 * @param {number} count 이번 요청의 사진 장수
 */
async function reservePhoto(user, count = 1) {
    const db = admin.firestore();
    const userRef = db.collection("users").doc(user.uid);
    const today = seoulToday();
    const n = Math.max(1, Number(count) || 1);

    try {
        return await db.runTransaction(async (tx) => {
            // 분석과 마찬가지로 왕복을 한 번으로 줄인다
            const [snap, unlimitedSnap] = await Promise.all([
                tx.get(userRef),
                readUnlimited(tx, db, user.email)
            ]);

            const data = snap.exists ? snap.data() : {};
            const unlimited = !!(unlimitedSnap && unlimitedSnap.exists);
            const sameDay = data.lastPhotoDate === today;
            const used = sameDay ? Number(data.todayPhotoCount) || 0 : 0;
            const attempts = sameDay ? Number(data.todayPhotoAttempts) || 0 : 0;

            // 무제한 계정(운영자 등)은 분석과 마찬가지로 상한을 적용하지 않는다.
            // 횟수 자체는 계속 세어 콘솔에서 사용량을 볼 수 있게 둔다.
            if (!unlimited && attempts + n > DAILY_PHOTO_ATTEMPT_LIMIT) {
                return { allowed: false, attempts, limit: DAILY_PHOTO_ATTEMPT_LIMIT };
            }

            tx.set(userRef, {
                todayPhotoCount: used + 1,
                todayPhotoAttempts: attempts + n, // 환불 대상이 아님
                lastPhotoDate: today
            }, { merge: true });
            return { allowed: true, attempts: attempts + n, limit: DAILY_PHOTO_ATTEMPT_LIMIT };
        });
    } catch (error) {
        // 횟수를 못 세는 상황 때문에 기능 자체를 막지는 않는다
        console.warn("사진 횟수 확인 실패:", error.message);
        return { allowed: true, attempts: 0, limit: DAILY_PHOTO_ATTEMPT_LIMIT };
    }
}

/**
 * 사진 인식이 쓸 만한 결과를 못 냈을 때 성공 횟수(todayPhotoCount)를 되돌린다.
 *
 * 성공 횟수는 이제 상한에 쓰이지 않지만, 콘솔에서 "쓸 만한 결과를 낸 횟수"로 읽히려면
 * 서버 오류나 "음식을 못 알아봤다"는 빼야 맞다.
 *
 * 시도 횟수(todayPhotoAttempts)는 그대로 두어 비용 사고 방지선은 계속 작동한다.
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

/**
 * 분석이 실패하면 차감했던 횟수를 되돌린다. 실패한 분석까지 세면 사용자만 손해다.
 *
 * 무료 개방 중에는 차감한 쪽이 freeModeAnalysisCount이므로 그쪽을 되돌린다.
 * 원래 필드를 건드리면 0으로 눌러둔 값이 흐트러져 구버전 앱에 결제창이 뜬다.
 */
async function refundAnalysis(uid) {
    const field = FREE_FOR_ALL ? "freeModeAnalysisCount" : "todayAnalysisCount";
    try {
        const db = admin.firestore();
        const userRef = db.collection("users").doc(uid);
        await db.runTransaction(async (tx) => {
            const snap = await tx.get(userRef);
            const used = Number(snap.data() && snap.data()[field]) || 0;
            if (used > 0) {
                tx.set(userRef, { [field]: used - 1 }, { merge: true });
            }
        });
    } catch (error) {
        console.warn("횟수 환불 실패:", error.message);
    }
}

module.exports = {
    REQUIRE_AUTH,
    // 무료 개방 스위치와 그 상한. 테스트가 값을 고정해 두려고 함께 내보낸다.
    FREE_FOR_ALL,
    FREE_MODE_ANALYSIS_LIMIT,
    DAILY_PHOTO_ATTEMPT_LIMIT,
    QUOTA_TIMEOUT_MS,
    withTimeout,
    seoulToday,
    // 테스트에서 검증하려고 내보낸다. 이 함수는 서버 안에서만 쓰이며 동작은 그대로다.
    isSubscribedNow,
    verifyUser,
    reserveAnalysis,
    reservePhoto,
    refundAnalysis,
    refundPhoto
};
