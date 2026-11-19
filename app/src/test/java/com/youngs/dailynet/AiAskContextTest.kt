package com.youngs.dailynet

import com.youngs.dailynet.data.local.entity.ExercisePassEntity
import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.util.computePassUsage
import com.youngs.dailynet.ui.view.AiAskContext
import com.youngs.dailynet.ui.view.PeriodSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 질문에 실어 보내는 화면 요약.
 *
 * 서버는 이 글을 **다시 계산하지 않고 그대로 인용**하므로(functions/askAiCoach.js),
 * 화면에 보이는 숫자가 빠지거나 다른 값이 들어가면 답변이 화면과 어긋난다.
 * 그 어긋남을 여기서 잡는다.
 */
class AiAskContextTest {

    private fun record(
        date: String,
        net: Int = -420,
        weight: Float = 0f,
        breakfast: String = "",
        lunch: String = "",
        dinner: String = "",
        snack: String = "",
        exercise: String = "",
        exerciseKcal: Int = 0,
        remark: String = "",
        bmr: Int = 0,
        carb: Float = 0f,
        protein: Float = 0f,
        fat: Float = 0f,
        steps: Int = 0,
        analysis: String = ""
    ) = DailyRecordModel(
        date = date,
        netCalories = net,
        weight = weight,
        breakfast = breakfast,
        lunch = lunch,
        dinner = dinner,
        snack = snack,
        exercise = exercise,
        exerciseKcal = exerciseKcal,
        remark = remark,
        bmr = bmr,
        carbGram = carb,
        proteinGram = protein,
        fatGram = fat,
        steps = steps,
        analysisResult = analysis,
        hasExercise = exercise.isNotBlank() || exerciseKcal > 0
    )

    private fun summary(
        recordDays: Int = 18,
        exerciseDays: Int = 6,
        netTotal: Int = -6840,
        netAverage: Int = -380,
        nutritionDays: Int = 15,
        carbAvg: Float = 172f,
        proteinAvg: Float = 92f,
        fatAvg: Float = 48f,
        weightDiff: Float? = -1.1f
    ) = PeriodSummary(
        recordDays = recordDays,
        exerciseDays = exerciseDays,
        netTotal = netTotal,
        netAverage = netAverage,
        nutritionDays = nutritionDays,
        carbAvg = carbAvg,
        proteinAvg = proteinAvg,
        fatAvg = fatAvg,
        recCarb = 210,
        recProtein = 112,
        recFat = 55,
        weightDiff = weightDiff
    )

    @Test
    fun `하루 정산은 화면의 숫자와 식단을 그대로 담는다`() {
        val text = AiAskContext.dailyRecord(
            record(
                date = "2026-11-19",
                net = -420,
                weight = 78.2f,
                breakfast = "닭가슴살 샐러드",
                lunch = "김치찌개",
                dinner = "계란후라이",
                exercise = "웨이트 50분",
                exerciseKcal = 380,
                remark = "야식 참음",
                bmr = 1750,
                carb = 180f,
                protein = 95f,
                fat = 45f,
                steps = 8200
            )
        )

        assertTrue(text.contains("date 2026-11-19"))
        assertTrue(text.contains("weight 78.2kg"))
        // 섭취 kcal는 끼니별 값의 합이다 (여기선 비어 있어 0) — 화면의 순칼로리는 그대로 적는다
        assertTrue(text.contains("net -420 kcal"))
        assertTrue(text.contains("macros C180g P95g F45g, bmr 1750kcal"))
        assertTrue(text.contains("steps 8200"))
        assertTrue(text.contains("B: 닭가슴살 샐러드"))
        assertTrue(text.contains("L: 김치찌개"))
        assertTrue(text.contains("D: 계란후라이"))
        assertTrue(text.contains("exercise: 웨이트 50분 (380kcal)"))
        assertTrue(text.contains("remark: 야식 참음"))
        // 간식 칸은 비었으므로 S: 줄이 없어야 한다
        assertFalse(text.contains("S: "))
    }

    @Test
    fun `영양 집계가 없는 옛 기록에는 탄단지 줄을 넣지 않는다`() {
        val text = AiAskContext.dailyRecord(record(date = "2025-01-02", net = 0))
        assertFalse(text.contains("macros"))
        assertFalse(text.contains("bmr"))
    }

    @Test
    fun `분석 리포트는 앞부분만 보낸다`() {
        val longReport = "가".repeat(AiAskContext.REPORT_MAX + 300)
        val text = AiAskContext.dailyRecord(record(date = "2026-11-19", analysis = longReport))
        assertTrue(text.contains("report: "))
        assertTrue(text.length < longReport.length)
    }

    @Test
    fun `일일 목록은 합계와 최근 7일만 담는다`() {
        val records = (1..12).map {
            record(date = "2026-11-%02d".format(it), net = -100 * it, weight = 70f + it / 10f)
        }
        val text = AiAskContext.dailyList(records)

        assertTrue(text.contains("records 12 days"))
        assertTrue(text.contains("total net -7800 kcal"))
        assertTrue(text.contains("avg net -650 kcal/day"))
        // 최신순으로 들어오므로 11-01 ~ 11-07 일곱 줄만
        assertTrue(text.contains("- 2026-11-01"))
        assertTrue(text.contains("- 2026-11-07"))
        assertFalse(text.contains("- 2026-11-08"))
    }

    @Test
    fun `목록 줄에는 무슨 운동을 했는지 글자가 들어간다`() {
        val text = AiAskContext.dailyList(listOf(
            record(date = "2026-11-02", exercise = "러닝 30분"),
            record(date = "2026-11-01", exercise = "", exerciseKcal = 0)
        ))
        assertTrue(text.contains("- 2026-11-02 net -420 kcal, exercise: 러닝 30분"))
        assertFalse(text.contains("2026-11-01 net -420 kcal, exercise"))
    }

    @Test
    fun `운동 이용권은 쓸 수 있는 것만 남은 횟수·기간과 함께 한 줄로 붙는다`() {
        val today = "2026-11-19"
        val records = listOf(
            record(date = "2026-11-18", exercise = "헬스 1시간"),
            record(date = "2026-11-15", exercise = "헬스")
        )
        val gym = ExercisePassEntity(id = "a", name = "헬스", kind = ExercisePassEntity.KIND_COUNT,
            startDate = "2026-11-01", endDate = "2026-12-31", totalCount = 10, keyword = "헬스")
        val pilates = ExercisePassEntity(id = "b", name = "필라테스", kind = ExercisePassEntity.KIND_PERIOD,
            startDate = "2026-11-01", endDate = "2026-11-20")
        val expired = ExercisePassEntity(id = "c", name = "수영", kind = ExercisePassEntity.KIND_PERIOD,
            startDate = "2026-10-01", endDate = "2026-10-31")
        val usages = listOf(gym, pilates, expired).map { computePassUsage(it, records, today) }

        val line = AiAskContext.passLine(usages)!!
        assertEquals(
            "pass 헬스 (count): 2/10 used, 8 left, since 2026-11-01, until 2026-12-31, went 2 days: 2026-11-15, 2026-11-18\n" +
                "pass 필라테스 (period): 2 days left, since 2026-11-01, until 2026-11-20",
            line
        )

        // 상세·목록 요약 둘 다에 붙고, 리포트보다 앞에 온다
        val detail = AiAskContext.dailyRecord(record(date = today, analysis = "리포트"), usages)
        assertTrue(detail.indexOf("pass 헬스") < detail.indexOf("report:"))
        assertTrue(AiAskContext.dailyList(records, usages).contains("pass 헬스"))
        // 이용권이 없으면 줄 자체가 없다
        assertFalse(AiAskContext.dailyRecord(record(date = today)).contains("pass "))
        assertEquals(null, AiAskContext.passLine(listOf(computePassUsage(expired, records, today))))
    }

    @Test
    fun `기간제 이용권도 키워드가 있으면 간 날짜를 적고, 많으면 최근 20일만`() {
        val today = "2026-11-19"
        val kick = ExercisePassEntity(id = "k", name = "오버컴", kind = ExercisePassEntity.KIND_PERIOD,
            startDate = "2026-10-01", endDate = "2026-12-31", keyword = "킥복싱")
        val records = (1..25).map { record(date = "2026-10-%02d".format(it), exercise = "킥복싱 50분") } +
            record(date = "2026-11-01", exercise = "러닝")
        val line = AiAskContext.passLine(listOf(computePassUsage(kick, records, today)))!!
        assertTrue(line.startsWith("pass 오버컴 (period): 43 days left, since 2026-10-01, until 2026-12-31, went 25 days: ... 2026-10-06"))
        assertTrue(line.endsWith("2026-10-25"))
        assertFalse(line.contains("2026-10-05,"))

        // 키워드가 있는데 한 번도 안 갔으면 0으로 밝힌다 (모델이 "기록 없음"으로 오해하지 않게)
        val none = AiAskContext.passLine(listOf(computePassUsage(kick, emptyList(), today)))!!
        assertTrue(none.contains("went 0 days (keyword 킥복싱)"))
    }

    @Test
    fun `기록이 없으면 없다고 보낸다`() {
        assertEquals("no records yet", AiAskContext.dailyList(emptyList()))
        assertEquals("no inbody measurements", AiAskContext.inbody(emptyList(), trend = false))
        assertEquals("no weight records", AiAskContext.weightTrend(emptyList()))
    }

    @Test
    fun `기간 정산은 카드의 합계와 권장량을 그대로 담는다`() {
        val text = AiAskContext.periodSummary(
            summary = summary(),
            records = listOf(record(date = "2026-11-18"), record(date = "2026-11-19")),
            title = "11월"
        )

        assertTrue(text.contains("period 11월"))
        assertTrue(text.contains("recorded 18 days, exercise 6 days"))
        assertTrue(text.contains("net total -6840 kcal"))
        assertTrue(text.contains("avg -380 kcal/day"))
        assertTrue(text.contains("weight change -1.1kg"))
        assertTrue(text.contains("macros avg C172g P92g F48g over 15 days"))
        assertTrue(text.contains("recommended C210g P112g F55g"))
        assertTrue(text.contains("daily:"))
    }

    @Test
    fun `연 정산처럼 기록이 많으면 31일만 넣고 생략을 밝힌다`() {
        val records = (1..40).map { record(date = "2026-01-%02d".format(it)) }
        val text = AiAskContext.periodSummary(summary(), records, "2026년")

        assertTrue(text.contains("- 2026-01-31"))
        assertFalse(text.contains("- 2026-01-32"))
        assertTrue(text.contains("... 9 older days omitted"))
    }

    @Test
    fun `탄단지 기록이 없는 기간에는 평균 줄을 넣지 않는다`() {
        val text = AiAskContext.periodSummary(
            summary = summary(nutritionDays = 0, weightDiff = null),
            records = listOf(record(date = "2026-11-19")),
            title = "2026년"
        )
        assertFalse(text.contains("macros avg"))
        assertFalse(text.contains("weight change"))
    }

    @Test
    fun `인바디는 최근 측정과 이전 측정, 해석을 담는다`() {
        val records = listOf(
            InbodyRecordEntity(
                measuredAt = "2026-11-10",
                weight = 77.8f,
                bodyFatPercent = 24.1f,
                bodyFat = 18.7f,
                skeletalMuscle = 32.4f,
                visceralFatLevel = 8,
                inbodyScore = 72,
                bmr = 1680,
                analysis = "체지방이 줄고 근육이 늘었습니다."
            ),
            InbodyRecordEntity(
                measuredAt = "2026-09-02",
                weight = 79.1f,
                bodyFatPercent = 25.4f,
                skeletalMuscle = 32.0f,
                bmr = 1690
            )
        )

        val list = AiAskContext.inbody(records, trend = false)
        assertTrue(list.contains("measurements 2 (latest 2026-11-10)"))
        assertTrue(list.contains("weight 77.8kg"))
        assertTrue(list.contains("body fat 24.1% (18.7kg)"))
        assertTrue(list.contains("skeletal muscle 32.4kg"))
        assertTrue(list.contains("previous 2026-09-02"))
        assertTrue(list.contains("interpretation: 체지방이 줄고"))
        // 목록 화면에는 추이 문구가 없다
        assertFalse(list.contains("change 2026-09-02"))

        // 추이 화면은 처음→마지막 변화를 맨 위에 붙인다
        val trend = AiAskContext.inbody(records, trend = true)
        assertTrue(trend.contains("change 2026-09-02 -> 2026-11-10"))
        assertTrue(trend.contains("weight 79.1kg -> 77.8kg"))
    }

    @Test
    fun `체중 추이는 처음·마지막·최소·최대를 담는다`() {
        val points = listOf(
            record(date = "2026-11-10", weight = 77.8f),
            record(date = "2026-11-03", weight = 78.0f),
            record(date = "2026-10-28", weight = 77.4f),
            record(date = "2026-05-02", weight = 82.0f)
        )
        val text = AiAskContext.weightTrend(points)

        assertTrue(text.contains("changed weight points 4"))
        assertTrue(text.contains("2026-05-02 82.0kg -> 2026-11-10 77.8kg (-4.2kg)"))
        assertTrue(text.contains("min 77.4kg (2026-10-28), max 82.0kg (2026-05-02)"))
        assertTrue(text.contains("recent:"))
    }

    @Test
    fun `사용자가 적은 글의 줄바꿈은 눌러 한 줄로 만든다`() {
        // 줄바꿈으로 프롬프트의 다른 항목을 흉내 내지 못하게 (functions/aiContext.js 와 같은 이유)
        assertEquals("아침 밥", AiAskContext.clip("  아침\n밥  ", 20))
        assertTrue(AiAskContext.clip("가".repeat(100), 10).startsWith("가".repeat(10)))
        assertTrue(AiAskContext.clip("가".repeat(100), 10).endsWith("…"))
    }

    @Test
    fun `아주 긴 화면도 서버 상한 아래로 줄인다`() {
        // 31일 × 긴 식단 텍스트. 서버가 자르면 문장 중간이 끊기므로 앱에서 먼저 상한을 지킨다
        val records = (1..31).map {
            record(
                date = "2026-01-%02d".format(it),
                breakfast = "가".repeat(60),
                lunch = "나".repeat(60),
                dinner = "다".repeat(60),
                snack = "라".repeat(60)
            )
        }
        val text = AiAskContext.periodSummary(summary(), records, "2026년")
        assertTrue("길이 ${text.length}", text.length <= AiAskContext.TOTAL_MAX)
    }
}
