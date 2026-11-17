package com.youngs.dailynet

import com.youngs.dailynet.data.model.AnalysisDetail
import com.youngs.dailynet.data.model.AnalysisItem
import com.youngs.dailynet.data.model.CalorieTotals
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.model.MacroSet
import com.youngs.dailynet.data.model.MealCalories
import com.youngs.dailynet.data.model.MealItems
import com.youngs.dailynet.data.model.MealMacros
import com.youngs.dailynet.data.model.MealTexts
import com.youngs.dailynet.data.model.RecommendedIntake
import com.youngs.dailynet.ui.view.DailyReceiptBuilder
import com.youngs.dailynet.ui.view.DailyReceipt
import com.youngs.dailynet.ui.view.ReceiptLabels
import com.youngs.dailynet.ui.view.ReceiptLine
import com.youngs.dailynet.ui.view.formatKcal
import com.youngs.dailynet.ui.view.signedKcalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 결산 영수증의 숫자·문구 매기기.
 *
 * 공유 이미지는 사용자 계정 밖으로 나가는 물건이라 틀린 숫자가 찍히면 되돌릴 수 없다.
 * 앱 화면(리포트)과 영수증이 다른 값을 말하지 않도록 여기서 못박는다.
 *
 * 그리는 쪽(DailyReceiptImage)은 android.graphics라 JVM 테스트에서 실행하지 않는다.
 * 대신 이 테스트가 넘기는 값이 정확한지를 본다.
 */
class DailyReceiptTest {

    private val labels = ReceiptLabels(
        title = "칼로리 결산서",
        badge = "DAILYNET",
        serialPrefix = "영수증 번호",
        columnItem = "항목",
        columnKcal = "칼로리",
        breakfast = "아침",
        lunch = "점심",
        dinner = "저녁",
        snack = "간식",
        exercise = "운동",
        bmr = "기초대사",
        netLabel = "최종 순칼로리 (NET)",
        badgeDeficit = "감량 적자 달성",
        badgeSurplus = "섭취 초과",
        macroTitle = "오늘 탄단지 섭취",
        intakeTotalFormat = "총 %1\$s kcal 섭취",
        aiTitle = "AI 결산 한줄평",
        footer = "DAILYNET · 칼로리 결산 완료",
        macroCarb = "탄수화물",
        macroProtein = "단백질",
        macroFat = "지방",
        hourUnit = "시간",
        minuteUnit = "분"
    )

    /** 시안과 같은 하루: 1,410kcal 먹고 2,100kcal 써서 -690kcal */
    private fun analyzedRecord(): DailyRecordModel {
        val detail = AnalysisDetail(
            bmr = 1820,
            recommended = RecommendedIntake(calories = 2000, carb = 250, protein = 120, fat = 60),
            calories = MealCalories(breakfast = 320, lunch = 480, dinner = 610, snack = 0, exercise = 280),
            macros = MacroSet(carb = 142f, protein = 118f, fat = 44f),
            totals = CalorieTotals(intake = 1410, burned = 2100, net = -690),
            meals = MealItems(
                breakfast = listOf(AnalysisItem("그릭요거트", 180, amount = "150g"), AnalysisItem("그래놀라", 140, amount = "40g")),
                lunch = listOf(AnalysisItem("닭가슴살 샐러드 볼", 480)),
                dinner = listOf(AnalysisItem("연어 포케", 450), AnalysisItem("현미밥", 160))
            ),
            exercises = listOf(
                AnalysisItem("웨이트 트레이닝", 280, minutes = 50, mets = 4.3f),
                AnalysisItem("걸음 9,420보", 260)
            ),
            mealMacros = MealMacros(present = true),
            descriptions = MealTexts(breakfast = "단백질을 잘 챙겼어요."),
            evaluation = "단백질 118g으로 목표를 잘 채웠고, 걸음수와 웨이트로 깔끔한 적자 마감이에요."
        )
        return DailyRecordModel(
            date = "2026-11-17",
            breakfast = "그릭요거트, 그래놀라",
            lunch = "닭가슴살 샐러드 볼",
            dinner = "연어 포케, 현미밥",
            exercise = "웨이트 트레이닝 50분",
            steps = 9420,
            netCalories = -690,
            finalized = true,
            breakfastKcal = 320,
            lunchKcal = 480,
            dinnerKcal = 610,
            exerciseKcal = 280,
            carbGram = 142f,
            proteinGram = 118f,
            fatGram = 44f,
            bmr = 1820,
            analysisResult = "분석 결과",
            analysisDetail = detail
        )
    }

    @Test
    fun receiptKeepsMealOrderAndSigns() {
        val receipt = DailyReceiptBuilder.build(analyzedRecord(), labels, Locale.KOREAN)

        // 간식은 0kcal이라 빠지고, 끼니 3줄 + 기초대사 + 걸음 + 운동 = 6줄
        assertEquals(6, receipt.lines.size)
        assertEquals("아침 · 그릭요거트, 그래놀라", receipt.lines[0].title)
        assertEquals(320, receipt.lines[0].kcal)
        assertFalse(receipt.lines[0].isBurn)
        assertEquals("점심 · 닭가슴살 샐러드 볼", receipt.lines[1].title)
        assertEquals("저녁 · 연어 포케, 현미밥", receipt.lines[2].title)
        // 기초대사는 끼니 다음, 운동 앞
        assertEquals("기초대사", receipt.lines[3].title)
        assertEquals(1820, receipt.lines[3].kcal)
        assertTrue(receipt.lines[3].isBurn)
    }

    @Test
    fun stepItemIsNotLabelledAsExercise() {
        val receipt = DailyReceiptBuilder.build(analyzedRecord(), labels, Locale.KOREAN)
        val stepLine: ReceiptLine = receipt.lines.first { it.title.contains("걸음") }

        // 서버가 운동 목록에 넣어 주는 걸음 항목에 "운동 ·"을 붙이면 걸음이 운동처럼 읽힌다
        assertEquals("걸음 9,420보", stepLine.title)
        assertEquals(260, stepLine.kcal)
        assertTrue(stepLine.isBurn)

        val workout = receipt.lines.first { it.title.contains("웨이트") }
        assertEquals("운동 · 웨이트 트레이닝 50분", workout.title)
    }

    @Test
    fun intakeAndNetMatchTheReceiptRows() {
        val receipt = DailyReceiptBuilder.build(analyzedRecord(), labels, Locale.KOREAN)

        // 표에 찍힌 섭취 줄의 합과 "총 N kcal 섭취"가 같은 숫자여야 한다
        assertEquals(1410, receipt.intakeKcal)
        assertEquals(320 + 480 + 610, receipt.intakeKcal)
        assertEquals(-690, receipt.netKcal)
        assertEquals("-690", receipt.netText)
        assertTrue(receipt.isDeficit)
    }

    @Test
    fun macrosAndAiNoteComeFromTheAnalysis() {
        val receipt = DailyReceiptBuilder.build(analyzedRecord(), labels, Locale.KOREAN)

        assertEquals(142f, receipt.carbGram, 0.01f)
        assertEquals(118f, receipt.proteinGram, 0.01f)
        assertEquals(44f, receipt.fatGram, 0.01f)
        assertTrue(receipt.hasMacros)
        assertTrue(receipt.aiNote.startsWith("단백질 118g으로"))
    }

    @Test
    fun dateAndSerialArePrinted() {
        val receipt = DailyReceiptBuilder.build(analyzedRecord(), labels, Locale.KOREAN)

        assertEquals("2026. 11. 17. (화)", receipt.dateText)
        assertEquals("#20261117", receipt.serial)
    }

    @Test
    fun englishLocaleUsesEnglishWeekday() {
        val receipt = DailyReceiptBuilder.build(analyzedRecord(), labels, Locale.US)
        assertEquals("2026. 11. 17. (Tue)", receipt.dateText)
    }

    @Test
    fun brokenDateDoesNotThrow() {
        val record = analyzedRecord().copy(date = "not-a-date", netCalories = -10)
        val receipt = DailyReceiptBuilder.build(record, labels, Locale.KOREAN)

        assertEquals("not-a-date", receipt.dateText)
        assertEquals("", receipt.serial)
    }

    /** b33 이전 기록: 구조가 없어 사용자가 적은 글과 저장된 집계 컬럼으로 채운다 */
    @Test
    fun legacyRecordFallsBackToTypedText() {
        val record = DailyRecordModel(
            date = "2026-10-01",
            breakfast = "토스트 2장\n커피",
            lunch = "",
            dinner = "김치찌개",
            exercise = "걷기 1시간",
            netCalories = 120,
            finalized = true,
            breakfastKcal = 350,
            dinnerKcal = 520,
            exerciseKcal = 180,
            carbGram = 90f,
            proteinGram = 55f,
            fatGram = 30f,
            bmr = 1700,
            analysisResult = "옛 형식 결과"
        )

        val receipt = DailyReceiptBuilder.build(record, labels, Locale.KOREAN)

        assertEquals(listOf("아침 · 토스트 2장", "저녁 · 김치찌개", "기초대사", "운동 · 걷기 1시간"), receipt.lines.map { it.title })
        assertEquals(350, receipt.lines[0].kcal)
        assertEquals(180, receipt.lines[3].kcal)
        assertEquals(870, receipt.intakeKcal)
        assertEquals(120, receipt.netKcal)
        assertFalse(receipt.isDeficit)
        assertEquals("+120", receipt.netText)
        assertEquals(90f, receipt.carbGram, 0.01f)
        // 구조가 없으면 AI 총평도 없다 — 글에서 지어내지 않는다
        assertEquals("", receipt.aiNote)
    }

    @Test
    fun zeroCalorieMealsAreNotPrinted() {
        val record = analyzedRecord().copy(
            breakfast = "", lunch = "", dinner = "", snack = "",
            analysisDetail = null,
            breakfastKcal = 0, lunchKcal = 0, dinnerKcal = 0, snackKcal = 0
        )
        val receipt = DailyReceiptBuilder.build(record, labels, Locale.KOREAN)

        // 기초대사만 남는다 (구조가 없으므로 운동 kcal 컬럼만 있으면 그것도 남는다)
        assertTrue(receipt.lines.none { it.title.startsWith("아침") })
        assertTrue(receipt.lines.none { it.title.startsWith("점심") })
        assertEquals(0, receipt.intakeKcal)
    }

    @Test
    fun netFallsBackToAnalysisTotalsWhenColumnIsZero() {
        val record = analyzedRecord().copy(netCalories = 0)
        val receipt = DailyReceiptBuilder.build(record, labels, Locale.KOREAN)

        assertEquals(-690, receipt.netKcal)
    }

    @Test
    fun kcalFormattingUsesThousandsSeparators() {
        assertEquals("0", formatKcal(0))
        assertEquals("690", formatKcal(690))
        assertEquals("1,410", formatKcal(1410))
        assertEquals("12,345", formatKcal(12345))
        assertEquals("+320", signedKcalText(320, isBurn = false))
        assertEquals("-1,820", signedKcalText(1820, isBurn = true))
    }

    @Test
    fun emptyRecordStillBuildsAReceipt() {
        val receipt: DailyReceipt = DailyReceiptBuilder.build(DailyRecordModel(date = "2026-11-17"), labels, Locale.KOREAN)

        assertTrue(receipt.lines.isEmpty())
        assertEquals(0, receipt.netKcal)
        assertEquals("0", receipt.netText)
        assertEquals("", receipt.aiNote)
        assertFalse(receipt.hasMacros)
    }
}
