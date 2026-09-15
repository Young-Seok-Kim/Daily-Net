/**
 * 하루 섭취 합계(끼니별 칼로리·탄단지)를 **서버가 항목에서 다시 센다.**
 *
 * 왜 가져왔는가 (2026-09-15):
 *   같은 입력을 3초 간격으로 두 번 분석했는데
 *   1) 첫 번째는 모델이 macros.carb를 숫자가 아닌 값으로 내려줘
 *      `"45" + 0` 문자열 접합 → `totalCarb.toFixed is not a function`으로 500이 나갔고
 *   2) 두 번째는 항목 kcal은 멀쩡한데(점심 215, 저녁 850) 섭취 합계가 0으로 나갔다.
 *      합계를 항목이 아니라 모델이 따로 적는 calories 객체에서만 셌기 때문이다.
 *      그 칸이 스키마를 벗어나면(단위가 붙은 문자열, 총합 숫자 하나 등) 전부 0이 된다.
 *
 * 리포트 본문의 끼니별 "(합계: …)"는 원래부터 항목 합이었다. 결산만 모델 값을 믿어서
 * 둘이 따로 놀았다. 이제 결산도 항목 합을 쓴다 — 어차피 합이어야 하는 값이다.
 * 모델의 calories는 **항목이 하나도 없는 끼니**에서만 쓴다.
 */

const MEALS = ["breakfast", "lunch", "dinner", "snack"];
const MACROS = ["carb", "protein", "fat"];

/** 무엇이 오든 숫자로. 문자열 "45"는 45, "45g"·객체·누락은 0 */
const num = (v) => Number(v) || 0;

/**
 * 끼니별 섭취 칼로리.
 *
 * - 항목이 있는 끼니: 항목 kcal의 합 (반올림)
 * - 항목이 없는 끼니: 모델의 calories[meal]을 숫자로 강제한 값
 *
 * @returns {{ perMeal: {breakfast, lunch, dinner, snack}, total: number, mismatches: string[] }}
 *   mismatches — 모델이 적은 끼니 합계와 항목 합이 어긋난 끼니. 로그용이다.
 *   (모델 값이 아예 숫자가 아니면 그것도 어긋난 것으로 본다)
 */
function mealCalories(data) {
    const claimed = data?.calories;
    const claimedIsObject = claimed && typeof claimed === "object" && !Array.isArray(claimed);

    const perMeal = {};
    const mismatches = [];
    let total = 0;

    for (const meal of MEALS) {
        const items = data?.meals?.[meal];
        const hasItems = Array.isArray(items) && items.length > 0;
        const claimedKcal = claimedIsObject ? num(claimed[meal]) : 0;

        let kcal;
        if (hasItems) {
            kcal = Math.round(items.reduce((acc, m) => acc + num(m?.kcal), 0));
            // 모델이 적은 값과 1kcal 넘게 다르면 남긴다. 어느 쪽을 믿었는지 로그에서 보이게
            if (Math.abs(kcal - claimedKcal) > 1) {
                const raw = claimedIsObject ? JSON.stringify(claimed[meal]) : JSON.stringify(claimed);
                mismatches.push(`${meal}: 항목합 ${kcal} ≠ 모델 ${raw}`);
            }
        } else {
            kcal = claimedKcal;
        }

        perMeal[meal] = kcal;
        total += kcal;
    }

    return { perMeal, total, mismatches };
}

/**
 * 끼니별 탄단지를 숫자로 정리하고 하루 합계를 낸다.
 *
 * @returns {{ perMeal: {breakfast: {carb, protein, fat}, ...}, total: {carb, protein, fat} }}
 *   값은 모두 소수 첫째 자리까지의 숫자다. 문자열이 와도 더해지고, 없으면 0이다.
 */
function mealMacros(data) {
    const perMeal = {};
    const total = { carb: 0, protein: 0, fat: 0 };

    for (const meal of MEALS) {
        const src = data?.macros?.[meal];
        const m = {};
        for (const key of MACROS) {
            const v = src && typeof src === "object" ? num(src[key]) : 0;
            m[key] = Number(v.toFixed(1));
            total[key] += v;
        }
        perMeal[meal] = m;
    }

    for (const key of MACROS) total[key] = Number(total[key].toFixed(1));
    return { perMeal, total };
}

module.exports = { mealCalories, mealMacros };
