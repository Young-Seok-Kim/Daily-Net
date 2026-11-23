package com.youngs.dailynet

import com.youngs.dailynet.data.local.entity.ExercisePassEntity
import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
import com.youngs.dailynet.data.local.entity.UserProfileEntity
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
    fun `일일 목록은 합계만 담고 날짜별 줄은 넣지 않는다 (최근 30일 블록이 대신 간다)`() {
        val records = (1..12).map {
            record(date = "2026-11-%02d".format(it), net = -100 * it, weight = 70f + it / 10f)
        }
        val text = AiAskContext.dailyList(records)

        assertTrue(text.contains("records 12 days"))
        assertTrue(text.contains("total net -7800 kcal"))
        assertTrue(text.contains("avg net -650 kcal/day"))
        assertFalse(text.contains("- 2026-11-"))
    }

    @Test
    fun `최근 30일 블록은 오늘부터 29일 전까지만, 최신순으로 담는다`() {
        val today = "2026-11-19"
        val start = java.time.LocalDate.of(2026, 9, 1)
        val records = (0 until 90).map { record(date = start.plusDays(it.toLong()).toString(), net = -it) } +
            record(date = "2026-11-25", net = -999) // 미래(미리 적어 둔 날)는 뺀다

        val text = AiAskContext.recentDays(records, today, screenData = "")!!
        val lines = text.lines()

        // 창 밖에도 기록이 있으면 머리줄에 전체 범위를 적는다 — "첫 기록이 언제야?"에 창의 첫 줄을 답하지 않게
        assertEquals("recent 30 days (2026-10-21..2026-11-19): 30 days recorded; all records 80 days since 2026-09-01 (older days in compact block)", lines[0])
        assertEquals(31, lines.size)
        assertTrue(lines[1].startsWith("- 2026-11-19 "))
        assertTrue(lines[30].startsWith("- 2026-10-21 "))
        assertFalse(text.contains("2026-10-20"))
        assertFalse(text.contains("2026-11-25"))
    }

    @Test
    fun `최근 30일 블록이 상한을 넘으면 오래된 날부터 줄 단위로 빼고 머리줄에 적는다`() {
        val today = "2026-11-19"
        val start = java.time.LocalDate.of(2026, 10, 21)
        val meal = "a".repeat(60)
        // 끼니 넷에 운동까지 60자씩 — 한 줄 360자쯤, 30일이면 10800자로 상한(8500)을 넘어 7일쯤 빠진다
        val records = (0 until 30).map {
            record(date = start.plusDays(it.toLong()).toString(), breakfast = meal, lunch = meal, dinner = meal, snack = meal, exercise = meal)
        }

        val text = AiAskContext.recentDays(records, today, screenData = "")!!
        val lines = text.lines()
        assertTrue(text.length <= AiAskContext.RECENT_MAX)
        // 줄이 중간에 끊기지 않는다 — 모든 날 줄이 같은 꼴로 끝난다
        lines.drop(1).forEach { assertTrue(it, it.endsWith(" S: $meal")) }
        val dropped = 30 - (lines.size - 1)
        assertTrue("dropped=$dropped", dropped in 5..10)
        assertTrue(lines[0], lines[0].endsWith("; $dropped oldest days of this window dropped for size (not in compact block)"))
        assertTrue(lines[1].startsWith("- 2026-11-19 "))
        assertTrue(lines.last().startsWith("- ${start.plusDays(dropped.toLong())} "))

        // 상한 안이면 안내가 없다
        val small = AiAskContext.recentDays(records.take(3), today, screenData = "")!!
        assertFalse(small.contains("dropped"))
    }

    @Test
    fun `창 이전의 날은 날짜·순칼로리·체중·운동만 한 줄로 압축해 전부 보낸다`() {
        val today = "2026-11-19"
        val start = java.time.LocalDate.of(2026, 5, 22)
        val records = (0 until 182).map { i ->
            record(
                date = start.plusDays(i.toLong()).toString(), net = -195 + i,
                weight = if (i % 3 == 0) 92.2f else 0f,
                exercise = if (i % 2 == 0) "헬스 1시간" else "",
                breakfast = if (i == 1) "" else "식단 글은 압축 줄에 안 들어간다"
            )
        }

        val text = AiAskContext.olderDays(records, today)!!
        val lines = text.lines()
        // 앞에는 월별 요약(5월~11월 7달, 최신순). 탄단지가 없는 기록이라 macros 칸은 없다
        assertTrue(lines[0], lines[0].startsWith("monthly (2026-05..2026-11): 7 months, format: month days net-total total-from-that-month-to-today(pre-summed, use as-is) avg-net macros-avg"))
        // 합계·그 달부터 오늘까지 누적·평균. 11월은 11-01(-32)~11-19(-14) 19일 = -437, 5월은 5-22(-195)~5-31(-186) 10일 = -1905,
        // 5월부터 전체 182일 누적은 -195×182 + (0+…+181) = -19019
        assertTrue(lines[1], lines[1].startsWith("- 2026-11: 19 days, net total -437 kcal, total from 2026-11 to today -437 kcal, avg net -23 kcal, "))
        assertTrue(lines[7], lines[7].startsWith("- 2026-05: 10 days, net total -1905 kcal, total from 2026-05 to today -19019 kcal, avg net -190 kcal, "))
        assertFalse(text.contains("macros avg"))
        // 그 뒤 창(10-21~)보다 앞선 5-22 ~ 10-20 = 152일, 최신순
        val olderAt = lines.indexOfFirst { it.startsWith("older days") }
        assertEquals(8, olderAt)
        assertEquals("older days (2026-05-22..2026-10-20): 152 days, format: date net(kcal) weight food(has meal text) ex(has exercise)", lines[olderAt])
        assertEquals(olderAt + 153, lines.size)
        assertTrue(lines[olderAt + 1].startsWith("2026-10-20 "))
        assertEquals("2026-05-22 -195 92.2kg food ex", lines.last())
        // 5-23은 체중·식단·운동이 모두 없다
        assertEquals("2026-05-23 -194", lines[lines.size - 2])
        assertFalse(text.contains("식단 글"))
        assertFalse(text.contains("2026-10-21 "))

        // 창 안 기록뿐이면 월별 줄만 있고 older days는 없다. 기록이 아예 없거나 오늘 날짜가 깨졌으면 null
        val onlyRecent = AiAskContext.olderDays(listOf(record(date = "2026-11-01")), today)!!
        assertTrue(onlyRecent.startsWith("monthly (2026-11..2026-11): 1 months"))
        assertFalse(onlyRecent.contains("older days"))
        assertEquals(null, AiAskContext.olderDays(emptyList(), today))
        assertEquals(null, AiAskContext.olderDays(records, "오늘"))
    }

    @Test
    fun `사용자 정보는 프로필·최근 인바디·이용권을 한 묶음으로 보낸다`() {
        val today = "2026-11-19"
        val profile = UserProfileEntity(height = 176f, isMale = true, initialWeight = 98.6f, birthDate = "1987-03-15")
        val inbody = InbodyRecordEntity(measuredAt = "2026-11-10", weight = 77.8f, bodyFatPercent = 24.1f, bodyFat = 18.7f, skeletalMuscle = 32.4f, visceralFatLevel = 8, bmr = 1710)
        val gym = ExercisePassEntity(id = "a", name = "헬스", kind = ExercisePassEntity.KIND_COUNT,
            startDate = "2026-11-01", endDate = "2026-12-31", totalCount = 10, keyword = "헬스")
        val usages = listOf(computePassUsage(gym, listOf(record(date = "2026-11-15", exercise = "헬스")), today))

        val text = AiAskContext.profile(profile, inbody, usages, today)!!
        val lines = text.lines()
        assertEquals("profile: height 176cm, male, age 39, start weight 98.6kg, daily net goal -500 kcal", lines[0])
        assertEquals("latest inbody 2026-11-10: weight 77.8kg, body fat 24.1% (18.7kg), skeletal muscle 32.4kg, visceral fat 8, bmr 1710kcal", lines[1])
        assertTrue(lines[2].startsWith("pass 헬스 (count): 1/10 used, 9 left"))

        // 생년월일이 비었거나 키가 0이면 그 칸만 빠진다. 셋 다 없으면 null
        val bare = AiAskContext.profile(UserProfileEntity(initialWeight = 0f, birthDate = "", isMale = false), null, emptyList(), today)!!
        assertEquals("profile: female, daily net goal -500 kcal", bare)
        assertEquals(null, AiAskContext.profile(null, null, emptyList(), today))
    }

    @Test
    fun `하루 줄에는 섭취·소모 kcal과 걸음이 있으면 함께 적는다`() {
        val text = AiAskContext.recentDays(
            listOf(record(date = "2026-11-18", exercise = "웨이트 50분", exerciseKcal = 380, steps = 8200)),
            today = "2026-11-18", screenData = ""
        )!!
        assertTrue(text, text.contains("- 2026-11-18 net -420 kcal (in 0, out 380), steps 8200, exercise: 웨이트 50분"))
    }

    @Test
    fun `탄단지는 최근 30일 줄과 월별 요약에 권장량과 함께 들어간다`() {
        val today = "2026-11-19"
        val analyzed = record(date = "2026-11-18", weight = 78.2f, bmr = 1750, carb = 180f, protein = 95f, fat = 40f)
        val plain = record(date = "2026-11-17")

        val recent = AiAskContext.recentDays(listOf(analyzed, plain), today, "")!!
        assertTrue(recent, recent.contains("- 2026-11-18 net -420 kcal, weight 78.2kg, C180g P95g F40g"))
        assertFalse(recent.contains("2026-11-17 net -420 kcal, C"))
        // 권장량은 월간 카드와 같은 계산(bmr 1750 × 1.375 − 500 = 1906kcal, 단백질 78.2×1.6). 둘째 줄에 기준과 판정
        assertEquals(
            "recommended daily: C232g P125g F53g (compare with C/P/F on each day line; avg over 1 analyzed days C180g P95g F40g: carb 77% low, protein 76% low, fat 75% low)",
            recent.lines()[1]
        )

        // 월별 줄에도 권장량 대비 판정을 앱이 계산해 적는다 (모델이 84g vs 151g을 "넘겼다"고 읽은 사례)
        val monthly = AiAskContext.olderDays(listOf(analyzed, plain), today)!!
        assertTrue(monthly, monthly.contains("- 2026-11: 2 days, net total -840 kcal, total from 2026-11 to today -840 kcal, avg net -420 kcal, macros avg C180g P95g F40g over 1 days vs rec C232g P125g F53g: carb 77% low, protein 76% low, fat 75% low, exercise 0 days"))
        // 탄단지 없는 창에는 권장량 줄이 없다
        assertFalse(AiAskContext.recentDays(listOf(plain), today, "")!!.contains("recommended"))
    }

    @Test
    fun `압축 블록은 2년치까지만 담고 넘치는 오래된 날은 몇 일을 뺐는지 적는다`() {
        val today = "2026-11-19"
        val start = java.time.LocalDate.of(2023, 1, 1)
        val records = (0 until 1200).map { record(date = start.plusDays(it.toLong()).toString()) }
        val text = AiAskContext.olderDays(records, today)!!
        val lines = text.lines()
        val olderAt = lines.indexOfFirst { it.startsWith("older days") }
        assertEquals(olderAt + 1 + AiAskContext.OLDER_DAYS_MAX, lines.size)
        // 가장 오래된 줄은 최신 730일의 마지막
        val oldest = lines.last().substring(0, 10)
        assertTrue(lines[olderAt], lines[olderAt].contains("; ${1200 - AiAskContext.OLDER_DAYS_MAX} older days before $oldest omitted (yearly summary on request)"))
        assertTrue(text.length <= AiAskContext.OLDER_MAX)
    }

    @Test
    fun `최근 30일 줄에는 무슨 운동을 했는지 글자가 들어간다`() {
        val text = AiAskContext.recentDays(listOf(
            record(date = "2026-11-02", exercise = "러닝 30분"),
            record(date = "2026-11-01", exercise = "", exerciseKcal = 0)
        ), today = "2026-11-02", screenData = "")!!
        assertTrue(text.contains("- 2026-11-02 net -420 kcal, exercise: 러닝 30분"))
        assertFalse(text.contains("2026-11-01 net -420 kcal, exercise"))
    }

    @Test
    fun `화면 데이터에 이미 든 날은 최근 30일 블록에서 뺀다`() {
        val today = "2026-11-19"
        val records = listOf(record(date = "2026-11-19"), record(date = "2026-11-18"), record(date = "2026-11-17"))

        // 정산 상세: 첫 줄 "date yyyy-MM-dd"
        val detail = AiAskContext.recentDays(records, today, AiAskContext.dailyRecord(records[0]))!!
        // 창 밖 기록이 없으면 전체 범위 문구도 없다
        assertEquals("recent 30 days (2026-10-21..2026-11-19): 3 days recorded, 1 already in screen data (omitted here)", detail.lines()[0])
        assertFalse(detail.contains("- 2026-11-19 "))
        assertTrue(detail.contains("- 2026-11-18 "))

        // 월간 정산: 일별 줄 "- yyyy-MM-dd "
        val month = AiAskContext.recentDays(records, today, AiAskContext.periodSummary(summary(), records, "11월"))!!
        assertEquals(1, month.lines().size)
        assertTrue(month.contains("3 already in screen data"))

        // 창 안에 기록이 없거나 오늘 날짜가 깨졌으면 블록을 보내지 않는다
        assertEquals(null, AiAskContext.recentDays(listOf(record(date = "2026-01-01")), today, ""))
        assertEquals(null, AiAskContext.recentDays(records, "오늘", ""))
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
            startDate = "2026-11-01", endDate = "2026-11-20", keyword = "필라테스")
        val expired = ExercisePassEntity(id = "c", name = "수영", kind = ExercisePassEntity.KIND_PERIOD,
            startDate = "2026-10-01", endDate = "2026-10-31")
        val usages = listOf(gym, pilates, expired).map { computePassUsage(it, records, today) }

        val line = AiAskContext.passLine(usages)!!
        assertEquals(
            "pass 헬스 (count): 2/10 used, 8 left, since 2026-11-01, until 2026-12-31, went 2 days: 2026-11-15, 2026-11-18\n" +
                "pass 필라테스 (period): 2 days left, since 2026-11-01, until 2026-11-20, went 0 days (keyword 필라테스)",
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
    fun `기간제 이용권도 키워드가 있으면 간 날짜를 적고, 많으면 최근 60일만`() {
        val today = "2026-11-19"
        val kick = ExercisePassEntity(id = "k", name = "오버컴", kind = ExercisePassEntity.KIND_PERIOD,
            startDate = "2026-08-01", endDate = "2026-12-31", keyword = "킥복싱")
        val start = java.time.LocalDate.of(2026, 8, 1)
        val records = (0 until 90).map { record(date = start.plusDays(it.toLong()).toString(), exercise = "킥복싱 50분") } +
            record(date = "2026-11-01", exercise = "러닝")
        val line = AiAskContext.passLine(listOf(computePassUsage(kick, records, today)))!!
        // 90일 중 최근 60일(09-30~10-29)만, 앞은 "..."
        assertTrue(line, line.startsWith("pass 오버컴 (period): 43 days left, since 2026-08-01, until 2026-12-31, went 90 days: ... 2026-08-31"))
        assertTrue(line.endsWith("2026-10-29"))
        assertFalse(line.contains("2026-08-30,"))

        // 키워드가 있는데 한 번도 안 갔으면 0으로 밝힌다 (모델이 "기록 없음"으로 오해하지 않게)
        val none = AiAskContext.passLine(listOf(computePassUsage(kick, emptyList(), today)))!!
        assertTrue(none.contains("went 0 days (keyword 킥복싱)"))
    }

    @Test
    fun `키워드 없는 이용권은 기간 안에 운동한 날을 전부 넘긴다`() {
        // 2026-11-19 실제 사례: 오버컴(기간제)에 키워드가 없어 킥복싱 날짜가 하나도 안 갔다
        val today = "2026-11-19"
        val overcome = ExercisePassEntity(id = "o", name = "오버컴", kind = ExercisePassEntity.KIND_PERIOD,
            startDate = "2026-10-13", endDate = "2027-02-12")
        val records = listOf(
            record(date = "2026-10-10", exercise = "킥복싱"),             // 시작 전 → 제외
            record(date = "2026-10-13", exercise = "킥복싱 수업 50분, 샌드백 자세 연습 및 매달리기"),
            record(date = "2026-10-20", exercise = ""),                   // 운동 없음 → 제외
            record(date = "2026-11-02", exercise = "러닝 30분"),
            record(date = "2026-11-17", exercise = "킥복싱 수업 50분")
        )
        val line = AiAskContext.passLine(listOf(computePassUsage(overcome, records, today)))!!
        assertTrue(line, line.startsWith("pass 오버컴 (period): 86 days left, since 2026-10-13, until 2027-02-12, no keyword, exercise days since start 3: 2026-10-13 킥복싱 수업 50분, 샌드백"))
        assertTrue(line, line.contains("…, 2026-11-02 러닝 30분, 2026-11-17 킥복싱 수업 50분"))
        assertFalse(line.contains("2026-10-10"))
        assertFalse(line.contains("2026-10-20"))
        // 목록 요약에도 붙는다 (일별 줄은 목록 요약에 없다 — 최근 30일 블록이 따로 간다)
        val list = AiAskContext.dailyList(records.sortedByDescending { it.date }, listOf(computePassUsage(overcome, records, today)))
        assertTrue(list.contains("pass 오버컴"))
        assertFalse(list.contains("- 2026-11-17"))
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
