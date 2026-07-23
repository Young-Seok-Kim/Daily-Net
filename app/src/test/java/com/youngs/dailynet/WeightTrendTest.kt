package com.youngs.dailynet

import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.ui.view.changedWeightPoints
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 몸무게 추이 그래프에서 "이전 날짜 대비 값이 바뀐 날"만 남기는 로직 검증.
 * 입력은 실제 화면과 동일하게 최신순(DESC)으로 준다.
 */
class WeightTrendTest {

    private fun rec(date: String, weight: Float) =
        DailyRecordModel(date = date, weight = weight)

    @Test
    fun `같은 몸무게가 이어지면 최초 기록과 바뀐 날만 남는다`() {
        // 시간순: 01=55.3, 02=55.3, 03=55.3, 04=54.0, 05=54.0
        val records = listOf(
            rec("2026-01-05", 54.0f),
            rec("2026-01-04", 54.0f),
            rec("2026-01-03", 55.3f),
            rec("2026-01-02", 55.3f),
            rec("2026-01-01", 55.3f)
        )

        val result = changedWeightPoints(records)

        // 55.3의 최초 기록(01-01)과 54.0으로 바뀐 날(01-04)만 남아야 한다
        assertEquals(listOf("2026-01-04", "2026-01-01"), result.map { it.date })
        assertEquals(listOf(54.0f, 55.3f), result.map { it.weight })
    }

    @Test
    fun `몸무게를 입력하지 않은 날(0kg)은 제외된다`() {
        val records = listOf(
            rec("2026-01-03", 54.0f),
            rec("2026-01-02", 0f),
            rec("2026-01-01", 55.3f)
        )

        val result = changedWeightPoints(records)

        assertEquals(listOf("2026-01-03", "2026-01-01"), result.map { it.date })
    }

    @Test
    fun `값이 올랐다 내려가면 각 변화 지점이 모두 남는다`() {
        // 시간순: 01=55.0, 02=56.0, 03=56.0, 04=54.5
        val records = listOf(
            rec("2026-01-04", 54.5f),
            rec("2026-01-03", 56.0f),
            rec("2026-01-02", 56.0f),
            rec("2026-01-01", 55.0f)
        )

        val result = changedWeightPoints(records)

        assertEquals(listOf("2026-01-04", "2026-01-02", "2026-01-01"), result.map { it.date })
    }

    @Test
    fun `전부 같은 몸무게면 최초 기록 한 점만 남는다`() {
        val records = listOf(
            rec("2026-01-03", 55.0f),
            rec("2026-01-02", 55.0f),
            rec("2026-01-01", 55.0f)
        )

        val result = changedWeightPoints(records)

        assertEquals(listOf("2026-01-01"), result.map { it.date })
    }

    @Test
    fun `기록이 없으면 빈 목록`() {
        assertEquals(emptyList<String>(), changedWeightPoints(emptyList()).map { it.date })
    }
}
