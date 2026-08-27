/**
 * 전면 무료 개방 스위치(FREE_FOR_ALL) 검증.
 *
 * 이 스위치는 언제든 되돌릴 수 있어야 하므로, 테스트도 켠 상태와 끈 상태를 모두 확인한다.
 * 스위치를 false로 바꿔도 이 파일은 그대로 통과해야 한다.
 *
 * 가장 중요한 것은 "무료 개방 중에는 todayAnalysisCount가 올라가지 않는다"이다.
 * 이미 설치된 앱은 서버 응답과 무관하게 그 값이 3에 닿으면 스스로 결제창을 띄운다.
 * 여기가 깨지면 무료로 열어도 사용자는 결제창을 보게 된다.
 */
const test = require("node:test");
const assert = require("node:assert/strict");

process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "demo";
process.env.FIREBASE_CONFIG =
    process.env.FIREBASE_CONFIG || JSON.stringify({ projectId: "demo" });

const admin = require("firebase-admin");
const { FREE_FOR_ALL, FREE_MODE_ANALYSIS_LIMIT, reserveAnalysis, seoulToday } = require("../quota");

/**
 * Firestore를 대신하는 최소한의 가짜.
 * 트랜잭션이 읽을 사용자 문서 하나와, 트랜잭션이 쓴 값들만 기억한다.
 *
 * 컬렉션 이름을 문서에 달아두는 이유는 unlimited_users 조회를 구분하기 위해서다.
 * 구분하지 않으면 무제한 계정으로 읽혀서, 스위치를 끈 상태의 검증이 항상 통과해 버린다.
 */
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

/**
 * 가짜 Firestore를 물려 reserveAnalysis를 한 번 돌리고, 결과와 쓰인 값을 돌려준다.
 *
 * admin.firestore는 프로토타입의 getter라 그냥 대입하면 조용히 무시되고
 * 진짜 Firestore에 붙는다. defineProperty로 덮고 끝나면 지워서 되돌린다.
 */
async function reserveWith(doc) {
    const db = fakeFirestore(doc);
    Object.defineProperty(admin, "firestore", { value: () => db, configurable: true });
    try {
        const result = await reserveAnalysis({ uid: "u1", email: "a@b.c" });
        return { result, writes: db.writes };
    } finally {
        delete admin.firestore;
    }
}

test("무료 개방 스위치", async (t) => {
    const today = seoulToday();

    await t.test("하루 3회를 이미 쓴 사용자도 분석할 수 있다", async () => {
        const { result } = await reserveWith({
            todayAnalysisCount: 3,
            lastAnalyzedDate: today
        });

        if (FREE_FOR_ALL) {
            assert.equal(result.allowed, true);
            assert.equal(result.unlimited, true);
        } else {
            // 스위치를 되돌린 상태. 예전 정책대로 3회에서 막힌다.
            assert.equal(result.allowed, false);
            assert.equal(result.limit, 3);
        }
    });

    await t.test("무료 개방 중에는 todayAnalysisCount를 올리지 않는다", async () => {
        const { writes } = await reserveWith({
            freeModeAnalysisCount: 7,
            freeModeAnalysisDate: today,
            todayAnalysisCount: 0,
            lastAnalyzedDate: today
        });

        const written = writes[0];
        if (FREE_FOR_ALL) {
            // 구버전 앱이 보는 필드는 0으로 눌러 두고, 사용량은 따로 센다.
            assert.equal(written.todayAnalysisCount, 0);
            assert.equal(written.freeModeAnalysisCount, 8);
        } else {
            assert.equal(written.todayAnalysisCount, 1);
        }
    });

    await t.test("무료라도 비용 사고 방지선은 남아 있다", async () => {
        const { result } = await reserveWith({
            freeModeAnalysisCount: FREE_MODE_ANALYSIS_LIMIT,
            freeModeAnalysisDate: today,
            todayAnalysisCount: 0,
            lastAnalyzedDate: today
        });

        if (FREE_FOR_ALL) {
            assert.equal(result.allowed, false);
            assert.equal(result.limit, FREE_MODE_ANALYSIS_LIMIT);
        } else {
            // 끈 상태에서는 freeMode* 필드를 아예 보지 않는다
            assert.equal(result.allowed, true);
        }
    });

    await t.test("날짜가 바뀌면 무료 개방 사용량도 0부터 다시 센다", async () => {
        const { result, writes } = await reserveWith({
            freeModeAnalysisCount: FREE_MODE_ANALYSIS_LIMIT,
            freeModeAnalysisDate: "2000-01-01",
            todayAnalysisCount: 0,
            lastAnalyzedDate: "2000-01-01"
        });

        assert.equal(result.allowed, true);
        if (FREE_FOR_ALL) {
            assert.equal(writes[0].freeModeAnalysisCount, 1);
        }
    });

    await t.test("무료 개방 상한은 예전 유료 한도보다 넉넉해야 한다", () => {
        // 반대로 두면 무료로 연 뒤에 오히려 덜 쓰이게 된다
        assert.ok(FREE_MODE_ANALYSIS_LIMIT > 3);
    });
});
