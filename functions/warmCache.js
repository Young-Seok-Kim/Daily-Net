/**
 * 영양 캐시 채우기 (운영용, 평소에는 배포하지 않는다).
 *
 * ── 쓰는 법 ────────────────────────────────────────────────
 * 1) index.js의 `exports.warmCache = ...` 줄을 살린다
 * 2) firebase deploy --only functions:warmCache
 * 3) curl -H "x-warm-token: <FOOD_API_KEY>" \
 *      "https://asia-northeast3-daily-net-95d28.cloudfunctions.net/warmCache"
 *    (환산 규칙을 고친 뒤라면 `?force=1`을 붙여 예전 캐시를 덮어쓴다)
 * 4) firebase functions:delete warmCache --region asia-northeast3 --force
 * 5) index.js 줄을 다시 주석 처리
 *
 * 105개 훑는 데 1분 남짓 걸린다.
 * ──────────────────────────────────────────────────────────
 *
 * 왜 함수로 두는가:
 * 캐시를 채우려면 Firestore 쓰기 권한이 필요한데, 로컬에서 하려면
 * `gcloud auth application-default login`이라는 **브라우저 로그인**을 거쳐야 한다.
 * 함수는 이미 서비스 계정 권한으로 돌기 때문에 그 절차가 통째로 사라진다.
 *
 * 보호:
 * `x-warm-token` 헤더가 FOOD_API_KEY와 같아야 실행된다.
 * 새 비밀을 만들지 않으려고 이미 있는 키를 그대로 쓴다. 쿼리스트링이 아니라
 * 헤더로 받는 이유는 **URL은 액세스 로그에 남기 때문**이다.
 *
 * ⚠️ `Authorization` 헤더는 쓸 수 없다. Cloud Run이 그 헤더를 **IAM 토큰으로 오인**해
 * 우리 코드에 닿기도 전에 401로 끊는다. 그래서 전용 헤더 이름을 쓴다.
 *
 * 쓰고 나면 index.js에서 빼고 함수도 지운다. 상시로 열어둘 이유가 없다.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { lookupFood, cacheKey, CACHE_PREFIX } = require("./foodDb");
const { COMMON_MENUS } = require("./commonMenus");

// quota.js가 import 시점에 admin.initializeApp()을 부른다
require("./quota");

exports.warmCache = onRequest({
    region: "asia-northeast3",
    secrets: ["FOOD_API_KEY"],
    // 앱이 부르는 게 아니라 운영자가 한 번 부르는 자리다
    enforceAppCheck: false,
    // 메뉴 100여 개를 훑는다. 조회 하나가 최대 2.8초라 넉넉히 잡는다
    timeoutSeconds: 540
}, async (req, res) => {
    const apiKey = process.env.FOOD_API_KEY;
    if (!apiKey) {
        res.status(500).json({ error: "FOOD_API_KEY 미설정" });
        return;
    }
    if (req.headers["x-warm-token"] !== apiKey) {
        res.status(403).json({ error: "forbidden" });
        return;
    }

    // 환산 규칙을 고친 뒤에는 예전 값이 캐시에 남아 있으므로 다시 채워야 한다
    const force = req.query.force === "1";
    const db = getFirestore();

    // ?clean=1 — 캐시 정리만 하고 끝낸다.
    //
    // 두 가지를 치운다.
    // 1) 규칙판(RULES_VERSION)이나 출처가 다른 **옛 문서**. 이제 아무도 읽지 않는 찌꺼기다.
    // 2) updatedAt이 **밀리초 숫자**로 들어간 문서 → Timestamp로 다시 쓴다.
    //    콘솔에서 날짜로 보여야 언제 캐시된 값인지 눈으로 확인할 수 있다.
    if (req.query.clean === "1") {
        const snap = await db.collection("foodCache").get();
        const stale = [];
        const numeric = [];

        snap.forEach((doc) => {
            if (!doc.id.startsWith(CACHE_PREFIX)) stale.push(doc.ref);
            else if (typeof doc.get("updatedAt") === "number") numeric.push(doc.ref);
        });

        // Firestore 배치는 한 번에 500건까지다
        const flush = async (jobs) => {
            for (let i = 0; i < jobs.length; i += 400) {
                const batch = db.batch();
                jobs.slice(i, i + 400).forEach((run) => run(batch));
                await batch.commit();
            }
        };

        await flush(stale.map((ref) => (b) => b.delete(ref)));
        await flush(numeric.map((ref) => (b) =>
            b.set(ref, { updatedAt: FieldValue.serverTimestamp() }, { merge: true })
        ));

        console.log(`[warmCache] 정리: 삭제 ${stale.length} / 날짜변환 ${numeric.length}`);
        res.status(200).json({
            total: snap.size,
            deleted: stale.length,
            dateFixed: numeric.length,
            prefix: CACHE_PREFIX
        });
        return;
    }

    const started = Date.now();
    const hit = [];
    const miss = [];

    // 한 번에 4개씩. 공공 API에 몰아치지 않기 위해서다.
    const CHUNK = 4;
    for (let i = 0; i < COMMON_MENUS.length; i += CHUNK) {
        await Promise.all(
            COMMON_MENUS.slice(i, i + CHUNK).map(async (menu) => {
                if (force) {
                    // lookupFood는 캐시를 먼저 본다. 지워야 다시 조회한다.
                    await db.collection("foodCache").doc(cacheKey(menu)).delete().catch(() => {});
                }
                const food = await lookupFood(menu, apiKey).catch(() => null);
                if (food) hit.push(`${menu}=${food.kcal}kcal(${food.portion})`);
                else miss.push(menu);
            })
        );
    }

    console.log(`[warmCache] 찾음 ${hit.length} / 못 찾음 ${miss.length} / ${Date.now() - started}ms`);
    res.status(200).json({
        total: COMMON_MENUS.length,
        found: hit.length,
        notFound: miss.length,
        elapsedMs: Date.now() - started,
        hit,
        miss
    });
});
