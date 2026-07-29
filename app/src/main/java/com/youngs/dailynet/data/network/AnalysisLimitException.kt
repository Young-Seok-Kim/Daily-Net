package com.youngs.dailynet.data.network

import com.youngs.dailynet.data.model.AnalysisUsage

/**
 * 서버가 오늘 분석 한도를 초과했다고 응답했을 때(HTTP 429) 던진다.
 *
 * 일반 실패("분석 실패")와 구분해야 하는 이유는, 사용자에게 보여줄 안내와
 * 다음 행동(구독 유도)이 완전히 다르기 때문이다.
 */
class AnalysisLimitException(
    /**
     * 서버가 알려준 실제 사용량. 이 값으로 로컬을 맞춰두면
     * 다음번 사전 확인이 틀린 숫자로 사용자를 막는 일이 없다.
     */
    val usage: AnalysisUsage? = null
) : Exception("Daily analysis limit reached")
