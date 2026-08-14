/**
 * 항목 열량의 곱셈·나눗셈을 **서버가 계산한다.** 모델은 판단만 하고 산수는 여기서 한다.
 *
 * 왜 가져왔는가:
 *   "4명이서 치킨 2마리"가 프롬프트를 세 번 고치는 동안에도 계속 틀렸다.
 *   1마리 800g 1500(곱하기도 나누기도 안 함) → 0.5마리 ×2 2100(나눈 몫을 두 줄로
 *   내보내 서버 병합이 도로 합침) → 0.5마리 500g 2100(이름은 몫인데 kcal은 다른 값).
 *
 *   나눗셈을 서버로 옮기자 이번에는 곱셈이 남았다 — 1마리는 2100으로 잡으면서
 *   2마리 전체를 2400으로 냈다. **개수가 몇이든 한 단위 값 근처의 총합**을 내는 것이다.
 *
 *   운동(exerciseCalc.js)·성분표(extractItems.js)와 같은 결론이다 —
 *   thinkingLevel minimal에서 다단계 산수를 시키면 모델은 식을 풀지 않고 아무 값이나 낸다.
 *
 * 그래서 역할을 가른다. 모델이 항목에 담는 것은 판단뿐이다:
 *   - unitKcal / units: 한 단위(1마리·1개…)의 열량과 사용자가 적은 개수
 *   - sharedBy: 나눠 먹은 사람 수 (혼자면 담지 않음)
 *   - myShare: 똑같이 나누지 않았을 때 사용자 몫의 비율 (예: 0.6. 균등이면 담지 않음)
 * 전체(unitKcal × units) → 몫(÷ sharedBy) 순서로 여기서 계산하고,
 * 끼니 합계·탄단지도 그에 맞춰 보정한다.
 */

const MEALS = ["breakfast", "lunch", "dinner", "snack"];

/**
 * 항목 kcal을 서버 계산값으로 바꾼다. data를 직접 고친다.
 *
 * - 개수 항목: kcal = unitKcal × units. 모델이 kcal 칸에 곱해 담은 값보다 이쪽을 믿는다
 * - 나눠 먹은 항목: 위의 전체를 몫으로 나누고 이름에 "(1/4)"나 "(60%)"를 붙인다
 *   — 번역이 필요 없어 언어 설정과 무관하다 (mergeItems의 ×2와 같은 이유)
 * - 끼니 calories: 값이 바뀐 끼니는 항목 합으로 다시 세운다. 어차피 합이어야 하는 값이다
 * - 끼니 macros: 끼니 열량이 바뀐 비율만큼 함께 움직인다.
 *   항목별 탄단지가 없어 그 이상 정밀하게는 못 나눈다
 *
 * 판단 필드(units·unitKcal·sharedBy·myShare)는 쓰고 나서 지운다.
 * 앱은 {name, kcal}만 알면 된다.
 *
 * @returns 계산한 항목들의 로그 문자열 배열 (없으면 빈 배열)
 */
function settleItemKcal(data) {
    const logs = [];

    for (const meal of MEALS) {
        const items = data?.meals?.[meal];
        if (!Array.isArray(items) || items.length === 0) continue;

        let before = 0;
        let after = 0;

        for (const item of items) {
            const claimed = Number(item?.kcal) || 0;
            const units = Number(item?.units) || 0;
            const unitKcal = Number(item?.unitKcal) || 0;
            const sharedBy = Math.floor(Number(item?.sharedBy) || 0);

            // 비율은 (0, 1) 밖이면 버린다. 1 이상은 "나눠 먹지 않았다"는 뜻이 될 수 없다
            let ratio = Number(item?.myShare) || 0;
            if (!(ratio > 0 && ratio < 1)) ratio = 0;

            const name = String(item?.name || "?");
            delete item.units;
            delete item.unitKcal;
            delete item.sharedBy;
            delete item.myShare;

            before += claimed;
            const steps = [];

            // 1) 개수 곱셈: 전체 = 한 단위 × 개수
            let total = claimed;
            if (units > 0 && unitKcal > 0) {
                total = Math.round(units * unitKcal);
                if (total !== claimed) steps.push(`${unitKcal} ×${units} = ${total}`);
            }

            // 2) 몫 나눗셈: 몫 = 전체 ÷ 사람 수 (비율이 있으면 그 비율)
            let final = total;
            if (total > 0 && (sharedBy >= 2 || ratio > 0)) {
                final = ratio > 0 ? Math.round(total * ratio) : Math.round(total / sharedBy);
                const tag = ratio > 0 ? `${Math.round(ratio * 100)}%` : `1/${sharedBy}`;
                steps.push(`${tag} = ${final}`);
                item.name = `${item.name || ""} (${tag})`.trim();
            }

            if (steps.length > 0) logs.push(`${name}: ${claimed} → ${steps.join(" → ")}`);
            item.kcal = final;
            after += final;
        }

        if (after === before) continue;

        if (data.calories && typeof data.calories === "object") {
            data.calories[meal] = after;
        }
        const macros = data.macros?.[meal];
        if (macros && before > 0) {
            const factor = after / before;
            for (const key of ["carb", "protein", "fat"]) {
                macros[key] = Number(((Number(macros[key]) || 0) * factor).toFixed(1));
            }
        }
    }

    return logs;
}

module.exports = { settleItemKcal };
