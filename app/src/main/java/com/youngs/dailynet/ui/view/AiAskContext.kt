package com.youngs.dailynet.ui.view

import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
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

    /** 서버 상한(2000자)보다 조금 아래. 넘겨 보내면 서버가 잘라 중간이 끊긴다 */
    const val TOTAL_MAX = 1900

    /** 일별 줄을 넣을 최대 일수 (연 정산은 기록이 수백 일이다) */
    private const val MAX_DAY_LINES = 31

    /**
     * 일일 기록 목록 화면. 최근 7일의 순칼로리·체중·식단·운동을 짧게 담고 전체 합계를 붙인다.
     * 목록은 최신순(DESC)으로 들어온다.
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
        records.take(7).forEach { lines += dayLine(it) }
        passLine(passes)?.let { lines += it }
        return join(lines)
    }

    /** 이용권 줄에 적을 간 날짜 수. 그보다 많으면 최근 것만 적고 앞을 줄인다 */
    private const val PASS_DATES_MAX = 20

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
        val parts = mutableListOf("net ${kcal(record.netCalories)}")
        if (record.weight > 0f) parts += "weight ${kg(record.weight)}"
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
