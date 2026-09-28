package com.youngs.dailynet

import com.youngs.dailynet.data.model.AnalysisDetail
import com.youngs.dailynet.data.model.AnalysisItem
import com.youngs.dailynet.data.model.CalorieTotals
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.model.MacroSet
import com.youngs.dailynet.data.model.MealCalories
import com.youngs.dailynet.data.model.MealItems
import com.youngs.dailynet.data.model.MealMacros
import com.youngs.dailynet.util.AnalysisEdit
import com.youngs.dailynet.util.ReportSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 사용자가 리포트의 kcal을 고쳤을 때 집계를 다시 세는 산수 검증.
 * 서버가 준 모양 그대로 만들어 넣는다 (기초대사 1500, 점심 두 항목, 운동 하나).
 */
class AnalysisEditTest {

    private fun detail() = AnalysisEdit.stampAiKcal(
        AnalysisDetail(
            bmr = 1500,
            calories = MealCalories(breakfast = 0, lunch = 900, dinner = 0, snack = 0, exercise = 300),
            macros = MacroSet(carb = 100f, protein = 30f, fat = 20f),
            totals = CalorieTotals(intake = 900, burned = 1800, net = -900),
            meals = MealItems(
                lunch = listOf(
                    AnalysisItem("파스타 600g", 600),
                    AnalysisItem("피자 1조각 100g", 300)
                )
            ),
            exercises = listOf(AnalysisItem("걷기 60분", 300)),
            mealMacros = MealMacros(lunch = MacroSet(carb = 100f, protein = 30f, fat = 20f), present = true)
        )
    )

    @Test
    fun `항목 하나를 고치면 그 항목만 바뀌고 끼니 합계와 결산이 따라온다`() {
        val result = AnalysisEdit.withItemKcal(detail(), ReportSection.LUNCH, 0, 300)

        assertEquals(300, result.meals.lunch[0].kcal)
        assertEquals(300, result.meals.lunch[1].kcal)
        assertEquals(600, result.calories.lunch)
        assertEquals(600, result.totals.intake)
        assertEquals(1800, result.totals.burned)
        assertEquals(-1200, result.totals.net)
    }

    @Test
    fun `고친 항목은 AI 값을 그대로 기억한다`() {
        val result = AnalysisEdit.withItemKcal(detail(), ReportSection.LUNCH, 0, 300)

        assertEquals(600, result.meals.lunch[0].aiKcal)
        assertTrue(result.meals.lunch[0].isEdited)
        assertFalse(result.meals.lunch[1].isEdited)
    }

    @Test
    fun `끼니 탄단지는 kcal이 바뀐 비율대로 움직이고 하루 합계도 다시 센다`() {
        // 점심 900 → 600이면 2/3
        val result = AnalysisEdit.withItemKcal(detail(), ReportSection.LUNCH, 0, 300)

        assertEquals(66.7f, result.mealMacros.lunch.carb, 0.01f)
        assertEquals(20f, result.mealMacros.lunch.protein, 0.01f)
        assertEquals(13.3f, result.mealMacros.lunch.fat, 0.01f)
        assertEquals(66.7f, result.macros.carb, 0.01f)
    }

    @Test
    fun `운동을 고치면 소모와 결산이 바뀌고 섭취는 그대로다`() {
        val result = AnalysisEdit.withItemKcal(detail(), ReportSection.EXERCISE, 0, 500)

        assertEquals(500, result.calories.exercise)
        assertEquals(900, result.totals.intake)
        assertEquals(2000, result.totals.burned)
        assertEquals(-1100, result.totals.net)
    }

    @Test
    fun `목록 밖 자리를 고치면 아무것도 바뀌지 않는다`() {
        val before = detail()
        val result = AnalysisEdit.withItemKcal(before, ReportSection.DINNER, 3, 100)

        assertEquals(before.totals, result.totals)
        assertEquals(before.meals, result.meals)
    }

    @Test
    fun `음수는 0으로 막는다`() {
        val result = AnalysisEdit.withItemKcal(detail(), ReportSection.LUNCH, 1, -50)

        assertEquals(0, result.meals.lunch[1].kcal)
        assertEquals(600, result.calories.lunch)
    }

    @Test
    fun `기록 컬럼에 옮겨 적으면 차트가 읽는 값이 함께 바뀐다`() {
        val edited = AnalysisEdit.withItemKcal(detail(), ReportSection.LUNCH, 0, 300)
        val record = AnalysisEdit.applyToRecord(DailyRecordModel(date = "2026-09-28"), edited)

        assertTrue(record.resultEdited)
        assertEquals(-1200, record.netCalories)
        assertEquals(600, record.lunchKcal)
        assertEquals(300, record.exerciseKcal)
        assertEquals(66.7f, record.carbGram, 0.01f)
        assertEquals(edited, record.analysisDetail)
    }
}
