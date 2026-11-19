package com.youngs.dailynet.util

import com.youngs.dailynet.data.local.entity.ExercisePassEntity
import com.youngs.dailynet.data.model.DailyRecordModel
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 이용권 한 장의 현재 상태. 저장하지 않고 기록에서 매번 계산한다 ([computePassUsage]).
 *
 * @param usedDates 키워드가 든 날들(오름차순). 횟수권은 이 수가 쓴 횟수다.
 *   기간제도 키워드가 있으면 센다 — 차감은 없지만 "등록하고 몇 번 갔나"를 AI 질문이 답하는 근거다
 * @param used 쓴 횟수 = 센 날 수 + 수동 보정. 0 밑으로는 안 내려간다
 * @param remaining 남은 횟수. 기간제는 null
 * @param daysLeft 오늘을 포함해 남은 날. 종료일이 없으면 null, 지났으면 0
 */
data class PassUsage(
    val pass: ExercisePassEntity,
    val usedDates: List<String>,
    val used: Int,
    val remaining: Int?,
    val daysLeft: Int?,
    val totalDays: Int?,
    val expired: Boolean,
    val exhausted: Boolean
) {
    /** 아직 쓸 수 있는 이용권인지 */
    val active: Boolean get() = !expired && !exhausted

    /** 아직 시작 전인지 (시작일이 오늘보다 뒤) */
    fun notStarted(today: String): Boolean = pass.startDate.isNotBlank() && pass.startDate > today
}

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/** 두 날짜 사이의 일수(양 끝 포함). 형식이 틀리거나 뒤집혔으면 0 */
fun daysInclusive(start: String, end: String): Int {
    if (start.isBlank() || end.isBlank() || end < start) return 0
    val a = runCatching { DATE_FMT.parse(start) }.getOrNull() ?: return 0
    val b = runCatching { DATE_FMT.parse(end) }.getOrNull() ?: return 0
    return ((b.time - a.time) / 86_400_000L).toInt() + 1
}

/** 운동 칸 글에 이 이용권의 키워드가 하나라도 들어 있는지. 대소문자는 가리지 않는다 */
fun passMatches(pass: ExercisePassEntity, exerciseText: String): Boolean {
    if (exerciseText.isBlank()) return false
    return pass.keywords.any { exerciseText.contains(it, ignoreCase = true) }
}

/** 그 날이 이용권 기간 안인지. 종료일이 없으면 시작일 이후 전부 */
fun passCovers(pass: ExercisePassEntity, date: String): Boolean =
    (pass.startDate.isBlank() || date >= pass.startDate) && (pass.endDate.isBlank() || date <= pass.endDate)

/**
 * 이용권 상태 계산.
 *
 * 횟수권: 기간 안의 기록 중 운동 칸에 키워드가 든 날을 1회로 센다. 같은 날 두 번 적어도 1회다.
 * 기간제: 오늘 기준 남은 날을 센다. 키워드가 있으면 간 날도 같이 세어 둔다(차감은 없다).
 */
fun computePassUsage(pass: ExercisePassEntity, records: List<DailyRecordModel>, today: String): PassUsage {
    val usedDates = if (pass.keywords.isNotEmpty()) {
        records.asSequence()
            .filter { passCovers(pass, it.date) && passMatches(pass, it.exercise) }
            .map { it.date }
            .distinct()
            .sorted()
            .toList()
    } else emptyList()

    val used = (usedDates.size + pass.adjust).coerceAtLeast(0)
    val remaining = if (pass.isCount) (pass.totalCount - used).coerceAtLeast(0) else null

    val daysLeft = when {
        pass.endDate.isBlank() -> null
        today > pass.endDate -> 0
        else -> daysInclusive(maxOf(today, pass.startDate), pass.endDate)
    }
    val totalDays = if (pass.startDate.isNotBlank() && pass.endDate.isNotBlank()) {
        daysInclusive(pass.startDate, pass.endDate)
    } else null

    return PassUsage(
        pass = pass,
        usedDates = usedDates,
        used = used,
        remaining = remaining,
        daysLeft = daysLeft,
        totalDays = totalDays,
        expired = pass.endDate.isNotBlank() && today > pass.endDate,
        exhausted = pass.isCount && remaining == 0
    )
}
