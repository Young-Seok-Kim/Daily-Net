package com.youngs.dailynet.data.model

/**
 * 서버가 계산한 분석 결과를 구조화한 형태.
 *
 * 예전에는 리포트를 긴 문자열([AnalysisResponse.feedback]) 하나로만 받아서, 화면에 그대로
 * 뿌리는 것 말고는 할 수 있는 게 없었다. 끼니별 칼로리도 탄단지도 텍스트 안에 묻혀 있어
 * 차트를 그리거나 기간별로 집계할 수 없었다.
 *
 * 모든 필드에 기본값을 둔 이유는 두 가지다.
 * - 서버가 항목을 빠뜨려도 앱이 죽지 않아야 한다
 * - 이 필드가 없던 시절(b24 이전)에 저장된 기록을 읽을 때도 안전해야 한다
 */
data class AnalysisDetail(
    /** 기초대사량 */
    val bmr: Int = 0,
    val recommended: RecommendedIntake = RecommendedIntake(),
    val calories: MealCalories = MealCalories(),
    /** 하루 총 탄단지 (끼니별 합계) */
    val macros: MacroSet = MacroSet(),
    val totals: CalorieTotals = CalorieTotals(),
    val meals: MealItems = MealItems(),
    val exercises: List<AnalysisItem> = emptyList()
)

/** 체중 감량을 위한 하루 권장 섭취량 */
data class RecommendedIntake(
    val calories: Int = 0,
    val carb: Int = 0,
    val protein: Int = 0,
    val fat: Int = 0
)

data class MealCalories(
    val breakfast: Int = 0,
    val lunch: Int = 0,
    val dinner: Int = 0,
    val snack: Int = 0,
    /** 운동으로 소모한 칼로리 (양수) */
    val exercise: Int = 0
)

data class MacroSet(
    val carb: Float = 0f,
    val protein: Float = 0f,
    val fat: Float = 0f
)

data class CalorieTotals(
    /** 먹은 총 칼로리 */
    val intake: Int = 0,
    /** 기초대사 + 운동으로 소모한 총 칼로리 */
    val burned: Int = 0,
    val net: Int = 0
)

data class MealItems(
    val breakfast: List<AnalysisItem> = emptyList(),
    val lunch: List<AnalysisItem> = emptyList(),
    val dinner: List<AnalysisItem> = emptyList(),
    val snack: List<AnalysisItem> = emptyList()
)

/** 메뉴 하나 또는 운동 하나 */
data class AnalysisItem(
    val name: String = "",
    val kcal: Int = 0
)
