package com.youngs.dailynet.ui.view

import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.util.PassUsage
import java.util.Locale

/**
 * AI 버튼을 눌렀을 때 서버로 보낼 **화면 데이터 요약**.
 *
 * 서버는 이 값을 다시 계산하지 않고 그대로 인용한다(functions/askAiCoach.js). 그래서 화면이
 * 보여주는 값과 똑같이 적어야 하고, 화면에 없는 것은 넣지 않는다.
 *
 * 라벨은 짧은 영문 약어를 쓴다 — 앱 언어와 무관하게 같은 형식이 가고, 답변 언어는 서버가
 * 따로 지시받는다. 사용자가 적은 글(식단·메모)은 길이를 잘라 프롬프트가 한 화면 분량을 넘지 않게 한다.
 *
 * Compose를 모르는 순수 함수라 유닛 테스트가 화면 없이 돈다.
 */
object AiAskContext {

    /** 사용자가 적은 한 칸(식단 한 끼·메모)을 보낼 길이. 길면 앞부분만 보낸다 */
    const val FIELD_MAX = 60

    /** 분석 리포트 전문을 다 보내면 프롬프트가 커진다. 결론 부분이 앞에 있어 앞을 남긴다 */
    const val REPORT_MAX = 700

    /**
     * 서버 상한(MAX_CONTEXT_CHARS 8000자)보다 조금 아래. 넘겨 보내면 서버가 잘라 중간이 끊긴다.
     * 처음엔 1900이었는데 목록 7일 + 이용권 날짜 목록이면 닿을 수 있어 올렸다. 모델은 이 길이를 문제없이 읽는다
     */
    const val TOTAL_MAX = 7000

    /** 일별 줄을 넣을 최대 일수 (연 정산은 기록이 수백 일이다) */
    private const val MAX_DAY_LINES = 31

    /**
     * 화면과 무관하게 매 질문에 함께 보내는 최근 기록의 일수. 서버 askAiCoach.js의 RECENT_DAYS와 같아야 한다.
     * "어제·2주 전·이번 달" 질문이 이 안에 들면 서버가 저장소를 읽지 않고 한 번에 답한다.
     */
    const val RECENT_DAYS = 30

    /** 최근 기록 블록의 길이 상한. 서버 MAX_RECENT_CHARS(6000)보다 아래 */
    const val RECENT_MAX = 5500

    /**
     * 최근 창 이전의 날을 압축 줄로 보낼 최대 일수(2년). 그보다 오래된 날은 머리줄에 몇 일을 뺐는지만 적고,
     * 모델은 서버의 연 월별 집계 요청([NEED:months])으로 받는다.
     */
    const val OLDER_DAYS_MAX = 730

    /** 월별 요약 + 압축 블록 상한. 한 줄 30자 × 730일에 월별 줄 여유. 서버 MAX_OLDER_CHARS(28000)보다 아래 */
    const val OLDER_MAX = 26000

    /** 사용자 정보 블록 상한. 프로필·인바디 한 줄씩과 이용권 몇 줄. 서버 MAX_PROFILE_CHARS(1500)와 같다 */
    const val PROFILE_MAX = 1500

    /**
     * 사용자 정보 한 묶음: 프로필(키·성별·나이·시작 체중·하루 목표)·최근 인바디·운동 이용권. 화면과 무관하게 매 질문에 간다.
     *
     * BMI·적정 체중·체중당 단백질 같은 질문은 키·성별·나이 없이는 답을 못 한다. 하루 분석(AnalysisRequest)도 같은 값을
     * 보내고 있어 새로 나가는 정보는 아니다. 시작 체중은 "지금까지 얼마나 뺐어"의 근거다. 이용권은 전에 목록·정산 상세
     * 화면 데이터에만 붙었는데, 월간·체중 화면에서 물어도 알게 여기로 옮겨 항상 보낸다.
     *
     * @return 셋 다 없으면 null (블록을 보내지 않는다)
     */
    fun profile(
        profile: UserProfileEntity?,
        latestInbody: InbodyRecordEntity?,
        passes: List<PassUsage>,
        today: String
    ): String? {
        val lines = mutableListOf<String>()
        if (profile != null) {
            val parts = mutableListOf<String>()
            if (profile.height > 0f) parts += "height " + String.format(Locale.US, "%.0fcm", profile.height)
            parts += if (profile.isMale) "male" else "female"
            ageOf(profile.birthDate, today)?.let { parts += "age $it" }
            if (profile.initialWeight > 0f) parts += "start weight ${kg(profile.initialWeight)}"
            parts += "daily net goal -$DAILY_GOAL_KCAL kcal"
            lines += "profile: ${parts.joinToString(", ")}"
        }
        if (latestInbody != null) lines += "latest inbody ${latestInbody.measuredAt}: ${inbodyLine(latestInbody)}"
        passLine(passes)?.let { lines += it }
        if (lines.isEmpty()) return null
        return lines.joinToString("\n").take(PROFILE_MAX)
    }

    /** 생년월일(yyyy-MM-dd)로 만 나이. 비었거나 깨졌거나 말이 안 되는 값이면 null */
    private fun ageOf(birthDate: String, today: String): Int? = runCatching {
        java.time.Period.between(java.time.LocalDate.parse(birthDate.trim()), java.time.LocalDate.parse(today)).years
    }.getOrNull()?.takeIf { it in 1..120 }

    /**
     * 일일 기록 목록 화면. 전체 합계와 이용권만 담는다 — 날짜별 줄은 [recentDays]가 어느 화면에서나 붙이므로
     * 여기서 또 적지 않는다. 목록은 최신순(DESC)으로 들어온다.
     *
     * @param passes 운동 이용권 상태. 남은 횟수·기간을 한 줄로 붙인다 (없으면 생략)
     */
    fun dailyList(records: List<DailyRecordModel>, passes: List<PassUsage> = emptyList()): String {
        if (records.isEmpty()) return join(listOfNotNull("no records yet", passLine(passes)))
        val total = records.sumOf { it.netCalories }
        val average = total / records.size
        val lines = mutableListOf(
            "records ${records.size} days, total net ${kcal(total)}, avg net ${kcal(average)}/day"
        )
        passLine(passes)?.let { lines += it }
        return join(lines)
    }

    /**
     * 최근 [RECENT_DAYS]일의 하루 줄. 화면 종류와 상관없이 ViewModel이 질문마다 붙인다(별도 필드 recentData).
     *
     * 전에는 화면에 보이는 날짜만 보내고, 다른 날 질문은 서버가 질문 글에서 날짜를 정규식으로 뽑아 Firestore를 읽거나
     * 모델이 [NEED]로 되묻는 두 번 호출로 때웠다. 정규식은 "지난 주말"·"월요일에"를 놓치고 두 번 호출은 느리다.
     * 기록은 폰 Room에 다 있으니 최근 한 달을 그냥 같이 보내는 편이 빠르고 정확하다.
     *
     * 화면 데이터에 이미 든 날(월간 정산의 일별 줄, 정산 상세의 그 날)은 뺀다 — 같은 값을 두 번 보내면 길이만 든다.
     *
     * 머리줄에 **전체 기록의 첫 날짜와 일수**도 적는다. 모델은 프롬프트에 든 30일을 "가진 데이터 전부"로 알아서
     * "첫 기록이 언제야?"에 창의 첫 줄(29일 전)을 답했다(2026-11-20 실제 사례). 창 이전의 날은 [olderDays]가 압축 줄로 보낸다.
     *
     * @param records 전체 기록 (순서 무관). 오늘 이후 날짜는 뺀다
     * @param today 기기 날짜 yyyy-MM-dd. 기록도 기기 날짜로 매겨져 있다
     * @param screenData 이 화면의 요약(contextData). 겹치는 날을 가리는 데만 쓴다
     * @return 창 안에 기록이 하나도 없으면 null (블록을 보내지 않는다)
     */
    fun recentDays(records: List<DailyRecordModel>, today: String, screenData: String): String? {
        val from = windowStart(today) ?: return null
        val inWindow = records
            .filter { it.date in from..today }
            .sortedByDescending { it.date }
            .distinctBy { it.date }
        if (inWindow.isEmpty()) return null
        val (shown, fresh) = inWindow.partition { screenData.hasDay(it.date) }
        val past = records.filter { it.date <= today }.distinctBy { it.date }
        val first = past.minOf { it.date }
        val head = "recent $RECENT_DAYS days ($from..$today): ${inWindow.size} days recorded" +
            (if (shown.isEmpty()) "" else ", ${shown.size} already in screen data (omitted here)") +
            (if (first < from) "; all records ${past.size} days since $first (older days in compact block)" else "")
        val lines = mutableListOf(head)
        // 권장 탄단지를 한 줄. 하루 줄의 C/P/F를 견줄 기준이 없으면 "이번 주 단백질 충분했어?"에 답을 못 한다
        val summary = buildPeriodSummary(inWindow, inWindow.firstOrNull { it.weight > 0f }?.weight ?: 0f)
        if (summary.nutritionDays > 0) {
            lines += "recommended daily: C${summary.recCarb}g P${summary.recProtein}g F${summary.recFat}g " +
                "(compare with C/P/F on each day line; avg over ${summary.nutritionDays} analyzed days " +
                "C${summary.carbAvg.toInt()}g P${summary.proteinAvg.toInt()}g F${summary.fatAvg.toInt()}g: ${macroVerdicts(summary)})"
        }
        fresh.forEach { lines += dayLine(it) }
        return lines.joinToString("\n").take(RECENT_MAX)
    }

    /**
     * 전체 기록 요약: **월별 한 줄**(모든 달) + 최근 창 **이전**의 모든 날을 압축 한 줄("2026-05-22 -195 92.2kg food ex")로.
     * 식단 글은 뺀다.
     *
     * 월별 줄은 월간 정산 카드와 같은 계산([buildPeriodSummary])으로 기록일수·평균 순칼로리·탄단지 평균과 권장량·운동일수·
     * 체중 변화를 적는다. "올해 탄수화물 알맞게 먹었어?"에 모델이 "이 화면엔 탄단지가 없다"고 답한 실기기 사례(2026-11-20) —
     * 탄단지는 정산 상세·월간 화면에만 가고 있었다.
     *
     * `food`·`ex`는 그 날 식단·운동 **글이 있다**는 표시다. 내용이 없는 줄만 보고 모델이 "운동만 기록돼 있다"고 단정한
     * 실기기 사례(2026-11-20, 6월 5일)가 있어 식단 표시를 넣었다. 내용은 서버가 [NEED:day]로 읽어 준다.
     *
     * "적자가 제일 심했던 날", "체중이 제일 낮았던 때", "언제부터 기록했어" 처럼 전 기간을 훑어야 하는 질문을
     * 서버가 저장소를 읽지 않고 한 번에 답하게 한다. 특정 날의 식단 글이 필요하면 모델이 서버에 그 날·달을 요청한다.
     *
     * 화면 데이터와 겹쳐도 빼지 않는다 — 압축 줄은 싸고, 빼면 전 기간 목록에 구멍이 생겨 "가장"을 잘못 고른다.
     * [OLDER_DAYS_MAX]일(2년)까지만 담고 넘치는 오래된 날은 머리줄에 몇 일을 뺐는지 적는다.
     *
     * @return 오늘까지의 기록이 하나도 없으면 null (블록을 보내지 않는다)
     */
    fun olderDays(records: List<DailyRecordModel>, today: String): String? {
        val from = windowStart(today) ?: return null
        val past = records
            .filter { it.date <= today }
            .distinctBy { it.date }
            .sortedByDescending { it.date }
        if (past.isEmpty()) return null
        val lines = mutableListOf<String>()

        // 월별 요약. 권장 탄단지의 체중은 월간 카드와 같이 그 달 기록 체중, 없으면 최근 체중
        val latestWeight = past.firstOrNull { it.weight > 0f }?.weight ?: 0f
        val byMonth = past.groupBy { it.date.take(7) }.toSortedMap(compareByDescending { it })
        lines += "monthly (${byMonth.lastKey()}..${byMonth.firstKey()}): ${byMonth.size} months, " +
            "format: month days avg-net macros-avg(over analyzed days) vs rec(recommended daily) with verdict " +
            "(low <85%, ok, high >115% of rec) exercise weight-change"
        byMonth.forEach { (ym, recs) -> lines += monthLine(ym, buildPeriodSummary(recs, latestWeight)) }

        val older = past.filter { it.date < from }
        if (older.isNotEmpty()) {
            val shown = older.take(OLDER_DAYS_MAX)
            val omitted = older.size - shown.size
            lines += "older days (${shown.last().date}..${shown.first().date}): ${shown.size} days, " +
                "format: date net(kcal) weight food(has meal text) ex(has exercise)" +
                if (omitted > 0) "; $omitted older days before ${shown.last().date} omitted (yearly summary on request)" else ""
            shown.forEach { lines += compactLine(it) }
        }
        return lines.joinToString("\n").take(OLDER_MAX)
    }

    /**
     * "- 2026-11: 19 days, avg net -334 kcal, macros avg C172g P92g F48g over 15 days vs rec C210g P112g F55g:
     *  carb 82% low, protein 82% low, fat 87% ok, exercise 8 days, weight -0.8kg"
     *
     * 권장량 대비 판정은 앱이 계산해 적는다. 숫자만 주면 모델이 비교를 틀린다 — 단백질 84g vs 권장 151g을 "권장량을
     * 넘겼다"고 답한 실기기 사례(2026-11-20).
     */
    private fun monthLine(ym: String, s: PeriodSummary): String {
        val sb = StringBuilder("- $ym: ${s.recordDays} days, avg net ${kcal(s.netAverage)}")
        if (s.nutritionDays > 0) {
            sb.append(", macros avg C${s.carbAvg.toInt()}g P${s.proteinAvg.toInt()}g F${s.fatAvg.toInt()}g over ${s.nutritionDays} days")
            sb.append(" vs rec C${s.recCarb}g P${s.recProtein}g F${s.recFat}g: ${macroVerdicts(s)}")
        }
        sb.append(", exercise ${s.exerciseDays} days")
        s.weightDiff?.let { sb.append(", weight ${signedKg(it)}") }
        return sb.toString()
    }

    /** "carb 112% high, protein 56% low, fat 186% high". 권장량이 0이면 n/a */
    private fun macroVerdicts(s: PeriodSummary): String =
        "carb ${macroVerdict(s.carbAvg, s.recCarb)}, protein ${macroVerdict(s.proteinAvg, s.recProtein)}, " +
            "fat ${macroVerdict(s.fatAvg, s.recFat)}"

    private fun macroVerdict(avg: Float, rec: Int): String {
        if (rec <= 0) return "n/a"
        val pct = (avg / rec * 100f).toInt()
        val flag = when {
            pct < 85 -> "low"
            pct > 115 -> "high"
            else -> "ok"
        }
        return "$pct% $flag"
    }

    /** 최근 창의 첫 날(오늘 포함 [RECENT_DAYS]일). 오늘 날짜가 깨졌으면 null */
    private fun windowStart(today: String): String? =
        runCatching { java.time.LocalDate.parse(today).minusDays((RECENT_DAYS - 1).toLong()).toString() }.getOrNull()

    /** "2026-05-22 -195 92.2kg food ex". 체중 없으면 체중 칸이, 식단 글 없으면 food가, 운동 없으면 ex가 빠진다 */
    private fun compactLine(record: DailyRecordModel): String {
        val sb = StringBuilder(record.date).append(' ').append("%+d".format(Locale.US, record.netCalories))
        if (record.weight > 0f) sb.append(' ').append(kg(record.weight))
        val hasFood = listOf(record.breakfast, record.lunch, record.dinner, record.snack).any { it.isNotBlank() }
        if (hasFood) sb.append(" food")
        if (record.hasExercise || record.exercise.isNotBlank()) sb.append(" ex")
        return sb.toString()
    }

    /** 화면 요약에 그 날이 이미 있는가. 일별 줄은 "- yyyy-MM-dd ", 정산 상세는 첫 줄 "date yyyy-MM-dd" */
    private fun String.hasDay(date: String): Boolean =
        contains("- $date ") || lineSequence().any { it == "date $date" }

    /** 이용권 줄에 적을 간 날짜 수. 그보다 많으면 최근 것만 적고 앞을 줄인다 */
    private const val PASS_DATES_MAX = 60

    /** 키워드 없는 이용권의 운동한 날 목록에서 운동 글 한 칸의 길이 */
    private const val PASS_EXERCISE_MAX = 24

    /**
     * 운동 이용권. "이번 주 헬스 몇 번 가야 해?", "등록하고 나서 자주 갔어?" 같은 질문의 근거다.
     * 쓸 수 있는 이용권만. 남은 횟수·기간과 함께 **시작일과 간 날짜**를 적는다 — 횟수만 주면 모델이
     * "얼마나 자주"를 셀 수 없다(2026-11-19 실제 사례). 이용권마다 한 줄씩.
     */
    fun passLine(passes: List<PassUsage>): String? {
        val active = passes.filter { it.active }
        if (active.isEmpty()) return null
        val lines = active.map { u ->
            val p = u.pass
            val parts = mutableListOf<String>()
            parts += when {
                u.remaining != null -> "${u.used}/${p.totalCount} used, ${u.remaining} left"
                u.daysLeft != null -> "${u.daysLeft} days left"
                else -> "no end date"
            }
            if (p.startDate.isNotBlank()) parts += "since ${p.startDate}"
            if (p.endDate.isNotBlank()) parts += "until ${p.endDate}"
            if (p.keywords.isNotEmpty()) {
                val dates = u.usedDates
                val shown = dates.takeLast(PASS_DATES_MAX)
                val prefix = if (dates.size > shown.size) "... " else ""
                parts += if (dates.isEmpty()) "went 0 days (keyword ${p.keywords.joinToString("|")})"
                else "went ${dates.size} days: $prefix${shown.joinToString(", ")}"
            } else {
                // 키워드가 없으면 어떤 운동이 이 이용권인지 모른다. 기간 안의 운동한 날을 전부 넘겨
                // 모델이 이름으로 가려 세게 한다 ("오버컴 = 킥복싱"은 사용자가 질문에서 말해 준다)
                val days = u.exerciseDays
                val shown = days.takeLast(PASS_DATES_MAX)
                val prefix = if (days.size > shown.size) "... " else ""
                parts += if (days.isEmpty()) "no keyword, no exercise days since start"
                else "no keyword, exercise days since start ${days.size}: $prefix" +
                    shown.joinToString(", ") { (d, text) -> "$d ${clip(text, PASS_EXERCISE_MAX)}" }
            }
            "pass ${clip(p.name, 20)} (${if (p.isCount) "count" else "period"}): ${parts.joinToString(", ")}"
        }
        return lines.joinToString("\n")
    }

    /**
     * 하루 정산 상세. 그날 화면에 보이는 숫자·식단·리포트를 담는다.
     * @param passes 운동 이용권 상태 (입력 화면에서 걸음수 밑에 보이는 줄과 같은 값)
     */
    fun dailyRecord(record: DailyRecordModel, passes: List<PassUsage> = emptyList()): String {
        val lines = mutableListOf("date ${record.date}")
        if (record.weight > 0f) lines += "weight ${kg(record.weight)}"
        val intake = record.breakfastKcal + record.lunchKcal + record.dinnerKcal + record.snackKcal
        if (intake > 0 || record.exerciseKcal > 0) {
            lines += "net ${kcal(record.netCalories)} (in ${intake}kcal, out ${record.exerciseKcal}kcal)"
        } else {
            lines += "net ${kcal(record.netCalories)}"
        }
        if (record.hasNutritionData) {
            lines += "macros C${record.carbGram.toInt()}g P${record.proteinGram.toInt()}g " +
                "F${record.fatGram.toInt()}g, bmr ${record.bmr}kcal"
        }
        if (record.steps > 0) lines += "steps ${record.steps}"

        meal("B", record.breakfast, record.breakfastKcal)?.let { lines += it }
        meal("L", record.lunch, record.lunchKcal)?.let { lines += it }
        meal("D", record.dinner, record.dinnerKcal)?.let { lines += it }
        meal("S", record.snack, record.snackKcal)?.let { lines += it }
        if (record.exercise.isNotBlank()) {
            lines += "exercise: ${clip(record.exercise, FIELD_MAX)}"
                .let { if (record.exerciseKcal > 0) "$it (${record.exerciseKcal}kcal)" else it }
        }
        if (record.remark.isNotBlank()) lines += "remark: ${clip(record.remark, FIELD_MAX)}"
        passLine(passes)?.let { lines += it }
        if (record.analysisResult.isNotBlank()) {
            lines += "report: ${clip(record.analysisResult, REPORT_MAX)}"
        }
        return join(lines)
    }

    /**
     * 주·월·연 정산. 화면의 합계 카드와 같은 값을 쓰고, 일별 순칼로리를 붙인다.
     * 연처럼 기록이 많은 기간은 최근 31일만 넣고 몇 일을 생략했는지 적는다.
     */
    fun periodSummary(
        summary: PeriodSummary,
        records: List<DailyRecordModel>,
        title: String
    ): String {
        val lines = mutableListOf(
            "period $title",
            "recorded ${summary.recordDays} days, exercise ${summary.exerciseDays} days",
            "net total ${kcal(summary.netTotal)}, avg ${kcal(summary.netAverage)}/day"
        )
        summary.weightDiff?.let { lines += "weight change ${signedKg(it)}" }
        if (summary.nutritionDays > 0) {
            lines += "macros avg C${summary.carbAvg.toInt()}g P${summary.proteinAvg.toInt()}g " +
                "F${summary.fatAvg.toInt()}g over ${summary.nutritionDays} days"
            lines += "recommended C${summary.recCarb}g P${summary.recProtein}g F${summary.recFat}g"
        }
        if (records.isNotEmpty()) {
            lines += "daily:"
            records.take(MAX_DAY_LINES).forEach { lines += dayLine(it) }
            val omitted = records.size - MAX_DAY_LINES
            if (omitted > 0) lines += "... $omitted older days omitted"
        }
        return join(lines)
    }

    /**
     * 인바디 측정 이력·추이. 최근 측정과 그 이전 측정 하나를 나란히 두고, 저장된 해석을 붙인다.
     *
     * @param trend true면 추이 화면 — 처음과 마지막 측정의 변화를 맨 위에 적는다
     */
    fun inbody(records: List<InbodyRecordEntity>, trend: Boolean): String {
        if (records.isEmpty()) return "no inbody measurements"
        val sorted = records.sortedByDescending { it.measuredAt }
        val latest = sorted.first()
        val lines = mutableListOf("measurements ${sorted.size} (latest ${latest.measuredAt})")
        if (trend && sorted.size >= 2) {
            val first = sorted.last()
            lines += "change ${first.measuredAt} -> ${latest.measuredAt}: " +
                "weight ${kg(first.weight)} -> ${kg(latest.weight)}, " +
                "body fat ${pct(first.bodyFatPercent)} -> ${pct(latest.bodyFatPercent)}, " +
                "muscle ${kg(first.skeletalMuscle)} -> ${kg(latest.skeletalMuscle)}"
        }
        lines += "latest: ${inbodyLine(latest)}"
        sorted.drop(1).take(2).forEach { lines += "previous ${it.measuredAt}: ${inbodyLine(it)}" }
        // 구조(b68~)가 있으면 총평·판정·할 일만. 글 700자보다 짧고 모델이 읽기 쉽다
        val detail = latest.analysisDetail
        if (detail != null && detail.isRenderable) {
            val m = detail.metrics
            lines += "verdict: fat ${m.fatLevel}, muscle ${m.muscleLevel}, visceral ${m.visceralLevel}, bmi ${m.bmi}"
            if (detail.summary.isNotBlank()) lines += "summary: ${clip(detail.summary, FIELD_MAX * 4)}"
            if (detail.actions.isNotEmpty()) lines += "actions: ${detail.actions.joinToString(" / ")}"
        } else if (latest.analysis.isNotBlank()) {
            lines += "interpretation: ${clip(latest.analysis, REPORT_MAX)}"
        }
        return join(lines)
    }

    /**
     * 체중 추이. 화면과 같은 "값이 바뀐 날"만 넘겨받아(최신순) 처음·마지막·최소·최대와 최근 흐름을 적는다.
     */
    fun weightTrend(points: List<DailyRecordModel>): String {
        val weighed = points.filter { it.weight > 0f }.sortedByDescending { it.date }
        if (weighed.isEmpty()) return "no weight records"

        val latest = weighed.first()
        val oldest = weighed.last()
        val min = weighed.minByOrNull { it.weight }!!
        val max = weighed.maxByOrNull { it.weight }!!
        val change = latest.weight - oldest.weight

        val lines = mutableListOf(
            "changed weight points ${weighed.size}",
            "${oldest.date} ${kg(oldest.weight)} -> ${latest.date} ${kg(latest.weight)} " +
                "(${signedKg(change)})",
            "min ${kg(min.weight)} (${min.date}), max ${kg(max.weight)} (${max.date})",
            "recent:"
        )
        weighed.take(10).forEach { lines += "- ${it.date} ${kg(it.weight)}" }
        return join(lines)
    }

    /** 분석 리포트의 지표 한 줄. 값이 0인 항목은 빼서 빈 칸을 만들지 않는다 */
    private fun inbodyLine(record: InbodyRecordEntity): String {
        val parts = mutableListOf<String>()
        if (record.weight > 0f) parts += "weight ${kg(record.weight)}"
        if (record.bodyFatPercent > 0f) parts += "body fat ${pct(record.bodyFatPercent)} (${kg(record.bodyFat)})"
        if (record.skeletalMuscle > 0f) parts += "skeletal muscle ${kg(record.skeletalMuscle)}"
        if (record.visceralFatLevel > 0) parts += "visceral fat ${record.visceralFatLevel}"
        if (record.inbodyScore > 0) parts += "score ${record.inbodyScore}"
        if (record.bmr > 0) parts += "bmr ${record.bmr}kcal"
        return if (parts.isEmpty()) "no values" else parts.joinToString(", ")
    }

    /** "- 2026-11-19 net -420kcal, weight 78.2kg, ex yes | B: 계란밥 L: …" */
    private fun dayLine(record: DailyRecordModel): String {
        // 섭취·소모가 있으면 같이. "평균 얼마나 먹었어?"는 순칼로리만으로는 못 답한다
        val intake = record.breakfastKcal + record.lunchKcal + record.dinnerKcal + record.snackKcal
        val parts = mutableListOf(
            if (intake > 0 || record.exerciseKcal > 0) "net ${kcal(record.netCalories)} (in $intake, out ${record.exerciseKcal})"
            else "net ${kcal(record.netCalories)}"
        )
        if (record.weight > 0f) parts += "weight ${kg(record.weight)}"
        // 탄단지. "이번 주 단백질 충분했어?"는 식단 글만으로는 못 답한다
        if (record.hasNutritionData) {
            parts += "C${record.carbGram.toInt()}g P${record.proteinGram.toInt()}g F${record.fatGram.toInt()}g"
        }
        if (record.steps > 0) parts += "steps ${record.steps}"
        // 무슨 운동을 했는지까지. "이번 달 러닝 몇 번 했어?"에 답하려면 yes/no로는 모자란다
        val exercise = clip(record.exercise, FIELD_MAX)
        if (exercise.isNotEmpty()) parts += "exercise: $exercise"
        else if (record.hasExercise) parts += "exercise yes"

        val meals = mutableListOf<String>()
        clip(record.breakfast, FIELD_MAX).takeIf { it.isNotEmpty() }?.let { meals += "B: $it" }
        clip(record.lunch, FIELD_MAX).takeIf { it.isNotEmpty() }?.let { meals += "L: $it" }
        clip(record.dinner, FIELD_MAX).takeIf { it.isNotEmpty() }?.let { meals += "D: $it" }
        clip(record.snack, FIELD_MAX).takeIf { it.isNotEmpty() }?.let { meals += "S: $it" }

        val head = "- ${record.date} ${parts.joinToString(", ")}"
        return if (meals.isEmpty()) head else "$head | ${meals.joinToString(" ")}"
    }

    /** 한 끼 줄. 이름과 kcal이 모두 비어 있으면 null (빈 칸을 프롬프트에 남기지 않는다) */
    private fun meal(label: String, text: String, kcal: Int): String? {
        val name = clip(text, FIELD_MAX)
        if (name.isEmpty()) return null
        return "$label: $name" + if (kcal > 0) " (${kcal}kcal)" else ""
    }

    /** 줄바꿈·연속 공백을 한 칸으로 눌러 한 줄로 만든다. 사용자가 적은 글이 프롬프트 구조를 깨지 못하게 */
    fun clip(raw: String, max: Int): String {
        val pressed = raw.replace(Regex("\\s+"), " ").trim()
        return if (pressed.length <= max) pressed else pressed.take(max).trimEnd() + "…"
    }

    private fun join(lines: List<String>): String =
        lines.joinToString("\n").take(TOTAL_MAX)

    private fun kcal(value: Int): String = "%+d kcal".format(Locale.US, value)

    private fun kg(value: Float): String = String.format(Locale.US, "%.1fkg", value)

    private fun signedKg(value: Float): String =
        String.format(Locale.US, "%+.1fkg", value)

    private fun pct(value: Float): String = String.format(Locale.US, "%.1f%%", value)
}
