package com.youngs.dailynet.data.model

import com.google.gson.annotations.SerializedName

/**
 * 전역 AI 질문(askAiCoach)의 요청·응답.
 *
 * 앱의 조회 화면 우하단 AI 버튼이 띄우는 시트가 쓴다. 화면 데이터를 앱이 요약해 보내고
 * 서버는 그 값을 근거로 **글 답변**만 만든다 (functions/askAiCoach.js).
 *
 * 세션 안에서만 이어지는 대화라 서버에 저장하지 않는다. 지난 대화는 앱이 [history]로 다시 보낸다.
 */
data class AiAskRequest(
    /** 답변을 받을 언어 (BCP-47). 서버는 앞부분만 본다 */
    val language: String,
    /** 지금 보고 있는 화면의 종류. [AiScreenTypes]의 값 */
    val screenType: String,
    /** 뱃지에 그대로 보이는 화면 이름 ("11월 월간 정산") */
    val screenTitle: String,
    /** 화면이 보여주는 값의 요약. 서버는 이 값을 다시 계산하지 않고 인용만 한다 */
    val contextData: String,
    /** 설정 > AI 분석 참고 정보(목표·활동량·메모). 비어 있으면 null → 필드를 빼고 보낸다 */
    val aiContext: AiContext? = null,
    val question: String,
    /** 이전 왕복. 최근 것부터 서버가 6개까지만 쓴다 */
    val history: List<AiAskTurn> = emptyList()
)

/** 대화 한 줄. role은 "user" 또는 "assistant"만 쓴다 */
data class AiAskTurn(
    val role: String,
    val text: String
)

data class AiAskResponse(
    @SerializedName("answer")
    val answer: String = "",
    /**
     * 무료 사용자의 남은 질문 횟수. 무료/구독 구분이 없을 때(전면 무료 개방·구독자)는 null이라
     * 앱이 횟수 줄을 그리지 않는다.
     */
    @SerializedName("remainingFreeCount")
    val remainingFreeCount: Int? = null,
    /** 오늘 상한. 남은 횟수를 "2/3회"처럼 보여줄 때 쓴다 */
    @SerializedName("limit")
    val limit: Int? = null
)

/** 화면에서 오가는 말 한 줄. 오류도 같은 목록에 담아 말풍선으로 보여준다 */
data class AiAskMessage(
    val text: String,
    val fromUser: Boolean,
    /** 답을 받지 못한 줄. 오류 색으로 그리고 "다시 시도"를 붙인다 */
    val error: Boolean = false
)

/**
 * 오늘 남은 질문 횟수. 서버가 숫자를 준 경우(무료/구독 구분이 있을 때)에만 존재한다.
 * 전면 무료 개방 중에는 서버가 null을 주므로 이 값이 없고, 시트는 횟수 줄을 그리지 않는다.
 */
data class AiAskQuota(val remaining: Int, val limit: Int)

/**
 * AI가 인식하는 화면의 종류. 서버(functions/askAiCoach.js의 SCREEN_TYPES)와 값이 같아야 한다.
 * 여기 없는 화면에는 AI 버튼을 두지 않는다 — 로그인·설정·입력·카메라처럼 물을 데이터가 없는 화면이다.
 */
object AiScreenTypes {
    /** 일일 기록 목록 (홈) */
    const val DAILY_LIST = "daily_list"

    /** 하루 정산 상세 */
    const val DAILY_RECORD = "daily_record"

    /** 월간 정산 */
    const val MONTH_REPORT = "month_report"

    /** 주·연 정산 상세 */
    const val PERIOD_REPORT = "period_report"

    /** 인바디 측정 이력 */
    const val INBODY_LIST = "inbody_list"

    /** 인바디 추이 그래프 */
    const val INBODY_TREND = "inbody_trend"

    /** 체중 추이 그래프 */
    const val WEIGHT_TREND = "weight_trend"

    /** 질문 길이 상한. 서버 MAX_QUESTION_CHARS와 같아야 한다 */
    const val MAX_QUESTION = 500
}
