package com.youngs.dailynet.data.network

/**
 * 서버가 오늘 분석 한도를 초과했다고 응답했을 때(HTTP 429) 던진다.
 *
 * 일반 실패("분석 실패")와 구분해야 하는 이유는, 사용자에게 보여줄 안내와
 * 다음 행동(구독 유도)이 완전히 다르기 때문이다.
 */
class AnalysisLimitException : Exception("Daily analysis limit reached")
