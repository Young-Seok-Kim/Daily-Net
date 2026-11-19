package com.youngs.dailynet

import com.youngs.dailynet.data.local.entity.ExercisePassEntity
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.util.computePassUsage
import com.youngs.dailynet.util.daysInclusive
import com.youngs.dailynet.util.passMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 운동 이용권의 자동 차감 규칙.
 *
 * 횟수권은 저장된 "쓴 횟수"가 없고 기록에서 센다. 그래서 확인할 것은
 *  - 키워드가 든 날만, 같은 날은 1회로, 기간 밖은 안 센다
 *  - 수동 보정이 더해지고 0 밑으로 안 내려간다
 *  - 기간제는 남은 날을 오늘 포함으로 세고 지나면 0
 */
class PassUsageTest {

    private fun rec(date: String, exercise: String) =
        DailyRecordModel(date = date, exercise = exercise, hasExercise = exercise.isNotBlank())

    private val pt = ExercisePassEntity(
        id = "pt", name = "PT", kind = ExercisePassEntity.KIND_COUNT,
        startDate = "2026-11-01", endDate = "", totalCount = 10, keyword = "PT, 피티"
    )

    @Test
    fun countPassCountsKeywordDaysInsideRange() {
        val records = listOf(
            rec("2026-10-31", "PT 1시간"),          // 시작 전
            rec("2026-11-02", "pt 50분, 러닝"),      // 대소문자 무시
            rec("2026-11-03", "피티"),               // 두 번째 키워드
            rec("2026-11-04", "헬스 1시간"),          // 키워드 없음
            rec("2026-11-05", "")
        )
        val usage = computePassUsage(pt, records, "2026-11-17")
        assertEquals(listOf("2026-11-02", "2026-11-03"), usage.usedDates)
        assertEquals(2, usage.used)
        assertEquals(8, usage.remaining)
        assertNull(usage.daysLeft)
        assertTrue(usage.active)
    }

    @Test
    fun adjustIsAddedAndClampedAndExhaustion() {
        val records = listOf(rec("2026-11-02", "PT"), rec("2026-11-03", "PT"))
        assertEquals(3, computePassUsage(pt.copy(adjust = 1), records, "2026-11-17").used)
        assertEquals(0, computePassUsage(pt.copy(adjust = -5), records, "2026-11-17").used)

        val done = computePassUsage(pt.copy(totalCount = 2), records, "2026-11-17")
        assertEquals(0, done.remaining)
        assertTrue(done.exhausted)
        assertFalse(done.active)
    }

    @Test
    fun periodPassCountsDaysLeftIncludingToday() {
        val gym = ExercisePassEntity(
            id = "gym", name = "헬스", kind = ExercisePassEntity.KIND_PERIOD,
            startDate = "2026-11-01", endDate = "2026-11-30"
        )
        val u = computePassUsage(gym, emptyList(), "2026-11-17")
        assertEquals(14, u.daysLeft)
        assertEquals(30, u.totalDays)
        assertNull(u.remaining)
        assertTrue(u.usedDates.isEmpty())
        assertTrue(u.active)

        // 기간제도 키워드가 있으면 간 날을 센다. 차감은 없으니 remaining은 그대로 null
        val withKeyword = gym.copy(keyword = "헬스")
        val records = listOf(
            DailyRecordModel(date = "2026-11-02", exercise = "헬스 1시간"),
            DailyRecordModel(date = "2026-11-05", exercise = "러닝"),
            DailyRecordModel(date = "2026-11-06", exercise = "헬스")
        )
        val counted = computePassUsage(withKeyword, records, "2026-11-17")
        assertEquals(listOf("2026-11-02", "2026-11-06"), counted.usedDates)
        assertNull(counted.remaining)
        assertTrue(counted.active)
        // 운동한 날 전부(키워드와 무관)는 따로 둔다. 키워드 없는 이용권이 AI 질문에 쓴다
        assertEquals(listOf("2026-11-02" to "헬스 1시간", "2026-11-05" to "러닝", "2026-11-06" to "헬스"), counted.exerciseDays)
        assertEquals(counted.exerciseDays, computePassUsage(gym, records, "2026-11-17").exerciseDays)

        val over = computePassUsage(gym, emptyList(), "2026-12-01")
        assertEquals(0, over.daysLeft)
        assertTrue(over.expired)

        // 아직 시작 전이면 전체 기간이 남은 날이다
        val early = computePassUsage(gym, emptyList(), "2026-10-20")
        assertEquals(30, early.daysLeft)
        assertTrue(early.notStarted("2026-10-20"))
    }

    @Test
    fun countPassWithExpiryIgnoresLaterRecordsAndExpires() {
        val limited = pt.copy(endDate = "2026-11-10")
        val records = listOf(rec("2026-11-05", "PT"), rec("2026-11-12", "PT"))
        val u = computePassUsage(limited, records, "2026-11-08")
        assertEquals(1, u.used)
        assertEquals(3, u.daysLeft)
        assertFalse(u.expired)
        assertTrue(computePassUsage(limited, records, "2026-11-11").expired)
    }

    @Test
    fun helpers() {
        assertTrue(passMatches(pt, "오늘 PT 받음"))
        assertFalse(passMatches(pt, "헬스"))
        assertFalse(passMatches(pt.copy(keyword = ""), "PT"))
        assertEquals(1, daysInclusive("2026-11-17", "2026-11-17"))
        assertEquals(0, daysInclusive("2026-11-18", "2026-11-17"))
        assertEquals(0, daysInclusive("", "2026-11-17"))
    }
}
