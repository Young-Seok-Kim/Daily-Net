package com.youngs.dailynet.data.network

/**
 * 오늘 기간(주·월·연) AI 평가 횟수를 다 썼을 때(HTTP 429) 던진다.
 *
 * 일반 실패("다시 시도해 주세요")와 안내가 달라야 해서 구분한다.
 * 이건 다시 눌러도 안 되고 내일 다시 해야 한다.
 */
class EvaluationLimitException : Exception("Daily period evaluation limit reached")
