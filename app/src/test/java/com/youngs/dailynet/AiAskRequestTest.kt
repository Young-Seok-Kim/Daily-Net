package com.youngs.dailynet

import com.google.gson.Gson
import com.youngs.dailynet.data.model.AiActivity
import com.youngs.dailynet.data.model.AiAskRequest
import com.youngs.dailynet.data.model.AiAskTurn
import com.youngs.dailynet.data.model.AiContext
import com.youngs.dailynet.data.model.AiGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 질문 요청이 서버가 읽는 모양으로 나가는지.
 *
 * 서버(functions/askAiCoach.js)는 화면 종류를 정해진 값과 비교하고, AI 참고 정보는 **코드**
 * (lose·sedentary)로 찾는다. Gson은 그냥 두면 enum 이름(LOSE)을 쓰므로, 여기서 실제 JSON을 확인한다.
 * 이게 틀어지면 벌어지는 일이 조용하다 — 오류 없이 참고 정보만 전달되지 않는다.
 */
class AiAskRequestTest {

    private fun request(context: AiContext? = null) = AiAskRequest(
        language = "ko-KR",
        timeZone = "Asia/Seoul",
        screenType = "month_report",
        screenTitle = "11월 월간 정산",
        contextData = "net total -6840 kcal",
        aiContext = context,
        question = "3주차 적자가 왜 줄었어?",
        history = listOf(
            AiAskTurn(role = "user", text = "지난주는 어땠어?"),
            AiAskTurn(role = "assistant", text = "적자였습니다.")
        )
    )

    @Test
    fun `화면 종류는 서버가 아는 값 그대로 나간다`() {
        val json = Gson().toJson(request())
        assertTrue(json.contains("\"screenType\":\"month_report\""))
        assertTrue(json.contains("\"screenTitle\":\"11월 월간 정산\""))
        assertTrue(json.contains("\"contextData\":\"net total -6840 kcal\""))
        assertTrue(json.contains("\"question\":\"3주차 적자가 왜 줄었어?\""))
        assertTrue(json.contains("\"role\":\"user\""))
    }

    @Test
    fun `AI 참고 정보는 코드(lose·sedentary)로 나간다`() {
        val json = Gson().toJson(
            request(AiContext(goal = AiGoal.LOSE, activity = AiActivity.SEDENTARY, note = "야식 줄이기"))
        )
        assertTrue(json, json.contains("\"goal\":\"lose\""))
        assertTrue(json, json.contains("\"activity\":\"sedentary\""))
        assertTrue(json, json.contains("\"note\":\"야식 줄이기\""))
        // enum 이름이 그대로 새어 나가면 서버가 모르는 코드로 보고 버린다
        assertFalse(json.contains("LOSE"))
        assertFalse(json.contains("SEDENTARY"))
    }

    @Test
    fun `대화가 없으면 빈 배열, 참고 정보가 없으면 필드를 뺀다`() {
        val json = Gson().toJson(request().copy(history = emptyList(), aiContext = null))
        // 빈 목록은 서버가 그냥 "이어 온 대화 없음"으로 읽는다
        assertTrue(json, json.contains("\"history\":[]"))
        // null은 Gson이 필드째로 빼므로 서버가 받는 본문에 키 자체가 없다
        assertFalse(json.contains("aiContext"))
    }

    @Test
    fun `질문 길이 상한은 서버와 같은 500자다`() {
        // 서버 MAX_QUESTION_CHARS와 다르면 앱에서 통과한 질문이 서버에서 잘린다
        assertEquals(500, com.youngs.dailynet.data.model.AiScreenTypes.MAX_QUESTION)
    }
}
