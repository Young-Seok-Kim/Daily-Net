/**
 * 사진 인식 시도 상한(DAILY_PHOTO_ATTEMPT_LIMIT) 검증.
 *
 * 사진 인식에는 횟수 제한이 없다. 남아 있는 상한은 유료 관문이 아니라
 * 계정 탈취·스크립트로 하루 수백 장을 보내는 비용 사고를 막는 방지선이다.
 * 그래서 확인할 것은 세 가지다.
 *  - 정상 사용(상한 아래)은 성공 횟수와 무관하게 늘 통과한다
 *  - 한 요청에 여러 장이면 장수만큼 센다 (토큰 비용이 장수에 비례한다)
 *  - 상한을 넘기면 막히고, 무제한 계정은 막히지 않는다
 */
const test = require("node:test");
const assert = require("node:assert/strict");

process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "demo";
process.env.FIREBASE_CONFIG =
    process.env.FIREBASE_CONFIG || JSON.stringify({ projectId: "demo" });

const admin = require("firebase-admin");
const { DAILY_PHOTO_ATTEMPT_LIMIT, reservePhoto, seoulToday } = require("../quota");

/** free-mode.test.js와 같은 최소한의 가짜 Firestore */
function fakeFirestore(doc, { unlimitedUser = false } = {}) {
    const writes = [];
    return {
        writes,
        collection: (name) => ({ doc: () => ({ collection: name }) }),
        runTransaction: async (fn) => fn({
            get: async (ref) => ref.collection === "unlimited_users"
                ? { exists: unlimitedUser, data: () => ({}) }
                : { exists: true, data: () => doc },
            set: (_ref, value) => writes.push(value)
        })
    };
}

async function reserveWith(doc, count, options) {
    const db = fakeFirestore(doc, options);
    Object.defineProperty(admin, "firestore", { value: () => db, configurable: true });
    try {
        const result = await reservePhoto({ uid: "u1", email: "a@b.c" }, count);
        return { result, writes: db.writes };
    } finally {
        delete admin.firestore;
    }
}

test("사진 인식 시도 상한", async (t) => {
    const today = seoulToday();

    await t.test("성공 횟수가 아무리 많아도 상한 아래면 통과한다", async () => {
        const { result, writes } = await reserveWith({
            todayPhotoCount: 120,
            todayPhotoAttempts: 150,
            lastPhotoDate: today
        }, 1);

        assert.equal(result.allowed, true);
        assert.equal(writes[0].todayPhotoAttempts, 151);
        assert.equal(writes[0].todayPhotoCount, 121);
    });

    await t.test("여러 장이면 장수만큼 센다", async () => {
        const { result, writes } = await reserveWith({
            todayPhotoAttempts: 10,
            lastPhotoDate: today
        }, 5);

        assert.equal(result.allowed, true);
        assert.equal(result.attempts, 15);
        assert.equal(writes[0].todayPhotoAttempts, 15);
        // 요청 단위의 성공 횟수는 1만 오른다
        assert.equal(writes[0].todayPhotoCount, 1);
    });

    await t.test("장수가 상한을 넘기면 막힌다", async () => {
        const { result, writes } = await reserveWith({
            todayPhotoAttempts: DAILY_PHOTO_ATTEMPT_LIMIT - 2,
            lastPhotoDate: today
        }, 3);

        assert.equal(result.allowed, false);
        assert.equal(result.limit, DAILY_PHOTO_ATTEMPT_LIMIT);
        assert.equal(writes.length, 0);
    });

    await t.test("딱 상한까지는 허용한다", async () => {
        const { result } = await reserveWith({
            todayPhotoAttempts: DAILY_PHOTO_ATTEMPT_LIMIT - 2,
            lastPhotoDate: today
        }, 2);

        assert.equal(result.allowed, true);
    });

    await t.test("무제한 계정은 상한을 넘어도 통과하고 횟수는 계속 센다", async () => {
        const { result, writes } = await reserveWith({
            todayPhotoAttempts: DAILY_PHOTO_ATTEMPT_LIMIT + 50,
            lastPhotoDate: today
        }, 5, { unlimitedUser: true });

        assert.equal(result.allowed, true);
        assert.equal(writes[0].todayPhotoAttempts, DAILY_PHOTO_ATTEMPT_LIMIT + 55);
    });

    await t.test("날짜가 바뀌면 0부터 다시 센다", async () => {
        const { result, writes } = await reserveWith({
            todayPhotoAttempts: DAILY_PHOTO_ATTEMPT_LIMIT,
            todayPhotoCount: 40,
            lastPhotoDate: "2000-01-01"
        }, 2);

        assert.equal(result.allowed, true);
        assert.equal(writes[0].todayPhotoAttempts, 2);
        assert.equal(writes[0].todayPhotoCount, 1);
        assert.equal(writes[0].lastPhotoDate, today);
    });

    await t.test("장수가 0이거나 빠져도 1장으로 센다", async () => {
        const { writes } = await reserveWith({ lastPhotoDate: today }, 0);
        assert.equal(writes[0].todayPhotoAttempts, 1);
    });
});
