/**
 * 운동 소모 칼로리를 **서버가 계산한다.** 모델은 판단만 하고 곱셈은 여기서 한다.
 *
 * 왜 가져왔는가:
 *   프롬프트에 `kcal = METs × 3.5 × 체중 ÷ 200 × 분`을 적고 체중을 숫자로 세 군데나
 *   박아 넣었는데도, 89.5kg 사용자의 1시간 격투기가 계속 554kcal로 나왔다.
 *   역산하면 89.5kg 기준 5.9 METs지만 **70kg 기준으로는 7.5 METs**다.
 *   즉 METs는 맞게 골랐고 체중만 안 쓴 것이다.
 *
 *   반면 걸음은 정확했다(6199보에 221kcal, 계산값 222). 차이는 하나뿐이다.
 *   걸음은 프롬프트에 **계산이 끝난 상수**(걸음당 0.0358kcal)를 줬고,
 *   운동은 **계산하라는 식**을 줬다. thinkingLevel이 minimal이라 사고 토큰이 0인데
 *   4단계 곱셈을 시킨 셈이니, 모델은 식을 풀지 않고 외운 값을 냈다.
 *
 * 그래서 역할을 가른다. **판단은 모델(어떤 운동을 몇 분, 몇 METs로 볼 것인가),
 * 산수는 코드.** 식단에서 "곱셈은 코드가 한다"로 푼 것과 같은 수순이다.
 *
 * 검증(METs 역산으로 상식 밖인 값 찾기)은 exerciseCheck.js가 맡는다.
 */

const { stepsOf } = require("./exerciseCheck");

/**
 * 걸음 하나에 체중 1kg당 소모되는 칼로리.
 *
 * 기초대사를 뺀 순감과 총소모의 중간값이다. 배출 총합이 `BMR + 운동`이라
 * 총소모를 그대로 쓰면 걷는 동안의 기초대사가 두 번 세어진다.
 */
const KCAL_PER_STEP_PER_KG = 0.0004;

/** `kcal = METs × 3.5 × 체중 ÷ 200 × 분` */
function metsToKcal(mets, weightKg, minutes) {
    const kcal = (Number(mets) || 0) * 3.5 * (Number(weightKg) || 0) / 200 * (Number(minutes) || 0);
    return kcal > 0 ? Math.round(kcal) : 0;
}

/** 걸음 수만큼의 소모 칼로리 */
function stepKcal(weightKg, steps) {
    const kcal = KCAL_PER_STEP_PER_KG * (Number(weightKg) || 0) * (Number(steps) || 0);
    return kcal > 0 ? Math.round(kcal) : 0;
}

/**
 * 모델 응답에서 운동 목록과 총 소모 칼로리를 만든다.
 *
 * 걸음 항목은 **모델에게 맡기지 않고 여기서 붙인다.** 모델이 하는 판단은
 * "그 걸음 중 몇 보가 이미 운동에 들어가 있나"(stepsInExercise) 하나뿐이다.
 *
 * @param data 모델 응답. exercises[].{name, minutes, mets}와 stepsInExercise를 본다
 * @param weightKg 사용자 체중
 * @param stepCount 그날 걸음 수
 * @param stepName 걸음 항목 이름을 만드는 함수 (언어별). `(steps) => "걸음 6199보"`
 * @returns { items, total, overlap, fellBack, droppedStepItems }
 *   - items: [{ name, kcal, minutes, mets }] — 화면과 저장에 그대로 쓴다
 *   - fellBack: mets/minutes 없이 kcal만 온 항목이 있었는지 (구버전 응답 경로)
 *   - droppedStepItems: 모델이 넣지 말라는 걸음 항목을 넣어서 버린 것들
 */
function buildExercises(data, weightKg, stepCount, stepName) {
    const raw = Array.isArray(data?.exercises) ? data.exercises : [];

    const items = [];
    const droppedStepItems = [];
    let fellBack = false;

    for (const entry of raw) {
        const name = String(entry?.name || "").trim();
        if (!name) continue;

        // 걸음 항목은 서버가 붙인다. 모델이 또 넣었으면 버린다. 안 그러면 두 번 세어진다
        if (stepsOf(name) > 0) {
            droppedStepItems.push(name);
            continue;
        }

        const minutes = Number(entry?.minutes) || 0;
        const mets = Number(entry?.mets) || 0;

        if (minutes > 0 && mets > 0) {
            items.push({ name, kcal: metsToKcal(mets, weightKg, minutes), minutes, mets });
            continue;
        }

        // 구버전 경로: 시간·METs 없이 kcal만 왔다. 모델이 곱한 값을 그대로 쓴다.
        // 정확하진 않지만 운동을 통째로 0으로 만드는 것보다는 낫다
        const kcal = Math.abs(Number(entry?.kcal) || 0);
        if (kcal > 0) {
            fellBack = true;
            items.push({ name, kcal, minutes: 0, mets: 0 });
        }
    }

    // 운동 중에 이미 세어진 걸음만 뺀다. 통째로 버리지 않는다 —
    // 그러면 그날 걸어다닌 걸음이 전부 사라진다
    const steps = Math.max(0, Number(stepCount) || 0);
    const overlap = Math.min(steps, Math.max(0, Number(data?.stepsInExercise) || 0));
    const netSteps = steps - overlap;

    if (netSteps > 0) {
        items.push({
            name: stepName(netSteps),
            kcal: stepKcal(weightKg, netSteps),
            minutes: 0,
            mets: 0
        });
    }

    const total = items.reduce((acc, item) => acc + item.kcal, 0);

    return { items, total, overlap, fellBack, droppedStepItems };
}

module.exports = { buildExercises, metsToKcal, stepKcal, KCAL_PER_STEP_PER_KG };
