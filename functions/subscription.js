/**
 * 구독 상태를 서버가 판단하는 함수들.
 *
 * 지금까지는 앱이 Play Billing을 확인한 뒤 Firestore의 isSubscribed를 직접 썼다.
 * 그런데 그 문서는 규칙상 본인이 쓸 수 있어서, 결제하지 않고 필드만 true로 바꾸면
 * 분석 무제한이 됐다 (quota.js의 reserveAnalysis가 이 값을 그대로 믿는다).
 *
 * 그래서 판단 주체를 서버로 옮긴다. 서버는 구매 토큰을 Google Play에 직접 확인하고,
 * Admin SDK로 isSubscribed를 쓴다. Admin SDK는 보안 규칙을 우회하므로,
 * 나중에 규칙에서 isSubscribed를 앱 쓰기 금지로 바꿔도 서버 경로는 그대로 동작한다.
 *
 * ⚠️ 이 파일을 배포해도 구버전 앱은 깨지지 않는다.
 * 구버전은 이 함수의 존재를 몰라 호출하지 않고, 규칙도 아직 앱 쓰기를 허용하기 때문이다.
 * 앱 쓰기를 막는 것은 b25 배포 + min_version_code 상향 이후의 별도 작업이다.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { onMessagePublished } = require("firebase-functions/v2/pubsub");
const { google } = require("googleapis");
const admin = require("firebase-admin");
const { verifyUser } = require("./quota");

if (admin.apps.length === 0) {
    admin.initializeApp();
}

const PACKAGE_NAME = "com.youngs.dailynet";

/** Play RTDN이 들어올 Pub/Sub 토픽 이름. Play Console에 같은 이름으로 등록해야 한다. */
const RTDN_TOPIC = "play-rtdn";

/**
 * 구매 토큰 → uid 매핑을 저장할 컬렉션.
 *
 * RTDN 알림에는 구매 토큰만 들어 있고 어느 사용자인지가 없다.
 * 그래서 검증 시점에 매핑을 남겨두고, 나중에 알림이 오면 이걸로 사용자를 찾는다.
 *
 * 보안 규칙에 이 경로가 없으므로 앱에서는 읽지도 쓰지도 못한다 (Firestore 기본 거부).
 */
const TOKEN_MAP = "purchase_tokens";

/**
 * Play Developer API 클라이언트.
 *
 * 키 파일을 두지 않고 함수의 기본 서비스 계정(ADC)을 그대로 쓴다.
 * 키를 발급해 Secret으로 넣는 방식보다 유출 위험이 없고 갱신할 것도 없다.
 * 대신 Play Console에서 그 서비스 계정에 권한을 줘야 한다.
 */
const auth = new google.auth.GoogleAuth({
    scopes: ["https://www.googleapis.com/auth/androidpublisher"]
});
const androidPublisher = google.androidpublisher({ version: "v3", auth });

/**
 * 이용 가능한 상태로 볼 구독 상태값.
 *
 * CANCELED를 포함하는 이유는, 해지를 눌렀어도 이미 결제한 기간이 남아 있으면
 * 그 기간까지는 쓸 수 있어야 하기 때문이다. 실제 차단은 만료 시각으로 판단한다.
 */
const ACTIVE_STATES = new Set([
    "SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
    "SUBSCRIPTION_STATE_CANCELED"
]);

/**
 * Play 응답을 보고 지금 이용 가능한지 판단한다.
 * 상태값과 만료 시각을 함께 본다. 상태가 살아 있어도 만료가 지났으면 false다.
 */
function resolveActive(subscription) {
    const state = subscription.subscriptionState;
    if (!ACTIVE_STATES.has(state)) {
        return { active: false, state, expiryTime: null };
    }

    // 상품이 여러 개일 수 있으므로 가장 늦은 만료 시각을 기준으로 삼는다
    const expiries = (subscription.lineItems || [])
        .map(item => item.expiryTime)
        .filter(Boolean)
        .map(t => new Date(t).getTime())
        .filter(t => !Number.isNaN(t));

    if (expiries.length === 0) {
        // 만료 정보를 못 받았으면 상태값만 믿는다 (테스트 구독 등)
        return { active: true, state, expiryTime: null };
    }

    const expiry = Math.max(...expiries);
    return {
        active: expiry > Date.now(),
        state,
        expiryTime: new Date(expiry).toISOString()
    };
}

// 테스트에서 검증하려고 내보낸다. 이 함수는 서버 안에서만 쓰이며 동작은 그대로다.
exports.resolveActive = resolveActive;

/** Play에 구매 토큰을 물어본다. */
async function fetchSubscription(purchaseToken) {
    const res = await androidPublisher.purchases.subscriptionsv2.get({
        packageName: PACKAGE_NAME,
        token: purchaseToken
    });
    return res.data;
}

/**
 * 판단 결과를 사용자 문서에 기록한다.
 *
 * 만료 시각도 함께 남긴다. 나중에 RTDN이 유실되더라도 이 값으로 만료를 알아볼 수 있고,
 * 콘솔에서 사용자 상태를 확인할 때도 isSubscribed만 있는 것보다 낫다.
 */
async function writeSubscriptionState(uid, result) {
    await admin.firestore().collection("users").doc(uid).set({
        isSubscribed: result.active,
        subscriptionState: result.state || null,
        subscriptionExpiry: result.expiryTime || null,
        subscriptionCheckedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
}

/**
 * 앱이 결제 직후(그리고 실행 시마다) 호출하는 검증 엔드포인트.
 *
 * 앱은 구매 토큰만 보내고, 구독 여부는 서버가 정해서 돌려준다.
 * 앱이 보낸 "나 구독자야"를 믿지 않는 것이 이 함수의 존재 이유다.
 */
exports.verifySubscription = onRequest({
    region: "asia-northeast3",
    cors: true,
    enforceAppCheck: true,
    timeoutSeconds: 30
}, async (req, res) => {
    try {
        const user = await verifyUser(req);
        if (!user) {
            return res.status(401).json({ isSubscribed: false, error: "unauthenticated" });
        }

        const purchaseToken = req.body && req.body.purchaseToken;

        /**
         * 토큰이 없으면 "활성 구매가 없다"는 보고로 받아들이고 구독을 해제한다.
         *
         * 이 경로가 필요한 이유는 예전 사용자 때문이다. b24까지는 앱이 isSubscribed를
         * 직접 썼기 때문에, 결제가 이미 끝난 계정에 true만 남아 있고 만료 시각이 없는
         * 문서가 존재한다. 그 값은 RTDN으로도 만료 백스톱으로도 정리할 수 없다.
         * (구매 토큰을 모르니 Play에 물어볼 수가 없다)
         *
         * 권한을 "빼는" 방향이라 위조 이득이 없어 그대로 믿어도 된다.
         * 반대로 앱이 구매 조회에 실패했을 때는 이 요청을 보내지 않도록 앱에서 막는다.
         */
        if (!purchaseToken) {
            await writeSubscriptionState(user.uid, {
                active: false,
                state: "NO_PURCHASE",
                expiryTime: null
            });
            console.log(`구독 해제(활성 구매 없음): uid=${user.uid}`);
            return res.status(200).json({ isSubscribed: false, state: "NO_PURCHASE" });
        }

        const subscription = await fetchSubscription(purchaseToken);
        const result = resolveActive(subscription);

        await writeSubscriptionState(user.uid, result);

        // RTDN이 왔을 때 사용자를 찾을 수 있도록 매핑을 남긴다
        await admin.firestore().collection(TOKEN_MAP).doc(purchaseToken).set({
            uid: user.uid,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });

        console.log(`구독 검증: uid=${user.uid} state=${result.state} active=${result.active}`);

        res.status(200).json({
            isSubscribed: result.active,
            state: result.state,
            expiryTime: result.expiryTime
        });
    } catch (error) {
        console.error("verifySubscription 실패:", error.message);

        // 검증에 실패했다고 구독을 꺼버리면, Play 장애 때 정상 구독자가 기능을 잃는다.
        // 판단을 못 했을 뿐이므로 기존 값을 건드리지 않고 오류만 알린다.
        res.status(500).json({ isSubscribed: null, error: "verify failed" });
    }
});

/**
 * Play가 보내는 구독 변경 알림(RTDN)을 받아 서버가 스스로 상태를 갱신한다.
 *
 * 이게 없으면 만료를 앱이 켜질 때만 알 수 있다. 예전에는 앱이 만료를 감지해
 * isSubscribed를 false로 썼는데, 그 경로를 막고 나면 앱을 켜지 않는 만료 사용자가
 * 계속 구독자로 남는다. 그래서 앱 실행과 무관하게 Play가 직접 알려주는 경로가 필요하다.
 *
 * 알림 종류(갱신/해지/만료 등)를 일일이 구분하지 않고, 알림이 오면 Play에 다시 물어본다.
 * 종류별로 분기하면 새 알림 타입이 생길 때마다 빠뜨리게 된다.
 */
exports.playSubscriptionEvent = onMessagePublished({
    topic: RTDN_TOPIC,
    region: "asia-northeast3",
    retry: false
}, async (event) => {
    try {
        const message = event.data && event.data.message;
        const raw = message && message.data
            ? Buffer.from(message.data, "base64").toString("utf8")
            : null;
        if (!raw) {
            console.warn("RTDN: 빈 메시지");
            return;
        }

        const payload = JSON.parse(raw);
        const notification = payload.subscriptionNotification;

        // 테스트 알림이나 단건 결제 알림은 여기서 처리하지 않는다
        if (!notification || !notification.purchaseToken) {
            console.log("RTDN: 구독 알림이 아니므로 무시", Object.keys(payload).join(","));
            return;
        }

        const purchaseToken = notification.purchaseToken;
        const mapSnap = await admin.firestore().collection(TOKEN_MAP).doc(purchaseToken).get();

        if (!mapSnap.exists) {
            // 아직 verifySubscription을 한 번도 안 거친 토큰. 앱이 다음 실행 때 검증하면 채워진다.
            console.warn("RTDN: 매핑 없는 구매 토큰 - 건너뜀");
            return;
        }

        const uid = mapSnap.data().uid;
        const subscription = await fetchSubscription(purchaseToken);
        const result = resolveActive(subscription);

        await writeSubscriptionState(uid, result);
        console.log(
            `RTDN 처리: type=${notification.notificationType} uid=${uid} ` +
            `state=${result.state} active=${result.active}`
        );
    } catch (error) {
        // 여기서 던지면 Pub/Sub이 재시도하는데, 같은 이유로 계속 실패하면 무한 반복이 된다.
        // (retry: false로도 막았지만 로그를 남겨 원인을 볼 수 있게 한다)
        console.error("RTDN 처리 실패:", error.message);
    }
});
