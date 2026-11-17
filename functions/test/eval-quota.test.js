/**
 * 기간 평가 횟수(reserveEvaluation) 규칙.
 *
 * 분석 횟수와 따로 센다. 평가가 분석 한도를 갉아먹으면 저녁 정산이 막힌다.
 * 날짜가 바뀌면 0부터, 상한에 닿으면 allowed: false.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "demo";
process.env.FIREBASE_CONFIG =
    process.env.FIREBASE_CONFIG || JSON.stringify({ projectId: "demo" });

const admin = require("firebase-admin");
const { DAILY_EVALUATION_LIMIT, reserveEvaluation, seoulToday } = require("../quota");

function fakeFirestore(doc) {
    const writes = [];
    return {
        writes,
        collection: () => ({ doc: () => ({}) }),
        runTransaction: async (fn) => fn({
            get: async () => ({ exists: true, data: () => doc }),
            set: (_ref, value) => writes.push(value)
        })
    };
}

async function reserveWith(doc) {
    const db = fakeFirestore(doc);
    Object.defineProperty(admin, "firestore", { value: () => db, configurable: true });
    try {
        const result = await reserveEvaluation({ uid: "u1", email: "a@b.c" });
        return { result, writes: db.writes };
    } finally {
        delete admin.firestore;
    }
}

test("기간 평가 횟수", async (t) => {
    const today = seoulToday();

    await t.test("오늘 처음이면 1로 적고 통과", async () => {
        const { result, writes } = await reserveWith({ periodEvalCount: 5, periodEvalDate: "2000-01-01" });
        assert.equal(result.allowed, true);
        assert.equal(result.count, 1);
        assert.deepEqual(writes, [{ periodEvalCount: 1, periodEvalDate: today }]);
    });

    await t.test("분석 횟수 필드는 건드리지 않는다", async () => {
        const { writes } = await reserveWith({ todayAnalysisCount: 2, freeModeAnalysisCount: 4 });
        for (const w of writes) {
            assert.equal("todayAnalysisCount" in w, false);
            assert.equal("freeModeAnalysisCount" in w, false);
        }
    });

    await t.test("상한에 닿으면 막히고 쓰지 않는다", async () => {
        const { result, writes } = await reserveWith({ periodEvalCount: DAILY_EVALUATION_LIMIT, periodEvalDate: today });
        assert.equal(result.allowed, false);
        assert.equal(result.limit, DAILY_EVALUATION_LIMIT);
        assert.equal(writes.length, 0);
    });
});
