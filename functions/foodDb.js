/**
 * 식약처 식품영양성분 DB 조회.
 *
 * 왜 필요한가:
 * 지금까지 칼로리는 **Gemini의 기억**에서 나왔다. 프롬프트에 "식약처 DB를 기준으로 하라"고
 * 적혀 있었지만 실제로 조회하는 곳은 없었고, 모델은 그 문장을 톤 지시로 읽었을 뿐이다.
 * 브랜드 가공식품처럼 정답이 정해진 것까지 추정으로 답하는 건 낭비다.
 *
 * 어디까지 맡기는가:
 * 이 모듈은 **이름이 확실히 맞는 것만** 돌려준다. 애매하면 null이다.
 * 잘못 붙인 제품의 정확한 숫자는, 대충 맞는 추정값보다 나쁘다.
 * (사용자는 숫자가 DB에서 왔다는 걸 모르므로 틀려도 의심하지 않는다)
 *
 * 왜 공공데이터포털(data.go.kr)인가:
 * 식품안전나라(foodsafetykorea)에도 같은 데이터가 있지만, 남아 있는 서비스가 전부
 * **파일 다운로드 유형**이라 OpenAPI 이용신청 자체가 되지 않는다.
 * ("OpenAPI 이용신청은 서비스유형이 O(XML/JSON)인 서비스만 가능")
 * 예전에 쓰던 I2790(~2023)도 신청 목록에서 빠졌다. 실시간 조회가 되는 경로는 이제 여기뿐이다.
 *
 * 데이터셋: 식품의약품안전처_식품영양성분DB정보
 *   https://www.data.go.kr/data/15127578/openapi.do
 */
const { getFirestore, FieldValue } = require("firebase-admin/firestore");

/**
 * 오퍼레이션 주소. 데이터셋 15127578의 Swagger 명세로 확인한 값이다.
 * 이름이 `01`이 아니라 **`02`** 다. 01은 존재하지 않는다(NO_OPENAPI_SERVICE_ERROR).
 */
const HOST = "https://apis.data.go.kr/1471000";
const ENDPOINT = process.env.FOOD_API_ENDPOINT || "FoodNtrCpntDbInfo02/getFoodNtrCpntDbInq02";

/** 식품명 검색에 쓰는 요청 변수 (업체명은 MAKER_NM, 품목제조보고번호는 ITEM_REPORT_NO) */
const NAME_PARAM = process.env.FOOD_API_NAME_PARAM || "FOOD_NM_KR";

/**
 * 환산·매칭 규칙의 판.
 *
 * 캐시 키에 섞어 넣는다. 규칙을 고치면 **예전 규칙으로 계산해 둔 값이 캐시에 남아**
 * 아무리 코드를 고쳐도 옛날 숫자가 계속 나온다. 실제로 겪었다 —
 * 라떼가 검은콩 라떼로 붙던 문제를 고쳤는데 캐시 때문에 그대로였다.
 * 규칙을 바꿀 때마다 이 값을 올릴 것.
 */
const RULES_VERSION = "v3";

/**
 * 한 번에 받아올 후보 수.
 *
 * 5개로 두면 안 된다. "신라면"을 검색하면 **정확히 일치하는 행이 6번째**에 있고
 * 그 앞을 신라면건면·신라면블랙이 채운다. 앞쪽만 보면 엉뚱한 제품이 걸린다.
 * DB가 제품명 순으로 주지 않기 때문에 넉넉히 받아 그중에서 골라야 한다.
 */
const CANDIDATE_COUNT = 15;

/**
 * 검색어로 삼을 낱말 수의 상한.
 *
 * 이걸 넘는 이름은 아예 조회하지 않는다. 식약처 DB의 식품명은 짧은데
 * 모델은 "베러핏 고단백 저당 쉐이크 옛날커피맛" 같은 긴 이름을 내놓는다.
 * 찾을 가망이 없으면서 재조회까지 타서 **7초를 쓰고 실패**한다.
 *
 * 3으로 뒀더니 "파워에이드 마운틴 블라스트"가 통과해 매 정산 3초를 먹었다.
 * 2면 "농심 자갈치"(브랜드+이름)는 살고 맛 이름까지 붙은 것은 걸러진다.
 */
const MAX_QUERY_WORDS = 2;

/**
 * 개별 조회 제한 시간.
 *
 * 실측하니 이 API가 느리다 — 행 수와 거의 무관하게 **1~4초**가 걸린다.
 *
 * ⚠️ 반드시 [TOTAL_BUDGET_MS]보다 **뚜렷하게 짧아야** 한다.
 * 둘이 같으면 개별 타임아웃이 잡히기 전에 전체 예산이 먼저 잘라버려,
 * 실패를 캐시할 틈이 없어진다. 그러면 같은 조회를 매 정산마다 다시 하며 계속 시간만 쓴다.
 *
 * 2초로 조였더니 신라면·코카콜라처럼 **찾을 수 있는 것까지 잘려나갔다.**
 * 한 번 찾아두면 그 뒤로는 캐시라 60ms면 끝나므로, 첫 조회에는 여유를 주는 편이 낫다.
 */
const TIMEOUT_MS = 2800;

/**
 * 조회 단계 전체 제한 시간.
 *
 * 개별 조회는 병렬이라 보통 가장 느린 하나로 끝나지만, 이 앱은 지연을 깎으려고
 * 모델의 thinking까지 꺼둔 곳이다. **분석 전체가 영양 DB 때문에 늘어지면 안 된다.**
 * 여기서 끊기면 그 요청은 조회 없이 모델 추정값 그대로 나간다.
 */
const TOTAL_BUDGET_MS = 4000;

/**
 * 이 점수 아래는 버린다 (0~1).
 *
 * 사실상 **완전일치만 통과**시키는 값이다. 실제 데이터로 재본 점수를 보면 이유가 분명하다.
 *   신라면 → 신라면      1.00  ← 이것만 맞다
 *   신라면 → 신라면컵     0.92  (65g 컵라면, 전혀 다른 제품)
 *   신라면 → 신라면건면    0.88  (건면은 열량이 100kcal 이상 낮다)
 *   초코파이 → 생초코파이  0.94
 *
 * 0.9쯤으로 낮추면 히트율은 오르지만 저런 것들이 전부 통과한다.
 * 엉뚱한 제품의 정확한 숫자는 대충 맞는 추정값보다 나쁘다 — 사용자는 그 숫자가
 * DB에서 왔다는 걸 모르므로 틀려도 의심하지 않는다.
 */
const MIN_SCORE = 0.95;

/**
 * 같은 계열 제품끼리 열량이 이 배수를 넘게 벌어지면 어느 것인지 알 수 없다고 보고 포기한다.
 * 포카칩은 45g·60g·110g·124g 봉지가 뒤섞여 있어 여기서 걸린다.
 */
const VARIANT_SPREAD = 1.5; // (남겨둠: 후보 여럿을 비교하던 시절의 기준)

/**
 * 응답 필드 이름. 데이터셋 15127578 Swagger 명세로 확인했다.
 *
 * **성분 번호가 순서대로가 아니다.** 그냥 1·2·3·4를 열량·탄수화물·단백질·지방으로 읽으면
 * 탄수화물 자리에서 수분을, 지방 자리에서 회분을 읽게 된다.
 *   AMT_NUM1 에너지(kcal)  AMT_NUM2 수분(g)      AMT_NUM3 단백질(g)
 *   AMT_NUM4 지방(g)       AMT_NUM5 회분(g)      AMT_NUM6 탄수화물(g)
 *   AMT_NUM7 당류(g)       AMT_NUM13 나트륨(mg)  AMT_NUM23 콜레스테롤(mg)
 *
 * 옛 식품안전나라(NUTR_CONT*) 이름도 함께 받아둔다. 그쪽은 순서가 또 달라서
 * (1 열량 / 2 탄수화물 / 3 단백질 / 4 지방) 자리마다 짝을 맞춰 적었다.
 */
const FIELD = {
    name: ["FOOD_NM_KR", "DESC_KOR"],
    maker: ["MAKER_NM", "SELLER_MANUFAC_NM", "IMP_MANUFAC_NM", "MAKER_NAME"],
    /** 영양성분함량기준량. 실제로는 거의 항상 "100g" / "100mL"다 */
    basis: ["SERVING_SIZE", "SERVING_WT"],
    kcal: ["AMT_NUM1", "NUTR_CONT1"],
    carb: ["AMT_NUM6", "NUTR_CONT2"],
    protein: ["AMT_NUM3", "NUTR_CONT3"],
    fat: ["AMT_NUM4", "NUTR_CONT4"]
};

/**
 * 한 번에 먹는 양으로 볼 필드. 앞에서부터 값이 있는 것을 쓴다.
 *
 * 성분값은 **100g/100mL당** 수치라 그대로 쓰면 안 된다.
 * 콜라 한 병을 100mL치(43kcal)로 계산하게 된다.
 *
 * - DISH_ONE_SERVING : 음식 1인분량 (김밥·국밥 등 조리음식)
 * - Z10500           : 식품중량 = 포장 총 내용량 (콜라 300mL 한 병)
 * - NUTRI_AMOUNT_SERVING : 1회 섭취참고량 (콜라 200mL)
 *
 * 가공식품에서 총 내용량을 1회 섭취참고량보다 앞에 두는 이유는,
 * 사람이 "콜라 한 캔"이라고 적을 때 **포장 하나를 다 먹었다는 뜻**이기 때문이다.
 * 참고량은 영양표시용 기준일 뿐 실제로 그만큼만 먹고 남기지 않는다.
 */
const PORTION_FIELDS = ["DISH_ONE_SERVING", "Z10500", "NUTRI_AMOUNT_SERVING"];

/**
 * 총 내용량이 1회 참고량의 이 배수를 넘으면 **나눠 먹는 포장**으로 본다.
 *
 * 콜라 300mL(참고량 200mL, 1.5배)는 한 번에 다 마시고,
 * 오예스 360g(참고량 30g, 12배)은 열두 번에 나눠 먹는다.
 *
 * 3으로 두면 **새우깡 한 봉지(90g, 참고량 30g)가 딱 걸려** 3분의 1만 먹은 걸로 계산되고,
 * 5로 두면 **초코에몽 4개들이(720mL)를 한 번에 다 마신 걸로** 계산된다. 그 사이 값이다.
 * 과자 한 봉지는 대개 한 번에 다 먹으므로 그보다 여유를 준다.
 */
const PACK_SPLIT_RATIO = 4;

/** 한 번에 먹는 양의 하한·상한 (g 또는 mL) */
const MIN_PORTION_G = 5;
const MAX_PORTION_G = 1500;

/**
 * 1회 참고량이 없는 행에서, 포장 총량을 한 번에 먹는 양으로 믿어줄 상한.
 *
 * 참고량이 있으면 [PACK_SPLIT_RATIO]로 나눠 먹는 포장인지 가릴 수 있지만,
 * 참고량이 아예 없으면 판단할 근거가 없다. 그때 총량이 크면 묶음일 확률이 높다.
 * 초코에몽 720mL(4개들이)가 여기서 걸린다 — 한 번에 720mL를 마시지는 않는다.
 *
 * 국밥 한 그릇(800g)처럼 진짜 큰 1인분은 DISH_ONE_SERVING으로 들어오므로 영향받지 않는다.
 */
const SINGLE_PACK_MAX_G = 500;

/**
 * 캐시 한 칸이 살아 있는 기간(일).
 *
 * 두 가지를 한 번에 해결한다.
 *
 * 1) **캐시가 늙지 않게 한다.** 지금 구조는 한 번 캐시되면 영원히 그 값이다.
 *    식약처가 성분을 고치거나 없던 제품이 새로 등록돼도 우리는 옛날 값(또는 miss)을 계속 쓴다.
 *    만료되면 다음 조회 때 다시 물어보므로 그 창구가 열린다.
 * 2) **RULES_VERSION을 올렸을 때 옛 문서가 저절로 사라진다.**
 *    새 판으로 넘어가면 옛 이름은 아무도 다시 쓰지 않아 만료되고 그대로 지워진다.
 *
 * "안 쓰면 지운다"가 아니라 **"쓴 지 오래되면 지운다"** 이다.
 * 문서는 캐시에 없을 때만 쓰므로, 매일 조회되는 메뉴여도 이 기간이 지나면 한 번 다시 물어본다.
 * 읽을 때마다 기한을 늘리려면 캐시 적중이 전부 쓰기가 되는데, 그러면 캐시를 쓰는 의미가 없다.
 *
 * ⚠️ 이 필드만 넣는다고 지워지지 않는다. **Firebase 콘솔에서 foodCache 컬렉션에
 * expireAt 기준 TTL 정책을 켜야** 실제로 삭제된다. (README 참고)
 */
const CACHE_TTL_DAYS = 90;

/**
 * 조회를 끝내지 못했을 때 다시 시도하기까지 기다리는 시간(시간).
 *
 * "DB에 없다"와 달리 **"지금은 안 된다"** 이므로 90일씩 묶어두면 안 된다.
 * 그렇다고 안 막아두면 매 정산마다 같은 조회를 다시 시도하며 시간만 쓴다.
 *
 * 24시간으로 뒀더니 너무 길었다. 캐시를 비운 직후 여러 개를 한꺼번에 조회하다
 * **우리 예산에 잘린 것**까지 하루 종일 묶여, 멀쩡히 찾을 수 있는 신라면·코카콜라가
 * 보정되지 않았다. 목적은 "매번 재시도하지 않는 것"이지 "하루 포기"가 아니다.
 */
const FAILURE_RETRY_HOURS = 1;

/**
 * 종류를 가리키는 말 → DB 식품대분류(FOOD_CAT1_NM)에서 찾을 조각.
 *
 * 왜 필요한가:
 * 사람들은 브랜드를 늘 기억하지 못한다. "자갈치 과자"처럼 **종류로 좁혀 적는 게 더 자연스럽다.**
 * DB의 대분류가 정확히 그 구분을 들고 있다.
 *   자갈치문어맛 → 과자류·빵류 또는 떡류
 *   자갈치어묵   → 수산가공식품류
 * 브랜드를 몰라도 "과자"만 붙이면 어묵 1kg이 걸리는 사고를 막을 수 있다.
 */
const CATEGORY_HINTS = {
    과자: ["과자", "빵"],
    쿠키: ["과자", "빵"],
    비스킷: ["과자", "빵"],
    파이: ["과자", "빵"],
    빵: ["빵", "과자"],
    떡: ["떡", "과자"],
    음료: ["음료"],
    주스: ["음료"],
    탄산: ["음료"],
    커피: ["음료", "커피"],
    차: ["음료", "차"],
    라면: ["면"],
    면: ["면"],
    우유: ["유가공"],
    요거트: ["유가공"],
    치즈: ["유가공"],
    어묵: ["수산"],
    생선: ["수산", "어패"],
    과일: ["과일"],
    아이스크림: ["빙과"]
};

/**
 * 검색어에서 종류를 가리키는 말을 떼어낸다.
 *
 * 떼어낸 말은 **검색에 쓰면 안 된다**("자갈치 과자"로 검색하면 0건).
 * 대신 후보를 대분류로 거르는 데 쓴다.
 */
function splitCategoryHint(normalizedQuery) {
    const words = normalizedQuery.split(" ").filter(Boolean);
    // 이름 자체가 종류어 하나뿐이면(예: "빵") 뗄 수 없다
    if (words.length < 2) return { hint: null, core: normalizedQuery };

    const at = words.findIndex((w) => CATEGORY_HINTS[w]);
    if (at < 0) return { hint: null, core: normalizedQuery };

    return {
        hint: CATEGORY_HINTS[words[at]],
        core: words.filter((_, i) => i !== at).join(" ")
    };
}

/** 후보 이름 중 이 행에 실제로 있는 첫 번째 값 */
function pick(row, keys) {
    for (const k of keys) {
        if (row[k] !== undefined && row[k] !== null && String(row[k]).trim() !== "") {
            return row[k];
        }
    }
    return "";
}

/**
 * 값이 상식적인 범위인가.
 *
 * 필드 자리를 잘못 읽었을 때의 마지막 방어선이다. 열량 자리에서 수분(g)이나
 * 나트륨(mg)을 읽으면 대개 여기서 걸린다. 걸리면 고치지 않고 모델 추정을 그대로 둔다.
 */
function isPlausible(food) {
    if (!(food.kcal > 0 && food.kcal <= 2000)) return false;
    return [food.carb, food.protein, food.fat].every((v) => v >= 0 && v <= 300);
}

/** 양을 세는 단위 */
const UNITS =
    "g|kg|ml|l|cc|그램|킬로|리터|인분|공기|그릇|잔|컵|캔|병|팩|봉지|봉|개|조각|장|줄|마리|스푼|숟갈|접시|판|알|톨";

/** 수를 세는 한글 말 (한 공기, 두 개, 반 그릇) */
const COUNTERS = "한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|반";

/** 양이나 크기를 뭉뚱그리는 말. 단독으로 오면 뗀다 */
const VAGUE = new Set([
    "조금", "약간", "많이", "적게", "소량", "곱빼기", "정도", "사이즈", "쯤",
    "톨", "그란데", "벤티", "숏", "라지", "레귤러", "미디엄", "스몰", "큰", "작은"
]);

/**
 * 검색어 정리.
 *
 * 사용자가 적는 말과 DB의 제품명은 형태가 다르다.
 * "스타벅스 아메리카노 톨 1잔" 에서 수량·단위를 걷어내야 제품명에 가까워진다.
 *
 * 캐시 적중률에도 직결된다. `신라면`과 `신라면 1개`가 다른 키가 되면
 * 같은 라면을 매번 새로 조회하게 된다. 조회 한 번이 1~4초라 이게 그대로 지연이 된다.
 *
 * **낱말 단위로만 뗀다.** 부분 문자열로 지우면 "한우"의 "한", "우유"의 "유" 같은
 * 멀쩡한 제품명이 깎여 나간다.
 */
function normalize(name) {
    const cleaned = String(name || "")
        .toLowerCase()
        // 괄호 안 부연 설명 제거: "라떼(무지방)" → "라떼"
        .replace(/\([^)]*\)/g, " ")
        // 숫자에 단위가 붙은 형태: 300ml, 2개, 1인분
        .replace(new RegExp(`\\d+(\\.\\d+)?\\s*(${UNITS})`, "g"), " ")
        // 한글 수사에 단위가 붙은 형태: 한공기, 반개, 두조각
        .replace(new RegExp(`(^|\\s)(${COUNTERS})\\s*(${UNITS})(?=\\s|$)`, "g"), " ")
        .replace(/[·,./\-_]/g, " ")
        .replace(/\s+/g, " ")
        .trim();

    // 남은 낱말 중 수량·크기만 가리키는 것을 뗀다
    const unitOnly = new RegExp(`^(${UNITS})$`);
    const counterOnly = new RegExp(`^(${COUNTERS})$`);
    const numberOnly = /^\d+(\.\d+)?$/;

    return cleaned
        .split(" ")
        .filter((w) => w && !VAGUE.has(w) && !unitOnly.test(w) && !counterOnly.test(w) && !numberOnly.test(w))
        .join(" ");
}

/** 한글 수사 → 숫자 */
const COUNTER_VALUE = {
    한: 1, 두: 2, 세: 3, 네: 4, 다섯: 5, 여섯: 6, 일곱: 7, 여덟: 8, 아홉: 9, 열: 10, 반: 0.5
};

/**
 * "코카콜라 2캔"에서 **2**를 읽어낸다. 수량이 없으면 1.
 *
 * 왜 필요한가:
 * DB가 주는 값은 **한 개(또는 1인분) 기준**이다. [normalize]가 검색을 위해 수량을 떼어내는데,
 * 그 정보를 그냥 버리면 "콜라 2캔"에 1캔치 129kcal을 넣게 된다.
 * 떼어내되 **몇 개였는지는 기억해서** DB 값에 곱해야 한다.
 *
 * 무게·부피(300ml, 200g)는 수량이 아니다. 그건 그 제품의 크기를 적은 것이라
 * 곱하면 안 된다. 개수를 세는 단위만 본다.
 */
const COUNT_UNITS = "인분|공기|그릇|잔|컵|캔|병|팩|봉지|봉|개|조각|장|줄|마리|스푼|숟갈|접시|판|알";

/**
 * **크기가 정해지지 않은 단위** — 붙으면 DB가 아는 한 번 먹는 양과 크기가 아예 다르다.
 *
 * 나머지 세는 단위는 DB 값과 크기가 대충 맞는다. 콜라 1캔은 DB의 1캔이고,
 * 새우깡 1봉지는 DB의 1봉지다. 그래서 개수만 곱하면 됐다. 그런데 이것들은 다르다:
 *
 *   - **마리** — 닭강정 1마리는 800g쯤인데 DB가 아는 닭강정은 184g들이 포장 하나다
 *   - **판**   — 피자 1판은 DB의 한 조각이 아니다
 *   - **통**   — 치킨 1통, 아이스크림 1통도 마찬가지다
 *
 * 실제로 겪었다 — `닭강정 1마리`를 모델이 2,200kcal로 봤는데 184g 기준 403으로 덮어썼다.
 * 개수를 곱해도 소용없다. 1을 곱하니 그대로 403이다. 애초에 곱할 단위가 틀린 것이다.
 * (2026-08-05 22:59)
 *
 * **`그릇`·`대접`·`접시`·`공기`·`컵`·`잔`은 일부러 뺐다.** 담는 그릇이라 크기가 흔들릴 것 같지만,
 * DB가 주는 기준량이 **이미 한 그릇**이라 실제로는 잘 맞는다. 확인한 값들이다:
 *   `김치찌개`   기준 400g → 244kcal   (400g이 딱 한 그릇이다. 여덟 번 모두 이 값)
 *   `잡곡밥 1공기` 300 → 292           (여섯 번 모두)
 * 여기에 넣으면 멀쩡히 되던 보정까지 막힌다. 위의 셋과 달리 **다른 것을 가리키지 않는다.**
 *
 * 다만 `탕수육 1접시` 650 → 388은 미심쩍다. 기준 343g에 100g당 113kcal인데
 * 튀긴 고기에 소스를 부은 음식치고 낮다. `maker`가 비고 포장 총 내용량 기준인 걸 보면
 * **냉동·간편식 행이 외식 탕수육에 붙은 것**으로 보인다. 그렇다면 이건 단위 문제가 아니라
 * **매칭 문제**라서, 여기서 `접시`를 막는 건 증상만 가리는 셈이다. 그래서 그대로 뒀다.
 * 같은 게 자주 보이면 그때는 이쪽이 아니라 [pickBest]를 봐야 한다.
 *
 * 숫자나 한글 수사가 **바로 앞에 붙은 경우만** 본다. 그러지 않으면 `통닭`·`통밀빵`의 '통'이 걸린다.
 */
const BULK_UNITS = "마리|판|통";

function bulkUnitOf(rawName) {
    const numerals = Object.keys(COUNTER_VALUE).join("|");
    const matched = String(rawName || "")
        .match(new RegExp(`(?:\\d+(?:\\.\\d+)?|${numerals})\\s*(${BULK_UNITS})`));
    return matched ? matched[1] : null;
}

/**
 * "코카콜라 500ml"에서 **500**을 읽어낸다. 적혀 있지 않으면 null.
 *
 * 개수([quantityOf])와 반대 개념이다. 이건 **그 제품의 크기**다.
 *
 * 왜 필요한가:
 * DB가 고른 행은 300mL짜리인데 사용자는 500mL를 마셨을 수 있다.
 * 크기가 적혀 있으면 그건 추정이 아니라 **읽은 사실**이므로, DB의 100g당 수치를
 * 그 크기에 맞춰 환산하는 쪽이 정확하다. 안 그러면 맞는 값(215)을 틀린 값(129)으로 덮는다.
 *
 * 단위가 반드시 있어야 한다. 맨 숫자는 개수일 수도 있어 여기서 다루지 않는다.
 */
function statedAmountOf(rawName) {
    const m = String(rawName || "").toLowerCase().match(/(\d+(?:\.\d+)?)\s*(kg|g|ml|l|cc)(?![a-z가-힣])/);
    if (!m) return null;

    let value = parseFloat(m[1]);
    if (!Number.isFinite(value) || value <= 0) return null;
    if (m[2] === "kg" || m[2] === "l") value *= 1000;

    // 한 번에 먹는 양으로 볼 수 없는 값은 무시한다
    return value >= MIN_PORTION_G && value <= MAX_PORTION_G ? value : null;
}

function quantityOf(rawName) {
    const text = String(rawName || "").toLowerCase();

    // 숫자 + 세는 단위: "2캔", "3개", "1.5인분"
    const byNumber = text.match(new RegExp(`(\\d+(?:\\.\\d+)?)\\s*(?:${COUNT_UNITS})`));
    if (byNumber) {
        const n = parseFloat(byNumber[1]);
        // 20개를 넘게 적었다면 수량이 아니라 다른 숫자일 것이다
        if (Number.isFinite(n) && n > 0 && n <= 20) return n;
    }

    // 한글 수사 + 세는 단위: "두 캔", "반 개", "한공기"
    const byWord = text.match(
        new RegExp(`(${Object.keys(COUNTER_VALUE).join("|")})\\s*(?:${COUNT_UNITS})`)
    );
    if (byWord) return COUNTER_VALUE[byWord[1]];

    return 1;
}

/**
 * 두 이름이 얼마나 같은지 (0~1).
 *
 * 편집거리 대신 **글자 단위 포함 관계**로 본다.
 * 한국어 제품명은 "아메리카노"와 "스타벅스아메리카노"처럼 한쪽이 다른 쪽을 품는 경우가 많은데,
 * 편집거리로 재면 길이 차이 때문에 점수가 크게 깎인다.
 */
function similarity(a, b) {
    const x = a.replace(/\s/g, "");
    const y = b.replace(/\s/g, "");
    if (!x || !y) return 0;
    if (x === y) return 1;

    const [shortStr, longStr] = x.length <= y.length ? [x, y] : [y, x];
    // 짧은 쪽이 긴 쪽에 통째로 들어 있으면 길이 비율만큼 인정한다.
    // "아메리카노"(5) ⊂ "스타벅스아메리카노"(9) → 0.55 + 보정
    if (longStr.includes(shortStr)) {
        return 0.7 + 0.3 * (shortStr.length / longStr.length);
    }

    let hit = 0;
    const pool = longStr.split("");
    for (const ch of shortStr) {
        const at = pool.indexOf(ch);
        if (at >= 0) {
            pool.splice(at, 1);
            hit++;
        }
    }
    return hit / longStr.length;
}

function toNumber(v) {
    const n = Number(String(v ?? "").replace(/[^\d.-]/g, ""));
    return Number.isFinite(n) ? n : 0;
}

/**
 * "100mL" / "300.000mL" / "1회 200g" 같은 문자열에서 양을 숫자로 뽑는다.
 * kg·L은 g·mL로 맞춰 돌려준다. 못 읽으면 null.
 */
function parseAmount(text) {
    const m = String(text || "").match(/([\d.]+)\s*(kg|g|mL|ml|L|l|cc)?/);
    if (!m) return null;

    let value = parseFloat(m[1]);
    if (!Number.isFinite(value) || value <= 0) return null;

    const unit = (m[2] || "").toLowerCase();
    if (unit === "kg" || unit === "l") value *= 1000;
    return value;
}

/**
 * 100g당 수치를 **한 번 먹는 양** 기준으로 환산할 배수.
 *
 * g과 mL을 섞어 비교한다(기준량은 100g인데 총 내용량은 mL로 적힌 행이 흔하다).
 * 밀도를 1로 보는 셈인데, 음료·국물은 거의 정확하고 그 외에도 100g/100mL을
 * 그대로 한 끼로 치는 것보다는 훨씬 낫다.
 *
 * 환산할 근거가 없으면 null → 이 행은 쓰지 않는다.
 * 잘못 환산한 값을 넣느니 모델 추정을 그대로 두는 게 맞다.
 */
function portionFactor(row) {
    const basis = parseAmount(pick(row, FIELD.basis));
    if (!basis) return null;

    const dish = parseAmount(row.DISH_ONE_SERVING);       // 음식 1인분량
    const pack = parseAmount(row.Z10500);                 // 포장 총 내용량
    const ref = parseAmount(row.NUTRI_AMOUNT_SERVING);    // 1회 섭취참고량

    let portion = null;
    let source = null;

    if (dish) {
        portion = dish;
        source = "DISH_ONE_SERVING";
    } else if (pack && ref && pack / ref >= PACK_SPLIT_RATIO) {
        // 총 내용량이 1회 참고량보다 몇 배씩 크면 **여러 번 나눠 먹는 포장**이다.
        // 오예스 360g(12개들이)을 한 번에 먹는 양으로 보면 1,800kcal이 나온다.
        portion = ref;
        source = "NUTRI_AMOUNT_SERVING";
    } else if (pack) {
        // 참고량이 아예 없으면 나눠 먹는 포장인지 가릴 수 없다. 크면 믿지 않는다.
        if (!ref && pack > SINGLE_PACK_MAX_G) return null;
        // 콜라 300mL처럼 참고량과 큰 차이가 없으면 포장 하나를 다 먹은 것으로 본다
        portion = pack;
        source = "Z10500";
    } else if (ref) {
        portion = ref;
        source = "NUTRI_AMOUNT_SERVING";
    }

    // 한 번에 먹는 양으로 볼 수 없는 값은 버린다.
    // 아래로는 1g짜리(방울토마토 한 알 단위로 적힌 행), 위로는 업소용 대용량이 걸린다.
    if (!portion || portion < MIN_PORTION_G || portion > MAX_PORTION_G) return null;

    // 먹는 양이 기준량과 **똑같으면** 환산한 게 아니다.
    // 그런 행은 실제 1인분·포장 정보가 없어서 기준량(대개 100g)이 그대로 적힌 것이다.
    // 제육볶음이 그랬다 — 100g치 151kcal이 1인분으로 들어가 AI 추정(550)을 절반 이하로 깎았다.
    // 100g들이 제품이 실제로 있긴 하지만 드물고, 틀리는 쪽의 손해가 훨씬 크다.
    if (Math.abs(portion - basis) < 0.5) return null;

    return { factor: portion / basis, portion, source };
}

/**
 * 요청 주소를 만든다.
 *
 * data.go.kr은 인증키를 **Encoding용과 Decoding용 두 가지**로 준다.
 * 이걸 헷갈리는 게 이 API에서 가장 흔한 실패다.
 * - Decoding 키(원문)를 주면 여기서 인코딩해야 한다
 * - Encoding 키(이미 %2B 같은 게 섞인 것)를 또 인코딩하면 키가 깨진다
 * 그래서 키에 '%'가 들어 있으면 이미 인코딩된 것으로 보고 그대로 붙인다.
 */
function buildUrl(query, apiKey) {
    const encodedKey = apiKey.includes("%") ? apiKey : encodeURIComponent(apiKey);
    const params = new URLSearchParams({
        pageNo: "1",
        numOfRows: String(CANDIDATE_COUNT),
        type: "json",
        [NAME_PARAM]: query
    });
    return `${HOST}/${ENDPOINT}?serviceKey=${encodedKey}&${params}`;
}

/**
 * 응답에서 결과 배열을 찾아낸다.
 *
 * data.go.kr은 데이터셋마다 감싸는 모양이 다르다.
 *   {body:{items:[...]}} / {response:{body:{items:{item:[...]}}}} / {items:[...]}
 * 어느 쪽이든 통하도록 **객체 배열을 만나면 그걸로 본다.**
 * 모양 하나에 맞춰 짜두면 데이터셋이 바뀔 때 조용히 빈 결과가 된다.
 */
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

/**
 * 식약처 API를 실제로 호출한다. 실패는 전부 null (분석을 막지 않는다).
 */
async function fetchCandidates(query, apiKey) {
    const res = await fetch(buildUrl(query, apiKey), {
        signal: AbortSignal.timeout(TIMEOUT_MS)
    });
    if (!res.ok) return null;

    // 키가 틀리거나 트래픽을 초과하면 JSON을 달라고 해도 XML 에러 문서가 온다
    const text = await res.text();
    let body;
    try {
        body = JSON.parse(text);
    } catch {
        console.warn("식약처 응답이 JSON이 아님:", text.slice(0, 200));
        return null;
    }

    return extractRows(body);
}

/**
 * 이 행이 검색어와 얼마나 맞는지 (0~1).
 *
 * 그냥 식품명끼리만 비교하면 **브랜드를 적을수록 손해**가 된다.
 * DB는 이름과 업체를 따로 들고 있어서("아메리카노" + "스타벅스커피코리아"),
 * 사용자가 "스타벅스 아메리카노"라고 적으면 이름만으로는 0.87밖에 안 나온다.
 * 사진에서 브랜드를 읽어 붙이게 해놓고 정작 그것 때문에 탈락시키면 앞뒤가 안 맞는다.
 *
 * 그래서 **앞부분이 업체명에 들어 있으면 브랜드 표기로 보고 떼어낸 뒤** 다시 잰다.
 * "스타벅스 아메리카노" → (스타벅스 ⊂ 스타벅스커피코리아) → "아메리카노" vs "아메리카노" = 1.0
 */
function scoreRow(row, normalizedQuery) {
    const name = normalize(pick(row, FIELD.name));
    let best = similarity(normalizedQuery, name);

    const maker = normalize(pick(row, FIELD.maker)).replace(/\s/g, "");
    if (!maker) return best;

    const words = normalizedQuery.split(" ").filter(Boolean);
    for (let i = 1; i < words.length; i++) {
        const head = words.slice(0, i).join("");
        const rest = words.slice(i).join(" ");
        if (!rest || head.length < 2) continue;
        if (maker.includes(head)) {
            best = Math.max(best, similarity(rest, name));
        }
    }
    return best;
}

/**
 * 이름이 정확히 없을 때, **그 이름으로 시작하는 제품**에서 대표를 고른다.
 *
 * DB에는 사람들이 부르는 이름이 없는 경우가 많다.
 * "자갈치"는 없고 `자갈치문어맛`(농심)으로 등록돼 있다. 정확일치만 고집하면
 * 멀쩡히 있는 데이터를 못 쓴다.
 *
 * 다만 아무거나 쓰면 안 된다. 두 가지를 건다.
 *
 * 1) **포장 총량이 적힌 행만** 본다. 같은 제품인데 어떤 행은 90g 한 봉지,
 *    어떤 행은 1회 참고량 30g만 적혀 있어 세 배씩 차이 난다.
 * 2) 후보끼리 열량이 [VARIANT_SPREAD]배 넘게 벌어지면 **포기**한다.
 *    "포카칩"은 45g·60g·110g·124g 봉지가 뒤섞여 있어 어느 것인지 알 수 없다.
 *    "자갈치"는 90g 하나뿐이라 고를 수 있다.
 */
function pickVariant(rows, normalizedQuery, categoryHint) {
    // 종류를 적었으면 그 대분류가 아닌 것은 먼저 걷어낸다.
    // "자갈치 과자"에서 어묵(수산가공식품류)이 떨어져 나가는 지점이다.
    if (categoryHint) {
        const inCategory = rows.filter((r) => {
            const cat = String(r.FOOD_CAT1_NM || "");
            return categoryHint.some((k) => cat.includes(k));
        });
        if (inCategory.length > 0) rows = inCategory;
    }

    // 앞에서부터 일치해야 같은 계열이다.
    // "딸기초코파이"처럼 중간에 낀 것은 다른 제품이다.
    const startsWith = (row, prefix) =>
        normalize(pick(row, FIELD.name)).startsWith(prefix);

    let matched = rows.filter((r) => startsWith(r, normalizedQuery));

    // 이름만으로는 너무 넓을 때가 있다. "자갈치"에는 농심 과자와 해도식품 어묵(1kg)이
    // 같이 걸린다. 사용자가 **브랜드를 함께 적었다면** 그걸로 좁힌다.
    // (사진에서 브랜드를 읽게 만든 것도 이 때문이다)
    const words = normalizedQuery.split(" ").filter(Boolean);
    for (let i = 1; i < words.length && matched.length === 0; i++) {
        const head = words.slice(0, i).join("");
        const rest = words.slice(i).join(" ");
        if (head.length < 2 || !rest) continue;

        const byBrand = rows.filter((r) => {
            const maker = normalize(pick(r, FIELD.maker)).replace(/\s/g, "");
            return maker.includes(head) && startsWith(r, rest);
        });
        if (byBrand.length > 0) matched = byBrand;
    }

    const candidates = matched
        // 포장 총량이 적힌 행만 본다. 같은 제품인데 어떤 행은 90g 한 봉지,
        // 어떤 행은 1회 참고량 30g만 적혀 있어 세 배씩 차이 난다.
        .filter((r) => parseAmount(r.Z10500))
        .map((r) => ({ row: r, portion: portionFactor(r) }))
        .filter((c) => c.portion);

    // **후보가 하나로 좁혀졌을 때만** 쓴다.
    //
    // 열량이 비슷하다고 같은 것이 아니다. "라떼"로 찾으면 검은콩 라떼·녹차 라떼가 줄줄이
    // 나오는데 열량대가 비슷해 [VARIANT_SPREAD] 검사를 통과해버린다.
    // 실제로 일반 카페라떼(180)가 **검은콩 라떼(224)로 바뀌는 일**이 있었다.
    //
    // "자갈치"처럼 고유한 이름은 좁히면 하나만 남고, "라떼" 같은 일반명사는 여러 개가 남는다.
    // 브랜드나 종류를 함께 적으면(`농심 자갈치`, `자갈치 과자`) 위에서 이미 좁혀진다.
    if (candidates.length !== 1) return null;

    const only = candidates[0];
    const kcal = toNumber(pick(only.row, FIELD.kcal)) * only.portion.factor;
    if (!(kcal > 0)) return null;

    return only.row;
}

/**
 * 후보 중 가장 그럴듯한 것을 고른다. 기준을 못 넘으면 null.
 */
function pickBest(rows, normalizedQuery, categoryHint) {
    let best = null;
    let bestScore = 0;

    for (const row of rows) {
        const score = scoreRow(row, normalizedQuery);
        if (score > bestScore) {
            bestScore = score;
            best = row;
        }
    }

    // 정확한 이름이 없으면 같은 계열에서 대표를 찾아본다
    if (!best || bestScore < MIN_SCORE) {
        best = pickVariant(rows, normalizedQuery, categoryHint);
    }
    if (!best) return null;

    // 성분값은 100g/100mL당이다. 한 번 먹는 양으로 환산하지 못하면 쓸 수 없다.
    const portion = portionFactor(best);
    if (!portion) return null;

    const scale = (key) => toNumber(pick(best, key)) * portion.factor;
    const food = {
        /** 정확일치가 아니라 같은 계열에서 고른 것인지 */
        variant: bestScore < MIN_SCORE,
        name: String(pick(best, FIELD.name)).trim(),
        maker: String(pick(best, FIELD.maker)).trim(),
        /** 이 값이 몇 g/mL 기준인지. 로그에서 숫자를 검증할 때 필요하다 */
        portion: Math.round(portion.portion),
        portionSource: portion.source,
        kcal: Math.round(scale(FIELD.kcal)),
        carb: Number(scale(FIELD.carb).toFixed(1)),
        protein: Number(scale(FIELD.protein).toFixed(1)),
        fat: Number(scale(FIELD.fat).toFixed(1)),
        score: Number(bestScore.toFixed(2))
    };

    // 열량이 0인 행은 성분이 아직 안 채워진 것이다. 그대로 쓰면 0kcal 식사가 된다.
    // 값이 상식 밖이면 필드 자리를 잘못 읽은 것이다. 어느 쪽이든 손대지 않는 게 맞다.
    if (!isPlausible(food)) return null;

    return food;
}

/**
 * 캐시 문서 이름으로 쓸 수 있게 만든다 (슬래시 금지).
 *
 * 오퍼레이션 이름을 앞에 붙인다. 다른 데이터셋으로 갈아탔는데 예전 것으로 찾은 결과가
 * 그대로 나오면, 왜 안 바뀌는지 한참 헤매게 된다.
 */
/** 지금 규칙·출처로 만든 문서 이름의 공통 앞부분 */
const CACHE_PREFIX = `${ENDPOINT.split("/").pop().replace(/[^\w]/g, "")}_${RULES_VERSION}_`;

function cacheKey(rawName) {
    const normalized = normalize(rawName);
    const safe = normalized.replace(/[^\p{L}\p{N}]/gu, "_").slice(0, 150);
    return `${CACHE_PREFIX}${safe}`;
}

/**
 * 이름 하나를 조회한다. 못 찾거나 애매하면 null.
 *
 * 같은 메뉴를 매일 먹는 사용자가 많아서 캐시가 잘 듣는다.
 * 못 찾은 것도 캐시한다 — 없는 걸 매번 다시 물어보는 게 제일 아깝다.
 */
async function lookupFood(name, apiKey) {
    if (!apiKey) return null;

    const normalized = normalize(name);
    // 한 글자짜리는 무엇과도 비슷해 보인다. 검색 자체를 하지 않는다.
    if (normalized.length < 2) return null;

    // 낱말이 많은 서술형 이름은 **찾을 가망이 없다.**
    // "베러핏 고단백 저당 쉐이크 옛날커피맛" 같은 건 식약처 DB의 식품명과 형태가 다르다.
    // 그런데 이런 이름일수록 첫 검색이 0건이라 재조회까지 타서 7초 넘게 쓰고 실패한다.
    // 실제로 보정에 성공한 건 전부 짧은 이름이었다 (코카콜라·새우깡·김치찌개·신라면).
    if (normalized.split(" ").filter(Boolean).length > MAX_QUERY_WORDS) return null;

    const db = getFirestore();
    const ref = db.collection("foodCache").doc(cacheKey(name));

    try {
        const cached = await ref.get();
        if (cached.exists) {
            const data = cached.data();
            return data.miss ? null : data.food;
        }
    } catch (e) {
        // 캐시를 못 읽는 건 조회를 막을 이유가 못 된다. 그냥 API로 간다.
        console.warn("foodCache read failed:", e.message);
    }

    // "자갈치 과자"의 "과자"는 검색어로 쓰면 0건이 난다. 떼어내고 거르는 데만 쓴다.
    const { hint, core } = splitCategoryHint(normalized);

    let food = null;
    try {
        let rows = await fetchCandidates(core, apiKey);
        if (rows) food = pickBest(rows, core, hint);
        const firstWasEmpty = !rows || rows.length === 0;

        // DB의 식품명에는 브랜드가 없다. 이름은 "아메리카노"고 업체는 따로 들고 있다.
        // 그래서 "스타벅스 아메리카노"로 검색하면 **0건**이 나온다.
        // 사진에서 브랜드를 읽어 붙이게 해놓고 그것 때문에 못 찾으면 앞뒤가 안 맞으므로,
        // 첫 낱말을 떼고 한 번만 더 찾는다. (맞았을 때는 재조회하지 않아 지연이 늘지 않는다)
        // 재조회는 **첫 검색이 0건일 때만** 한다.
        // 결과가 왔는데 기준을 못 넘은 거라면 이름을 줄여 다시 찾아도 대개 마찬가지고,
        // 그 한 번이 3.8초라 예산을 통째로 먹는다.
        const words = core.split(" ").filter(Boolean);
        if (!food && firstWasEmpty && words.length > 1) {
            rows = await fetchCandidates(words.slice(1).join(" "), apiKey);
            // 점수는 원래 검색어로 잰다. 업체명까지 봐야 브랜드가 맞는지 확인된다.
            if (rows) food = pickBest(rows, core, hint);
        }
    } catch (e) {
        // 타임아웃·네트워크 오류.
        //
        // 예전에는 아무것도 안 남기고 나갔는데, 그러면 **매번 다시 시도하며 매번 시간만 쓴다.**
        // 실제로 겪었다 — 조회가 예산에 잘려 캐시에 못 들어가니, 같은 메뉴를 정산할 때마다
        // 3초씩 내고 계속 실패했다. 끝을 못 보는 조회일수록 더 자주 반복되는 셈이었다.
        //
        // 그래서 **짧은 기한**으로 막아둔다. [FAILURE_RETRY_HOURS]가 지나면 다시 시도한다.
        // "없다"가 아니라 "지금은 안 된다"라서 90일씩 묶어두면 안 된다.
        console.warn(`식약처 조회 실패 [${core}]:`, e.message);
        try {
            await ref.set({
                miss: true,
                transient: true,   // 진짜 없는 게 아니라 조회를 못 끝냈다는 표시
                updatedAt: FieldValue.serverTimestamp(),
                expireAt: new Date(Date.now() + FAILURE_RETRY_HOURS * 60 * 60 * 1000)
            });
        } catch (_) { /* 캐시에 못 써도 분석은 계속된다 */ }
        return null;
    }

    try {
        // 밀리초 숫자가 아니라 Timestamp로 넣는다. 콘솔에서 날짜로 보여야
        // 언제 캐시된 값인지 눈으로 확인할 수 있다 (users의 createdAt과 같은 이유).
        // 서버 시각이라 인스턴스 시계가 어긋나도 값이 뒤섞이지 않는다.
        const updatedAt = FieldValue.serverTimestamp();

        // TTL 정책이 보는 필드. serverTimestamp는 값이 아직 정해지지 않은 자리표라
        // 여기에 더하기를 할 수 없어, 만료 시각만은 로컬 시계로 계산한다.
        // 캐시 수명이 몇 초 어긋나는 건 아무 문제가 없다.
        const expireAt = new Date(Date.now() + CACHE_TTL_DAYS * 24 * 60 * 60 * 1000);

        await ref.set(
            food ? { food, updatedAt, expireAt } : { miss: true, updatedAt, expireAt }
        );
    } catch (e) {
        console.warn("foodCache write failed:", e.message);
    }

    return food;
}

/**
 * 여러 이름을 한 번에 조회한다.
 *
 * 순차로 돌리면 항목 수만큼 지연이 쌓인다. 한 끼에 서너 개씩 네 끼면 금방 몇 초다.
 * 개별 실패는 null로 떨어질 뿐 전체를 깨지 않는다.
 */
async function lookupMany(names, apiKey) {
    if (!apiKey || !names.length) return new Map();

    const unique = [...new Set(names.filter(Boolean))];
    const found = new Map();

    // 끝난 것부터 담는다. 전체 예산이 끝나면 그때까지 담긴 것만 쓴다.
    // Promise.all로 묶으면 느린 하나 때문에 이미 받아둔 결과까지 통째로 버리게 된다.
    const jobs = unique.map((n) =>
        lookupFood(n, apiKey)
            .then((food) => { if (food) found.set(n, food); })
            .catch(() => {})
    );

    await Promise.race([
        Promise.all(jobs),
        new Promise((resolve) => setTimeout(resolve, TOTAL_BUDGET_MS))
    ]);

    return found;
}

/**
 * 모델 추정치와 DB 값이 이 배수를 넘게 벌어지면 **고치지 않는다**.
 *
 * ⚠️ 이 값을 좁히면 정작 고쳐야 할 것을 놓친다. 실제로 겪은 일이다.
 * 새우깡 한 봉지(90g)는 465kcal인데 모델은 150kcal쯤으로 기억하고 있다. 3배 차이다.
 * 2.0으로 두면 이 어긋남이 **가드에 걸려 그대로 통과**했고, "새우깡 3개 = 450kcal"이 나왔다.
 * 고쳐야 할 오차가 클수록 안 고쳐지는 셈이라 앞뒤가 맞지 않는다.
 *
 * 원래 이 가드는 **개수 문제**를 막으려고 뒀는데, 이제 [quantityOf]가 개수를 직접 읽으므로
 * 그 역할은 끝났다. 지금 남은 역할은 "명백히 다른 것이 붙었을 때"만 막는 것이라 넉넉히 잡는다.
 *
 * 4로 뒀더니 **새우깡 3봉지(AI 155 vs DB 1395, 9배)가 걸려 그대로 통과**했다.
 * AI가 크게 틀린 경우일수록 가드에 막혀 안 고쳐지는, 앞뒤가 안 맞는 동작이었다.
 * 이름 매칭은 이미 [MIN_SCORE] 0.95로 엄격하고 환산 근거도 위에서 걸러내므로,
 * 여기서까지 좁게 잡을 이유가 없다.
 */
const MAX_RATIO = 10.0;

/**
 * 분석 결과의 칼로리를 DB 값으로 바로잡는다. (순수 함수 — 네트워크를 타지 않는다)
 *
 * Gemini 응답을 **제자리에서 고친다.** 이 함수 아래로는 report 문구도 netCalories도
 * 전부 data에서 파생되므로, 여기만 고치면 나머지는 저절로 따라온다.
 *
 * 탄단지는 항목별로 나오지 않고 끼니 단위로만 나온다. 그래서 칼로리가 바뀐 비율만큼
 * 그 끼니의 탄단지를 같이 조절한다. 항목별 정확도는 얻지 못하지만,
 * **칼로리와 탄단지가 서로 어긋나는 것**은 막는다.
 *
 * @returns 바로잡은 항목 목록 (로그·검증용)
 */
function correctWithFoodDb(data, foundMap) {
    const applied = [];
    if (!foundMap || foundMap.size === 0) return applied;

    for (const meal of ["breakfast", "lunch", "dinner", "snack"]) {
        const items = data?.meals?.[meal];
        if (!Array.isArray(items) || items.length === 0) continue;

        const before = items.reduce((sum, m) => sum + (Number(m.kcal) || 0), 0);
        let changed = false;

        for (const item of items) {
            const food = foundMap.get(item.name);
            if (!food) continue;

            // DB 값은 한 개(1인분) 기준이다. 사용자가 적은 개수만큼 곱해야 맞다.
            // 이걸 빼먹으면 "콜라 2캔"에 1캔치를 넣게 된다.
            const qty = quantityOf(item.name);

            // 용량이 적혀 있으면(500ml, 90g) 그 크기에 맞춰 환산한다.
            // DB가 고른 행이 300mL짜리인데 500mL를 마셨다면 그대로 쓰면 안 된다.
            // 적힌 크기는 추정이 아니라 읽은 사실이라 DB의 포장 크기보다 우선한다.
            const stated = statedAmountOf(item.name);
            const sizeFactor = stated && food.portion > 0 ? stated / food.portion : 1;

            // 마리·판·통은 DB가 아는 양과 크기가 아예 다르다. 곱할 단위가 틀렸으므로 손대지 않는다.
            //
            // 다만 g·mL이 적혀 있으면 얘기가 다르다. 그건 읽은 사실이고 위 sizeFactor가
            // 그 크기로 환산해 주므로, "닭강정 1마리 800g"은 정상적으로 보정된다.
            const bulk = bulkUnitOf(item.name);
            if (bulk && !stated) {
                // 조용히 넘어가면 안 된다. 왜 안 고쳐졌는지는 이 줄이 유일한 단서다.
                console.warn(
                    `[fooddb] 보정 건너뜀 - "${item.name}": `
                    // "1마리과"처럼 조사가 틀어지지 않게 단위 뒤에 "기준"을 붙여 받침을 고정한다
                    + `DB "${food.name}"는 ${food.portion || "?"}g 기준이라 1${bulk} 기준과 다르다`
                );
                continue;
            }

            const dbKcal = Math.round(food.kcal * sizeFactor * qty);

            // 가드는 **한 개끼리** 비교한다.
            // 총량으로 비교하면 개수가 늘어날수록 차이가 벌어져, 많이 먹었다고 적을수록
            // 보정이 안 되는 이상한 동작이 된다. (새우깡 3개에서 실제로 그랬다)
            const estimated = Number(item.kcal) || 0;
            if (estimated > 0) {
                const ratio = food.kcal / (estimated / qty);
                if (ratio > MAX_RATIO || ratio < 1 / MAX_RATIO) continue;
            }

            item.kcal = dbKcal;
            // 어디서 온 값인지 남긴다. 나중에 "이 숫자 왜 이래?"를 추적할 유일한 단서다.
            item.source = "mfds";
            applied.push({
                name: item.name,
                matched: food.name,
                qty,
                from: estimated,
                to: dbKcal
            });
            changed = true;
        }

        if (!changed) continue;

        const after = items.reduce((sum, m) => sum + (Number(m.kcal) || 0), 0);
        data.calories = data.calories || {};
        data.calories[meal] = after;

        // 탄단지도 같은 비율로. before가 0이면 비율을 낼 수 없어 그대로 둔다.
        const macros = data?.macros?.[meal];
        if (macros && before > 0) {
            const scale = after / before;
            for (const key of ["carb", "protein", "fat"]) {
                const v = Number(macros[key]) || 0;
                macros[key] = Number((v * scale).toFixed(1));
            }
        }
    }

    return applied;
}

/**
 * 같은 메뉴가 여러 줄로 쪼개져 나온 것을 한 줄로 합친다.
 *
 * 모델은 "코카콜라 2개"를 종종 `코카콜라` 두 항목으로 나눠 돌려준다.
 * 합계는 맞지만 리포트에 같은 이름이 두 줄로 뜬다.
 *
 * 프롬프트로 "하나로 묶어라"라고 부탁할 수도 있지만, 모델이 지킬 때도 안 지킬 때도 있다.
 * 결과를 손보는 쪽이 확실하다.
 *
 * 합칠지 말지는 [normalize]로 판단한다. `코카콜라`와 `코카콜라 1캔`은 같은 것이고
 * `코카콜라`와 `코카콜라 제로`는 다른 것이다.
 *
 * @returns 합쳐진 끼니 이름들 (로그용)
 */
function mergeDuplicateItems(data) {
    const touched = [];

    for (const meal of ["breakfast", "lunch", "dinner", "snack"]) {
        const items = data?.meals?.[meal];
        if (!Array.isArray(items) || items.length < 2) continue;

        const firstOf = new Map();
        const kept = [];

        for (const item of items) {
            const key = normalize(item?.name);
            const prev = key && firstOf.get(key);

            if (!prev) {
                if (key) firstOf.set(key, item);
                kept.push(item);
                continue;
            }
            // 두 번째부터는 앞 항목에 더하고 버린다
            prev.kcal = (Number(prev.kcal) || 0) + (Number(item.kcal) || 0);
            prev.mergedCount = (prev.mergedCount || 1) + 1;
        }

        if (kept.length === items.length) continue;

        for (const item of kept) {
            if (!item.mergedCount) continue;
            // "×2"는 번역이 필요 없어 언어 설정과 무관하게 그대로 쓸 수 있다
            item.name = `${item.name} ×${item.mergedCount}`;
            delete item.mergedCount;
        }
        data.meals[meal] = kept;
        touched.push(meal);
    }

    return touched;
}

/** 분석 결과에서 조회할 메뉴 이름을 모두 뽑는다 */
function collectMealNames(data) {
    const names = [];
    for (const meal of ["breakfast", "lunch", "dinner", "snack"]) {
        const items = data?.meals?.[meal];
        if (!Array.isArray(items)) continue;
        for (const item of items) {
            if (item?.name) names.push(String(item.name));
        }
    }
    return names;
}

module.exports = {
    lookupFood,
    cacheKey,
    CACHE_PREFIX,
    CACHE_TTL_DAYS,
    lookupMany,
    correctWithFoodDb,
    collectMealNames,
    mergeDuplicateItems,
    quantityOf,
    bulkUnitOf,
    statedAmountOf,
    splitCategoryHint,
    normalize,
    similarity,
    pickBest,
    scoreRow,
    MIN_SCORE,
    MAX_RATIO
};
