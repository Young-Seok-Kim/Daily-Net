/**
 * 나온 칼로리가 상식 밖인 항목을 찾아낸다. **로그로만 남기고 숫자는 손대지 않는다.**
 *
 * 고치지 않는 것이 핵심이다. 잘못 짚어도 로그 한 줄이 늘 뿐이고, 값을 고치면
 * 예전 식약처 DB 보정처럼 멀쩡한 계산까지 망친다. 여기는 "봐야 할 것"만 알린다.
 *
 * 이름에 중량이 적혀 있을 때만 잰다. `아메리카노 1잔`처럼 양이 없으면 100g당 값을
 * 낼 수 없으므로 그냥 넘어간다.
 */

/** 이름에 적힌 중량. `42g`, `1.5kg`, `200ml`, `0.5L` */
const WEIGHT = /(\d+(?:\.\d+)?)\s*(kg|g|ml|l)(?![a-z])/i;

/** mergeDuplicateItems가 붙인 `×2`. 중량도 그만큼 곱해야 100g당 값이 맞는다 */
const MERGED = /×\s*(\d+)/;

/** 사람이 먹는 것 중 가장 열량이 높은 순수 지방이 900kcal/100g이다 */
const MAX_PER_100G = 900;

/**
 * 100g당 열량이 좁게 몰려 있어 어긋나면 바로 티가 나는 것들.
 *
 * 넓게 잡지 않는다. 밥·국·과일처럼 폭이 큰 것을 넣으면 멀쩡한 값이 계속 걸려
 * 로그가 시끄러워지고, 시끄러운 로그는 아무도 안 본다.
 */
const BANDS = [
    {
        label: "과자류",
        keys: ["과자", "스낵", "시리얼", "씨리얼", "쿠키", "비스킷", "크래커",
               "감자칩", "포테이토칩", "콘칩", "나초", "프레첼", "초콜릿"],
        // 아래를 380(콘플레이크)보다 더 올리면 멀쩡한 시리얼이 걸린다
        min: 350,
        max: 650
    },
    {
        label: "견과류",
        keys: ["아몬드", "땅콩", "호두", "캐슈", "견과"],
        min: 450,
        max: 750
    },
    {
        label: "유지류",
        keys: ["식용유", "올리브유", "참기름", "버터", "마요네즈"],
        min: 650,
        max: 950
    }
];

/** 이름에서 중량을 g으로 뽑는다. 못 뽑으면 0 */
function gramsOf(rawName) {
    const name = String(rawName || "");
    const m = name.match(WEIGHT);
    if (!m) return 0;

    const value = Number(m[1]);
    if (!Number.isFinite(value) || value <= 0) return 0;

    const unit = m[2].toLowerCase();
    const grams = (unit === "kg" || unit === "l") ? value * 1000 : value;

    const merged = name.match(MERGED);
    return merged ? grams * Number(merged[1]) : grams;
}

/**
 * 사용자가 적어 보낸 글에서 명시된 열량을 모두 뽑는다.
 *
 * 사진의 영양성분표에서 읽은 값은 `"씨리얼 초코 80g 400kcal"` 형태로 입력에 실려 온다.
 * 인쇄된 값은 아래 범주 밴드보다 정확하므로 밴드로 트집 잡을 이유가 없다.
 */
function statedKcals(texts) {
    const found = new Set();
    for (const text of texts) {
        const matches = String(text || "").matchAll(/(\d+(?:\.\d+)?)\s*kcal/gi);
        for (const m of matches) found.add(Math.round(Number(m[1])));
    }
    return found;
}

/**
 * 상식 밖인 항목 목록. 없으면 빈 배열.
 *
 * @param stated 입력에 열량이 명시됐던 값들 (statedKcals). 범주 밴드를 건너뛴다
 * @returns [{ meal, name, kcal, grams, per100, reason }]
 */
function implausibleItems(data, stated = new Set()) {
    const found = [];

    for (const meal of ["breakfast", "lunch", "dinner", "snack"]) {
        const items = data?.meals?.[meal];
        if (!Array.isArray(items)) continue;

        for (const item of items) {
            const grams = gramsOf(item?.name);
            const kcal = Number(item?.kcal) || 0;
            if (grams <= 0 || kcal <= 0) continue;

            const per100 = Math.round((kcal / grams) * 100);
            const hit = { meal, name: String(item.name), kcal, grams, per100 };

            if (per100 > MAX_PER_100G) {
                // 순수 지방보다 높다. 음식이 무엇이든 나올 수 없는 값이다
                found.push({ ...hit, reason: `순수 지방(${MAX_PER_100G})보다 높다` });
                continue;
            }

            // 성분표에서 읽은 값은 밴드로 재지 않는다. 인쇄된 값이 내 어림 기준보다 정확하다.
            // (위의 900 초과 검사는 그대로 건다 — 그건 잘못 읽었다는 뜻이지 제품 특성이 아니다)
            if (stated.has(kcal)) continue;

            const name = hit.name.toLowerCase();
            const band = BANDS.find((b) => b.keys.some((k) => name.includes(k)));
            if (band && (per100 < band.min || per100 > band.max)) {
                found.push({ ...hit, reason: `${band.label}는 보통 ${band.min}~${band.max}` });
            }
        }
    }

    return found;
}

module.exports = { implausibleItems, gramsOf, statedKcals };
