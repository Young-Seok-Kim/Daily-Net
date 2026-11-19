package com.youngs.dailynet.ui.view

import androidx.annotation.StringRes
import com.youngs.dailynet.R
import com.youngs.dailynet.data.model.AiScreenTypes

/**
 * 화면마다 시트 위쪽에 띄우는 추천 질문.
 *
 * 처음 연 사람이 무엇을 물을 수 있는지 알게 하는 것이 목적이다. 누르면 그대로 전송된다.
 * 화면이 가진 데이터로 답할 수 있는 것만 넣는다 — 답이 "이 화면에는 없습니다"로 시작할 질문은 뺐다.
 *
 * 문구는 문자열 리소스로 두어 기기 언어를 따른다. Compose를 모르는 순수 함수라 테스트가 화면 없이 돈다.
 */
object AiAskQuestions {

    /** 화면 종류별 추천 질문 3개. AI 버튼이 없는 화면은 빈 목록 */
    @StringRes
    fun forScreen(type: String): List<Int> = when (type) {
        AiScreenTypes.DAILY_LIST -> listOf(
            R.string.ai_ask_q_daily_list_1,
            R.string.ai_ask_q_daily_list_2,
            R.string.ai_ask_q_daily_list_3
        )

        AiScreenTypes.DAILY_RECORD -> listOf(
            R.string.ai_ask_q_daily_record_1,
            R.string.ai_ask_q_daily_record_2,
            R.string.ai_ask_q_daily_record_3
        )

        AiScreenTypes.MONTH_REPORT -> listOf(
            R.string.ai_ask_q_month_1,
            R.string.ai_ask_q_month_2,
            R.string.ai_ask_q_month_3
        )

        AiScreenTypes.PERIOD_REPORT -> listOf(
            R.string.ai_ask_q_period_1,
            R.string.ai_ask_q_period_2,
            R.string.ai_ask_q_period_3
        )

        AiScreenTypes.INBODY_LIST -> listOf(
            R.string.ai_ask_q_inbody_list_1,
            R.string.ai_ask_q_inbody_list_2,
            R.string.ai_ask_q_inbody_list_3
        )

        AiScreenTypes.INBODY_TREND -> listOf(
            R.string.ai_ask_q_inbody_trend_1,
            R.string.ai_ask_q_inbody_trend_2,
            R.string.ai_ask_q_inbody_trend_3
        )

        AiScreenTypes.WEIGHT_TREND -> listOf(
            R.string.ai_ask_q_weight_1,
            R.string.ai_ask_q_weight_2,
            R.string.ai_ask_q_weight_3
        )

        else -> emptyList()
    }
}
