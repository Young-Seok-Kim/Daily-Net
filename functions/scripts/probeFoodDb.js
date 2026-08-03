/**
 * 공공데이터포털(data.go.kr) 식품영양성분 API를 확인하는 스크립트.
 * **키를 받은 직후 한 번은 반드시 돌려야 한다.**
 *
 * 세 가지를 알아낸다.
 *
 * 1) **어느 주소가 살아 있는가**
 *    data.go.kr은 상세 명세를 첨부 엑셀과 Swagger로만 준다. 오퍼레이션 이름을 코드에
 *    미리 확정할 수 없어서, 후보를 차례로 찔러보고 응답이 오는 것을 찾는다.
 *
 * 2) **응답 필드가 무엇인가**
 *    성분을 AMT_NUM1 / NUTR_CONT1 같은 번호로 주는데 데이터셋마다 순서가 다르다.
 *    어떤 표는 [열량·탄수화물·단백질·지방] 순이고, 어떤 표는 [열량·수분·단백질·지방·회분·탄수화물] 순이다.
 *    **틀린 자리를 읽어도 숫자는 그럴듯해 보여서** 배포 후에는 아무도 못 잡는다.
 *
 * 3) **이름으로 검색이 되는가**
 *    검색어를 무시하고 아무 제품이나 주는지, 검색을 건 결과와 안 건 결과를 비교해 확인한다.
 *
 * 사용법:
 *   cd functions
 *   FOOD_API_KEY=발급받은키 node scripts/probeFoodDb.js "코카콜라"
 *
 *   # 포털에서 오퍼레이션 주소를 확인했다면 직접 지정 (가장 확실하다)
 *   FOOD_API_KEY=키 node scripts/probeFoodDb.js "코카콜라" "FoodNtrCpntDbInfo01/getFoodNtrCpntDbInq01"
 */
const HOST = "https://apis.data.go.kr/1471000";

/**
 * 찔러볼 오퍼레이션 후보.
 * 포털 상세페이지에 적힌 주소를 알면 인자로 넘기는 게 빠르다.
 */
const CANDIDATES = [
    "FoodNtrCpntDbInfo02/getFoodNtrCpntDbInq02",
    "FoodNtrCpntDbInfo/getFoodNtrCpntDbInq",
    "FoodNtrIrdntInfo1/getFoodNtrIrdntList1",
    "FoodNtrIrdntInfo/getFoodNtrIrdntList"
];

/** 식품명 검색에 쓸 요청 변수 후보 */
const NAME_PARAMS = ["FOOD_NM_KR", "DESC_KOR", "foodNm"];

function buildUrl(endpoint, key, params) {
    // Encoding 키(이미 %가 섞인 것)를 또 인코딩하면 키가 깨진다
    const encodedKey = key.includes("%") ? key : encodeURIComponent(key);
    const qs = new URLSearchParams({ pageNo: "1", numOfRows: "5", type: "json", ...params });
    return `${HOST}/${endpoint}?serviceKey=${encodedKey}&${qs}`;
}

/** 어떤 모양으로 감싸여 있든 객체 배열을 찾아낸다 */
function extractRows(node, depth = 0) {
    if (!node || depth > 6) return null;
    if (Array.isArray(node)) {
        return node.every((v) => v && typeof v === "object") ? node : null;
    }
    if (typeof node !== "object") return null;
    for (const value of Object.values(node)) {
        const found = extractRows(value, depth + 1);
        if (found && found.length > 0) return found;
    }
    return null;
}

async function call(endpoint, key, params) {
    try {
        const res = await fetch(buildUrl(endpoint, key, params), {
            signal: AbortSignal.timeout(8000)
        });
        const text = await res.text();

        let body;
        try {
            body = JSON.parse(text);
        } catch {
            // 키가 틀리거나 주소가 없으면 JSON을 달라고 해도 XML 에러 문서가 온다
            return { status: res.status, xml: text.slice(0, 300) };
        }
        return { status: res.status, body, rows: extractRows(body) };
    } catch (e) {
        return { error: e.message };
    }
}

async function main() {
    const key = process.env.FOOD_API_KEY;
    if (!key) {
        console.error("FOOD_API_KEY 환경변수가 없습니다.");
        console.error('사용법: FOOD_API_KEY=키 node scripts/probeFoodDb.js "코카콜라"');
        process.exit(1);
    }

    const query = process.argv[2] || "코카콜라";
    const given = process.argv[3];
    const endpoints = given ? [given] : CANDIDATES;

    console.log(`검색어: ${query}`);
    console.log(`찔러볼 주소 ${endpoints.length}개\n`);

    // ── 1) 살아 있는 주소 찾기 ──────────────────────────────
    let hit = null;
    for (const endpoint of endpoints) {
        for (const nameParam of NAME_PARAMS) {
            const r = await call(endpoint, key, { [nameParam]: query });

            if (r.error) {
                console.log(`✗ ${endpoint} [${nameParam}] — ${r.error}`);
                break;
            }
            if (r.xml) {
                const reason = /SERVICE_KEY|SERVICE ERROR|등록되지/i.test(r.xml)
                    ? "키 또는 주소 문제"
                    : "JSON 아님";
                console.log(`✗ ${endpoint} [${nameParam}] — HTTP ${r.status} ${reason}`);
                break;
            }
            if (!r.rows || r.rows.length === 0) {
                console.log(`△ ${endpoint} [${nameParam}] — 응답은 왔지만 결과 0건`);
                continue;
            }

            console.log(`✅ ${endpoint} [${nameParam}] — ${r.rows.length}건`);
            hit = { endpoint, nameParam, rows: r.rows };
            break;
        }
        if (hit) break;
    }

    if (!hit) {
        console.log("\n동작하는 주소를 못 찾았습니다.");
        console.log("공공데이터포털 → 마이페이지 → 활용신청 현황 → 해당 API →");
        console.log("상세설명의 '요청주소'를 확인해서 세 번째 인자로 넘겨주세요.");
        console.log('예: node scripts/probeFoodDb.js "코카콜라" "서비스명/오퍼레이션명"');
        return;
    }

    console.log(`\n▶ 이 값을 코드에 넣으면 됩니다`);
    console.log(`   FOOD_API_ENDPOINT   = ${hit.endpoint}`);
    console.log(`   FOOD_API_NAME_PARAM = ${hit.nameParam}`);

    // ── 2) 기준량 확인 ──────────────────────────────────────
    //
    // 가장 중요한 항목이다. SERVING_SIZE는 "영양성분함량기준량"이라 대개 "100g"이다.
    // 그 경우 AMT_NUM1은 **100g당 열량**이지 한 개를 먹었을 때의 열량이 아니다.
    // 이걸 그대로 쓰면 355ml 캔을 100ml치로 계산하게 된다.
    console.log("\n=== 기준량 (제일 중요) ===");
    for (const r of hit.rows) {
        console.log(
            `- ${r.FOOD_NM_KR || r.DESC_KOR} | 업체:${r.MAKER_NM || "-"} ` +
            `| 구분:${r.DB_CLASS_NM || "-"} | 기준량:${r.SERVING_SIZE || "-"} ` +
            `| 열량:${r.AMT_NUM1} 탄:${r.AMT_NUM6} 단:${r.AMT_NUM3} 지:${r.AMT_NUM4}`
        );
    }
    console.log("기준량이 전부 100g/100ml이면 실제 섭취량으로 환산하는 코드가 필요합니다.");

    // ── 3) 응답 필드 전체 ───────────────────────────────────
    console.log("\n=== 첫 번째 행 전체 (필드 확인용) ===");
    console.log(JSON.stringify(hit.rows[0], null, 2));

    // ── 3) 검색이 실제로 먹는지 ─────────────────────────────
    const plain = await call(hit.endpoint, key, {});
    const nameOf = (r) => r.FOOD_NM_KR || r.DESC_KOR || r.FOOD_NAME || Object.values(r)[1];
    const searchedNames = hit.rows.map(nameOf);
    const plainNames = (plain.rows || []).map(nameOf);

    console.log("\n=== 이름 검색이 먹는가 ===");
    console.log("검색 O:", searchedNames.join(" / "));
    console.log("검색 X:", plainNames.join(" / "));
    if (plainNames.length > 0 && JSON.stringify(searchedNames) === JSON.stringify(plainNames)) {
        console.log("\n❌ 두 결과가 같습니다. 검색어가 무시되고 있습니다.");
    } else {
        console.log("\n✅ 결과가 다릅니다. 검색이 동작합니다.");
    }

    console.log("\n위 출력을 통째로 복사해서 알려주시면 코드를 확정하겠습니다.");
}

main().catch((e) => {
    console.error(e);
    process.exit(1);
});
