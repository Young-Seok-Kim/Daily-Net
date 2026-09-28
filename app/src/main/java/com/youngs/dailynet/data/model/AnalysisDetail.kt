package com.youngs.dailynet.data.model

import com.google.firebase.firestore.Exclude

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
    val exercises: List<AnalysisItem> = emptyList(),

    // ── b33부터 ──────────────────────────────────────────────
    // 리포트를 텍스트가 아니라 이 구조에서 그리기 위해 필요한 나머지.
    // 사용자가 항목의 kcal을 고칠 수 있게 하려면 화면이 구조를 그려야 하고,
    // 그러려면 텍스트에만 있던 끼니별 탄단지와 AI가 쓴 글도 여기 있어야 한다.
    // b33 이전 서버 응답과 기록에는 없으므로 전부 기본값이다.
    /** 끼니별 탄단지 */
    val mealMacros: MealMacros = MealMacros(),
    /** 끼니별 한줄평 (AI가 쓴 글) */
    val descriptions: MealTexts = MealTexts(),
    /** 전문가 총평 (AI가 쓴 글) */
    val evaluation: String = ""
) {
    /**
     * 화면을 이 구조로 그릴 수 있는지.
     *
     * b33 이전 서버가 준 structured에는 항목 목록은 있어도 끼니별 탄단지·한줄평이 없어서
     * 그리면 빈 리포트가 된다. 그런 기록은 예전처럼 텍스트를 보여줘야 한다.
     * 기초대사량은 서버가 항상 채우고, 끼니별 탄단지는 b33부터만 오므로 둘로 가른다.
     */
    // Firestore는 getter를 전부 필드로 쓰려 든다. 계산값이라 저장하지 않는다.
    @get:Exclude
    val isRenderable: Boolean
        get() = bmr > 0 && mealMacros.present
}

data class MealMacros(
    val breakfast: MacroSet = MacroSet(),
    val lunch: MacroSet = MacroSet(),
    val dinner: MacroSet = MacroSet(),
    val snack: MacroSet = MacroSet(),
    /**
     * 서버가 이 필드를 보냈는지. b33 이전 응답은 이 객체가 통째로 없어 기본값(false)이 된다.
     * 값이 전부 0인 날(아무것도 안 먹은 날)과 구분하려고 따로 둔다.
     */
    val present: Boolean = false
)

data class MealTexts(
    val breakfast: String = "",
    val lunch: String = "",
    val dinner: String = "",
    val snack: String = ""
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
    val kcal: Int = 0,
    /**
     * AI(서버)가 처음 낸 값. 사용자가 [kcal]을 고쳐도 이건 그대로 둔다.
     * 고친 항목을 표시하고, 수정 창에서 "AI 추정 ○○kcal"로 보여주는 데 쓴다.
     * 서버 응답에는 없는 필드라 저장할 때 kcal을 복사해 채운다 (b33 이전 기록은 0).
     */
    val aiKcal: Int = 0
) {
    /** 사용자가 고친 항목인지. aiKcal이 0이면 원래 값을 모르는 옛 기록이라 판정하지 않는다 */
    @get:Exclude
    val isEdited: Boolean
        get() = aiKcal > 0 && kcal != aiKcal
}
