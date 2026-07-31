/**
 * 구독 판정 검증.
 *
 * 여기가 틀리면 정상 구독자가 결제한 기능을 잃거나, 반대로 만료된 사용자가
 * 영원히 프리미엄으로 남는다. 둘 다 화면만 봐서는 알 수 없다.
 *
 * 각 테스트는 코드 주석에 적힌 "이렇게 하면 안 되는 이유"를 고정한다.
 * 나중에 정리하다가 조용히 되돌리는 것을 막는 것이 목적이다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

// firebase-admin이 자격증명을 찾다 실패하지 않도록 프로젝트를 지정해 둔다.
// (quota.js가 import 시점에 initializeApp을 호출한다)
process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "demo";
process.env.FIREBASE_CONFIG =
    process.env.FIREBASE_CONFIG || JSON.stringify({ projectId: "demo" });

const { isSubscribedNow, seoulToday } = require("../quota");
const { resolveActive } = require("../subscription");

const HOUR = 60 * 60 * 1000;
const future = () => new Date(Date.now() + 24 * HOUR).toISOString();
const past = () => new Date(Date.now() - 24 * HOUR).toISOString();

test("isSubscribedNow - 플래그와 만료 시각을 함께 본다", async (t) => {
    await t.test("플래그가 없으면 구독이 아니다", () => {
        assert.equal(isSubscribedNow({}), false);
        assert.equal(isSubscribedNow({ isSubscribed: false }), false);
    });

    await t.test("플래그가 boolean true가 아니면 구독이 아니다", () => {
        // 문자열 "true"를 넣어 우회하려는 시도를 막는다
        assert.equal(isSubscribedNow({ isSubscribed: "true" }), false);
        assert.equal(isSubscribedNow({ isSubscribed: 1 }), false);
    });

    await t.test("만료 시각이 없으면 플래그만 믿는다 (b24 이전 문서 보호)", () => {
        // 앱이 직접 쓰던 시절의 문서에는 subscriptionExpiry가 없다.
        // 여기서 막으면 기존 구독자가 한꺼번에 구독을 잃는다.
        assert.equal(isSubscribedNow({ isSubscribed: true }), true);
        assert.equal(isSubscribedNow({ isSubscribed: true, subscriptionExpiry: null }), true);
        assert.equal(isSubscribedNow({ isSubscribed: true, subscriptionExpiry: "" }), true);
    });

    await t.test("만료 전이면 구독 중이다", () => {
        assert.equal(isSubscribedNow({ isSubscribed: true, subscriptionExpiry: future() }), true);
    });

    await t.test("만료가 지났으면 플래그가 true여도 끊는다 (RTDN 유실 백스톱)", () => {
        // Pub/Sub 알림이 유실되면 플래그가 true인 채로 남는다.
        // 앱을 켜지 않는 사용자는 verifySubscription도 안 불리므로
        // 만료 시각이 유일한 안전장치다.
        assert.equal(isSubscribedNow({ isSubscribed: true, subscriptionExpiry: past() }), false);
    });

    await t.test("만료 값이 깨졌으면 막지 않는다", () => {
        // 판단을 못 하는 것이 정상 구독자의 기능을 뺏는 것보다 낫다
        assert.equal(
            isSubscribedNow({ isSubscribed: true, subscriptionExpiry: "not-a-date" }),
            true
        );
    });
});

test("resolveActive - Play 응답을 구독 여부로 바꾼다", async (t) => {
    const activeSub = (state, expiries) => ({
        subscriptionState: state,
        lineItems: expiries.map((e) => ({ expiryTime: e }))
    });

    await t.test("ACTIVE + 만료 전이면 이용 가능", () => {
        const r = resolveActive(activeSub("SUBSCRIPTION_STATE_ACTIVE", [future()]));
        assert.equal(r.active, true);
        assert.equal(r.state, "SUBSCRIPTION_STATE_ACTIVE");
        assert.ok(r.expiryTime);
    });

    await t.test("해지했어도 결제한 기간이 남았으면 이용 가능", () => {
        // CANCELED를 이용 가능 상태에 넣은 이유가 이것이다.
        // 해지 버튼을 눌렀다고 즉시 막으면 이미 낸 돈을 뺏는 것이 된다.
        const r = resolveActive(activeSub("SUBSCRIPTION_STATE_CANCELED", [future()]));
        assert.equal(r.active, true);
    });

    await t.test("해지 + 기간도 지났으면 차단", () => {
        const r = resolveActive(activeSub("SUBSCRIPTION_STATE_CANCELED", [past()]));
        assert.equal(r.active, false);
    });

    await t.test("유예 기간(GRACE_PERIOD)은 이용 가능", () => {
        // 결제 실패 중이지만 Play가 회수를 시도하는 구간이다
        const r = resolveActive(activeSub("SUBSCRIPTION_STATE_IN_GRACE_PERIOD", [future()]));
        assert.equal(r.active, true);
    });

    await t.test("이용 불가 상태는 만료 시각과 무관하게 차단", () => {
        for (const state of [
            "SUBSCRIPTION_STATE_EXPIRED",
            "SUBSCRIPTION_STATE_ON_HOLD",
            "SUBSCRIPTION_STATE_PAUSED",
            "SUBSCRIPTION_STATE_UNSPECIFIED"
        ]) {
            const r = resolveActive(activeSub(state, [future()]));
            assert.equal(r.active, false, `${state}가 통과했다`);
            assert.equal(r.expiryTime, null);
        }
    });

    await t.test("상품이 여러 개면 가장 늦은 만료를 기준으로 삼는다", () => {
        const later = new Date(Date.now() + 48 * HOUR).toISOString();
        const r = resolveActive(
            activeSub("SUBSCRIPTION_STATE_ACTIVE", [past(), later, future()])
        );
        assert.equal(r.active, true);
        assert.equal(new Date(r.expiryTime).getTime(), new Date(later).getTime());
    });

    await t.test("만료 정보를 못 받으면 상태값만 믿는다 (테스트 구독 등)", () => {
        const r = resolveActive({ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE" });
        assert.equal(r.active, true);
        assert.equal(r.expiryTime, null);
    });

    await t.test("만료 값이 깨진 항목은 무시한다", () => {
        const r = resolveActive(
            activeSub("SUBSCRIPTION_STATE_ACTIVE", ["garbage", null, future()])
        );
        assert.equal(r.active, true);
    });
});

test("seoulToday - 날짜 기준은 서울이다", async (t) => {
    await t.test("yyyy-MM-dd 형식", () => {
        assert.match(seoulToday(), /^\d{4}-\d{2}-\d{2}$/);
    });

    await t.test("런타임이 UTC여도 서울 날짜를 돌려준다", () => {
        // 함수 런타임은 UTC라 new Date()를 그냥 쓰면 한국 자정 이후 9시간 동안
        // 어제로 계산된다. 그러면 하루 횟수가 엉뚱한 시점에 초기화된다.
        const expected = new Intl.DateTimeFormat("en-CA", {
            timeZone: "Asia/Seoul",
            year: "numeric",
            month: "2-digit",
            day: "2-digit"
        }).format(new Date());

        assert.equal(seoulToday(), expected);
    });
});
