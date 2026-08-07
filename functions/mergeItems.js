/**
 * 쪼개져 나온 같은 메뉴를 한 줄로 합친다.
 */

/** 수량을 나타내는 단위. 이름에서 떼어내 같은 메뉴인지 볼 때 쓴다 */
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
 * 이름에서 수량·단위를 걷어낸다.
 *
 * 두 항목이 같은 메뉴인지 판단하는 기준이다. `코카콜라`와 `코카콜라 1캔`은 같고
 * `코카콜라`와 `코카콜라 제로`는 다르다 — 뒤엣것은 열량이 아예 다른 제품이라
 * 합치면 안 된다.
 *
 * **낱말 단위로만 뗀다.** 부분 문자열로 지우면 "한우"의 "한", "큰컵라면"의 "큰"이 깎인다.
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

/**
 * 같은 메뉴가 여러 줄로 쪼개져 나온 것을 한 줄로 합친다.
 *
 * 모델은 "코카콜라 2개"를 종종 `코카콜라` 두 항목으로 나눠 돌려준다.
 * 합계는 맞지만 리포트에 같은 이름이 두 줄로 뜬다.
 *
 * 프롬프트로 "하나로 묶어라"라고 부탁할 수도 있지만, 모델이 지킬 때도 안 지킬 때도 있다.
 * 결과를 손보는 쪽이 확실하다.
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

module.exports = { normalize, mergeDuplicateItems };
