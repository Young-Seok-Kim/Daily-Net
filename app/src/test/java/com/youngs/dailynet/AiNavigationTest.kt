package com.youngs.dailynet

import com.youngs.dailynet.data.model.AiScreenTypes
import com.youngs.dailynet.util.AiNavigation
import com.youngs.dailynet.util.AiNavigation.Route
import com.youngs.dailynet.util.AiNavigation.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AI 답변의 "이 화면으로" 표식을 MainActivity 화면 이름으로 바꾸는 규칙.
 *
 * 서버가 주는 화면 종류 일곱 가지가 모두 어딘가로 가야 하고, 날짜가 필요한 화면은 인자가 없을 때
 * 오늘로 열려야 한다. 모르는 값은 null이라 버튼이 아무 일도 하지 않는다.
 */
class AiNavigationTest {

    private val today = "2026-11-19"
    private val week = "202647"

    private fun go(screen: String, arg: String? = null) =
        AiNavigation.resolve(Target(screen, arg), today, week)

    @Test
    fun `일곱 화면 모두 갈 곳이 있다`() {
        listOf(
            AiScreenTypes.DAILY_LIST, AiScreenTypes.DAILY_RECORD, AiScreenTypes.MONTH_REPORT,
            AiScreenTypes.PERIOD_REPORT, AiScreenTypes.INBODY_LIST, AiScreenTypes.INBODY_TREND,
            AiScreenTypes.WEIGHT_TREND
        ).forEach { type ->
            assertEquals(type, true, go(type) != null)
        }
    }

    @Test
    fun `날짜 인자가 없으면 오늘·이번 달로 연다`() {
        assertEquals(Route("detail", today), go(AiScreenTypes.DAILY_RECORD))
        assertEquals(Route("month", "2026-11"), go(AiScreenTypes.MONTH_REPORT))
        assertEquals(Route("weight", today), go(AiScreenTypes.WEIGHT_TREND))
    }

    @Test
    fun `서버가 검증한 날짜 인자는 그대로 쓴다`() {
        assertEquals(Route("detail", "2026-11-18"), go(AiScreenTypes.DAILY_RECORD, "2026-11-18"))
        assertEquals(Route("month", "2026-10"), go(AiScreenTypes.MONTH_REPORT, "2026-10"))
    }

    @Test
    fun `주·연 정산은 이번 주로 연다`() {
        assertEquals(Route("period", "week:$week"), go(AiScreenTypes.PERIOD_REPORT))
    }

    @Test
    fun `날짜가 없는 화면은 인자를 무시한다`() {
        assertEquals(Route("main"), go(AiScreenTypes.DAILY_LIST, "2026-11-18"))
        assertEquals(Route("inbody"), go(AiScreenTypes.INBODY_LIST))
        assertEquals(Route("inbody_trend"), go(AiScreenTypes.INBODY_TREND))
    }

    @Test
    fun `모르는 화면은 null`() {
        assertNull(go("settings"))
        assertNull(go(""))
    }
}
