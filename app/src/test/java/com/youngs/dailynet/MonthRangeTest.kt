package com.youngs.dailynet

import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.ui.view.monthRangeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * 월 결산 화면에서 좌우로 넘길 수 있는 달 범위 검증.
 *
 * 범위는 "가장 오래된 기록이 있는 달 ~ 이번 달"이고, 처음 연 달은 어느 쪽이든 꼭 들어가야 한다.
 * 빠진 달이 있으면 페이저 번호가 달과 어긋나 엉뚱한 달이 열린다.
 */
class MonthRangeTest {

    private fun rec(date: String) = DailyRecordModel(date = date)

    private val thisMonth = Calendar.getInstance().let {
        String.format(Locale.US, "%04d-%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1)
    }

    @Test
    fun `빈 달도 빠뜨리지 않고 이어진다`() {
        val months = monthRangeOf(listOf(rec("2025-11-03"), rec("2026-02-10")), "2026-02")

        assertEquals("2025-11", months.first())
        assertEquals(thisMonth, months.last())
        // 12월, 1월은 기록이 없어도 사이에 있다
        assertTrue(months.containsAll(listOf("2025-11", "2025-12", "2026-01", "2026-02")))
        assertTrue(months.indexOf("2025-12") == months.indexOf("2025-11") + 1)
        assertTrue(months.indexOf("2026-01") == months.indexOf("2025-12") + 1)
    }

    @Test
    fun `오름차순이고 중복이 없다`() {
        val months = monthRangeOf(listOf(rec("2026-03-01"), rec("2026-03-02"), rec("2026-01-15")), "2026-03")

        assertEquals(months.sorted(), months)
        assertEquals(months.distinct(), months)
    }

    @Test
    fun `기록이 없으면 처음 연 달부터 이번 달까지`() {
        val months = monthRangeOf(emptyList(), "2026-01")

        assertEquals("2026-01", months.first())
        assertEquals(thisMonth, months.last())
    }

    @Test
    fun `처음 연 달이 기록보다 오래됐어도 범위에 든다`() {
        val months = monthRangeOf(listOf(rec("2026-05-01")), "2025-09")

        assertEquals("2025-09", months.first())
        assertTrue(months.contains("2026-05"))
    }

    @Test
    fun `연도가 바뀌는 자리가 맞다`() {
        val months = monthRangeOf(listOf(rec("2024-12-31")), "2025-01")

        assertEquals(months.indexOf("2024-12") + 1, months.indexOf("2025-01"))
    }
}
