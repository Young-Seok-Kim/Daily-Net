package com.youngs.dailynet.util

import com.youngs.dailynet.data.model.AnalysisDetail
import com.youngs.dailynet.data.model.AnalysisItem
import com.youngs.dailynet.data.model.CalorieTotals
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.model.MacroSet
import com.youngs.dailynet.data.model.MealCalories
import com.youngs.dailynet.data.model.MealItems
import com.youngs.dailynet.data.model.MealMacros
import kotlin.math.roundToInt

/** 리포트에서 사용자가 고칠 수 있는 항목이 속한 자리 */
enum class ReportSection { BREAKFAST, LUNCH, DINNER, SNACK, EXERCISE }

/**
 * 사용자가 분석 결과의 kcal을 고쳤을 때 집계를 다시 세는 산수.
 *
 * AI는 양을 자주 틀린다. 프롬프트를 아무리 조여도 "파스타 600g"처럼 중량을 잘못 잡으면
 * 열량이 같이 틀리고, 사용자는 그 숫자를 그날 결산으로 안고 가야 했다.
 * 항목 하나를 고치면 그날 숫자가 확실해지므로, 고친 값으로 여기서 다시 센다.
 *
 * 서버에 다시 묻지 않는다. 산수뿐이라 기기에서 끝나고, 분석 횟수도 쓰지 않는다.
 *
 * 순수 함수만 둔다. 화면·저장소와 떼어놓아야 테스트로 산수를 못박을 수 있다.
 */
object AnalysisEdit {

    /**
     * 서버 응답을 저장하기 전에 각 항목의 [AnalysisItem.aiKcal]에 kcal을 복사한다.
     * 서버는 이 필드를 보내지 않는다. 사용자가 나중에 고쳐도 원래 값을 보여주려면 지금 남겨야 한다.
     */
    fun stampAiKcal(detail: AnalysisDetail): AnalysisDetail {
        val stamp = { items: List<AnalysisItem> -> items.map { it.copy(aiKcal = it.kcal) } }
        return detail.copy(
            meals = MealItems(
                breakfast = stamp(detail.meals.breakfast),
                lunch = stamp(detail.meals.lunch),
                dinner = stamp(detail.meals.dinner),
                snack = stamp(detail.meals.snack)
            ),
            exercises = stamp(detail.exercises)
        )
    }

    /**
     * [section]의 [index]번째 항목 kcal을 [kcal]로 바꾸고 집계를 다시 센 결과.
     * 자리가 없으면(목록 밖 index) 원본을 그대로 돌려준다.
     */
    fun withItemKcal(detail: AnalysisDetail, section: ReportSection, index: Int, kcal: Int): AnalysisDetail {
        val safeKcal = kcal.coerceAtLeast(0)
        fun replace(items: List<AnalysisItem>): List<AnalysisItem> =
            if (index !in items.indices) items
            else items.mapIndexed { i, item -> if (i == index) item.copy(kcal = safeKcal) else item }

        val meals = detail.meals
        val changed = when (section) {
            ReportSection.BREAKFAST -> detail.copy(meals = meals.copy(breakfast = replace(meals.breakfast)))
            ReportSection.LUNCH -> detail.copy(meals = meals.copy(lunch = replace(meals.lunch)))
            ReportSection.DINNER -> detail.copy(meals = meals.copy(dinner = replace(meals.dinner)))
            ReportSection.SNACK -> detail.copy(meals = meals.copy(snack = replace(meals.snack)))
            ReportSection.EXERCISE -> detail.copy(exercises = replace(detail.exercises))
        }
        return recalculate(changed)
    }

    /**
     * 항목에서 끼니 합계·탄단지·섭취·소모·결산을 다시 센다.
     *
     * 탄단지는 항목별로 없고 끼니별로만 있다. 끼니 kcal이 바뀐 비율대로 그 끼니의 탄단지를
     * 같이 줄이거나 늘린다. 항목 하나를 고쳤는데 끼니 전체 비율로 움직이는 건 근사지만,
     * 탄단지를 그대로 두면 kcal은 반인데 탄단지는 그대로인 리포트가 되어 더 이상하다.
     * 끼니 kcal이 0이었으면 비율을 낼 수 없으니 탄단지는 그대로 둔다.
     */
    fun recalculate(detail: AnalysisDetail): AnalysisDetail {
        val meals = detail.meals
        val b = meals.breakfast.sumOf { it.kcal }
        val l = meals.lunch.sumOf { it.kcal }
        val d = meals.dinner.sumOf { it.kcal }
        val s = meals.snack.sumOf { it.kcal }
        val exercise = detail.exercises.sumOf { it.kcal }

        val old = detail.calories
        val mm = detail.mealMacros
        val mealMacros = MealMacros(
            breakfast = scale(mm.breakfast, old.breakfast, b),
            lunch = scale(mm.lunch, old.lunch, l),
            dinner = scale(mm.dinner, old.dinner, d),
            snack = scale(mm.snack, old.snack, s),
            present = mm.present
        )
        val macros = MacroSet(
            carb = round1(mealMacros.breakfast.carb + mealMacros.lunch.carb + mealMacros.dinner.carb + mealMacros.snack.carb),
            protein = round1(mealMacros.breakfast.protein + mealMacros.lunch.protein + mealMacros.dinner.protein + mealMacros.snack.protein),
            fat = round1(mealMacros.breakfast.fat + mealMacros.lunch.fat + mealMacros.dinner.fat + mealMacros.snack.fat)
        )

        val intake = b + l + d + s
        val burned = detail.bmr + exercise
        return detail.copy(
            calories = MealCalories(breakfast = b, lunch = l, dinner = d, snack = s, exercise = exercise),
            mealMacros = mealMacros,
            macros = macros,
            totals = CalorieTotals(intake = intake, burned = burned, net = intake - burned)
        )
    }

    /** 다시 센 [detail]의 집계를 기록의 컬럼(차트·위젯·목록이 읽는 값)에 옮겨 적는다 */
    fun applyToRecord(record: DailyRecordModel, detail: AnalysisDetail): DailyRecordModel =
        record.copy(
            analysisDetail = detail,
            resultEdited = true,
            netCalories = detail.totals.net,
            breakfastKcal = detail.calories.breakfast,
            lunchKcal = detail.calories.lunch,
            dinnerKcal = detail.calories.dinner,
            snackKcal = detail.calories.snack,
            exerciseKcal = detail.calories.exercise,
            carbGram = detail.macros.carb,
            proteinGram = detail.macros.protein,
            fatGram = detail.macros.fat
        )

    private fun scale(macro: MacroSet, oldKcal: Int, newKcal: Int): MacroSet {
        if (oldKcal <= 0 || oldKcal == newKcal) return macro
        val ratio = newKcal.toFloat() / oldKcal
        return MacroSet(
            carb = round1(macro.carb * ratio),
            protein = round1(macro.protein * ratio),
            fat = round1(macro.fat * ratio)
        )
    }

    private fun round1(value: Float): Float = (value * 10f).roundToInt() / 10f
}
