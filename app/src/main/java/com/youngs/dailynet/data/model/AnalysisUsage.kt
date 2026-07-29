package com.youngs.dailynet.data.model

/**
 * 서버가 센 오늘의 분석 사용량.
 *
 * 예전에는 앱이 직접 횟수를 세고 Firestore에 썼는데, 앱 데이터를 지우거나 문서를 손대면
 * 그대로 우회됐다. 이제 서버가 트랜잭션으로 세고 그 결과를 여기에 담아 돌려준다.
 *
 * b24 미만 앱은 인증 토큰을 보내지 않아 서버가 사용자를 알 수 없다. 그때는 null로 온다.
 */
data class AnalysisUsage(
    /** 오늘 사용한 횟수 (이번 분석 포함) */
    val count: Int = 0,
    /** 무료 사용자의 하루 한도 */
    val limit: Int = 3,
    /** 구독자나 무제한 계정이면 true. 이때 count는 참고용일 뿐 제한이 걸리지 않는다. */
    val unlimited: Boolean = false
)
