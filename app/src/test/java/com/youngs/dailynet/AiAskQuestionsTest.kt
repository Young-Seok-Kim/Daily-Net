package com.youngs.dailynet

import com.youngs.dailynet.data.model.AiScreenTypes
import com.youngs.dailynet.ui.view.AiAskQuestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 화면별 추천 질문.
 *
 * AI 버튼이 있는 일곱 화면은 모두 질문 3개를 가져야 한다(시안 2와 같은 모양).
 * 빈 목록이면 시트 위쪽이 휑해지고, 처음 연 사람이 무엇을 물을지 알 수 없다.
 */
class AiAskQuestionsTest {

    private val screens = listOf(
        AiScreenTypes.DAILY_LIST,
        AiScreenTypes.DAILY_RECORD,
        AiScreenTypes.MONTH_REPORT,
        AiScreenTypes.PERIOD_REPORT,
        AiScreenTypes.INBODY_LIST,
        AiScreenTypes.INBODY_TREND,
        AiScreenTypes.WEIGHT_TREND
    )

    @Test
    fun `일곱 화면 모두 추천 질문 3개를 가진다`() {
        screens.forEach { type ->
            assertEquals("$type", 3, AiAskQuestions.forScreen(type).size)
        }
    }

    @Test
    fun `화면마다 다른 질문을 준다`() {
        val all = screens.flatMap { AiAskQuestions.forScreen(it) }
        assertEquals("화면별 질문이 겹치면 그 화면의 데이터와 무관한 질문이 된다", all.size, all.toSet().size)
    }

    @Test
    fun `AI 버튼이 없는 화면에는 질문이 없다`() {
        for (type in listOf("unknown", "", "settings", "login")) {
            assertTrue(AiAskQuestions.forScreen(type).isEmpty())
        }
    }
}
