package com.youngs.dailynet.data.model

import com.google.gson.annotations.SerializedName

data class AnalysisResponse(
    @SerializedName("net_calories")
    val netCalories: Int,

    @SerializedName("feedback")
    val feedback: String,

    /**
     * 차트·통계용 구조화 데이터. b24 이전 서버 응답에는 없으므로 null일 수 있다.
     * 화면을 그릴 때는 반드시 null을 정상 상태로 다뤄야 한다.
     */
    @SerializedName("structured")
    val structured: AnalysisDetail? = null,

    /**
     * 서버가 센 오늘 사용량. 인증 토큰을 보내지 않는 구버전 경로에서는 null이다.
     */
    @SerializedName("usage")
    val usage: AnalysisUsage? = null
)
