/**
 * 하루 권장 섭취량 계산. **이 파일이 기준값의 원본이다.**
 *
 * ⚠️ 앱의 `WeekSummary.kt`에 같은 계산이 있다. 한쪽만 고치면 AI 리포트에 적힌 권장량과
 * 앱 화면의 권장량이 어긋난다. 반드시 양쪽을 함께 고칠 것.
 */

/** 활동 계수 (가벼운 활동) */
const ACTIVITY_FACTOR = 1.375;

/** 감량을 위해 하루 소비량에서 빼는 칼로리 */
const DEFICIT_KCAL = 500;

/**
 * 체중 1kg당 권장 단백질(g).
 *
 * 일반 성인 권장은 0.8g/kg이지만, 칼로리를 줄이는 동안에는 근손실을 막기 위해
 * 1.2~2.0g/kg을 권한다. 그 중간값을 쓴다.
 */
const PROTEIN_PER_KG = 1.6;

/** 지방이 차지할 칼로리 비율. 일반 권장 범위는 20~35%다. */
const FAT_CALORIE_RATIO = 0.25;

/** Mifflin-St Jeor 공식 기초대사량 */
function calculateBmr(weight, height, age, isMale) {
    return Math.round(10 * weight + 6.25 * height - 5 * age + (isMale ? 5 : -161));
}

/**
 * 하루 권장 섭취 칼로리와 탄단지.
 *
 * 단백질은 칼로리 비율이 아니라 **체중 기준**으로 잡는다.
 * 예전에는 권장 칼로리의 40%를 단백질로 배분해서, 체중과 무관하게 2000kcal면
 * 무조건 200g이 나왔다. 70kg인 사람에게 2.9g/kg으로, 기준으로 쓸 수 없는 값이었다.
 *
 * 단백질과 지방을 먼저 정하고 **남은 칼로리를 탄수화물로 채운다.**
 * 그래야 세 값을 칼로리로 환산한 합이 권장 칼로리와 맞는다.
 */
function recommendedIntake(bmr, weight) {
    const calories = Math.round(bmr * ACTIVITY_FACTOR) - DEFICIT_KCAL;
    const kg = Number(weight) || 0;

    const protein = Math.round(kg * PROTEIN_PER_KG);
    const fat = Math.round((calories * FAT_CALORIE_RATIO) / 9);
    // 체중이 아주 크거나 권장 칼로리가 낮으면 남는 칼로리가 음수가 될 수 있다
    const carb = Math.max(0, Math.round((calories - protein * 4 - fat * 9) / 4));

    return { calories, carb, protein, fat };
}

module.exports = { calculateBmr, recommendedIntake };
