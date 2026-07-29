/**
 * 리포트에 들어가는 언어별 고정 문구.
 * analyzeDiet과 extractMeal이 함께 쓴다.
 */

/**
 * 리포트에 들어가는 고정 문구. AI가 쓰는 문장이 아니라 서버가 조립하는 부분이라 여기서 번역한다.
 * 언어를 추가하려면 이 표에 항목 하나만 더 넣으면 된다.
 */
const LABELS = {
    ko: {
        outputLanguage: "한국어",
        reportTitle: "📋 오늘의 영양 분석 리포트",
        breakfast: "🍳 아침", lunch: "🍳 점심", dinner: "🍳 저녁", snack: "🍰 간식",
        total: "합계",
        carb: "탄", protein: "단", fat: "지",
        noInfo: "정보 없음",
        exerciseTitle: "🔥 운동별 소모 칼로리",
        totalBurned: "총",
        noExercise: "   • 기록된 운동이 없습니다",
        unknownExercise: "운동",
        unknownMenu: "알 수 없음",
        goalTitle: "🎯 다이어트 권장 목표 가이드",
        goalIntake: "• 하루 권장 섭취량",
        goalMacro: "• 추천 탄단지",
        myIntake: "• 나의 오늘 섭취량",
        over: "⚠️ 권장 초과",
        under: "✅ 권장 이내",
        macroTitle: "📊 나의 오늘 실제 탄단지 총합",
        carbFull: "• 탄수화물", proteinFull: "• 단백질", fatFull: "• 지방",
        inLabel: "🍎 인입 (IN)",
        outLabel: "🏃 배출 (OUT)",
        bmrNote: (bmr) => `기초대사 ${bmr} 포함`,
        netLabel: "📉 최종 결산",
        evalTitle: "💡 전문가 총평",
        error: "분석 중 오류가 발생했습니다.",
        limitReached: "오늘 분석 횟수를 모두 사용했습니다.",
        photoLimitReached: "오늘 사진 인식 횟수를 모두 사용했습니다.",
        photoLimitFree: "무료 사진 인식은 하루 3회입니다. 구독하면 더 쓸 수 있어요.",
        authRequired: "로그인 정보를 확인할 수 없습니다. 다시 로그인해 주세요."
    },
    en: {
        outputLanguage: "English",
        reportTitle: "📋 Today's Nutrition Report",
        breakfast: "🍳 Breakfast", lunch: "🍳 Lunch", dinner: "🍳 Dinner", snack: "🍰 Snack",
        total: "total",
        carb: "C", protein: "P", fat: "F",
        noInfo: "No data",
        exerciseTitle: "🔥 Calories Burned by Activity",
        totalBurned: "total",
        noExercise: "   • No activity recorded",
        unknownExercise: "Activity",
        unknownMenu: "Unknown",
        goalTitle: "🎯 Recommended Daily Targets",
        goalIntake: "• Recommended intake",
        goalMacro: "• Recommended macros",
        myIntake: "• Your intake today",
        over: "⚠️ Over target",
        under: "✅ Within target",
        macroTitle: "📊 Your Macros Today",
        carbFull: "• Carbs", proteinFull: "• Protein", fatFull: "• Fat",
        inLabel: "🍎 IN",
        outLabel: "🏃 OUT",
        bmrNote: (bmr) => `includes BMR ${bmr}`,
        netLabel: "📉 Net",
        evalTitle: "💡 Expert Summary",
        error: "An error occurred during analysis.",
        limitReached: "You have used all of today's analyses.",
        photoLimitReached: "You have used all of today's photo scans.",
        photoLimitFree: "Free photo scans are limited to 3 per day. Subscribe for more.",
        authRequired: "We could not verify your sign-in. Please sign in again."
    }
};

/**
 * 앱이 보낸 언어 코드를 지원 언어로 바꾼다.
 *
 * 값이 아예 없으면(= language를 보내지 않는 구버전 앱) 한국어로 떨어뜨려
 * 기존 사용자가 지금까지 받던 결과와 완전히 동일하게 유지한다.
 * 값은 있는데 지원하지 않는 언어면, 한국어보다는 영어가 나으므로 영어로 보낸다.
 */
function resolveLang(raw) {
    if (!raw) return "ko";
    const primary = String(raw).toLowerCase().split(/[-_]/)[0];
    return LABELS[primary] ? primary : "en";
}

module.exports = { LABELS, resolveLang };
