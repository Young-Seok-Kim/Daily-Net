package com.youngs.dailynet.data.model

import com.google.gson.annotations.SerializedName

/**
 * 회원탈퇴 요청.
 *
 * 서버는 confirm이 true가 아니면 거절한다. ID 토큰이 새어 나가도
 * 빈 POST 한 번으로 계정이 지워지지 않게 하는 최소한의 안전장치다.
 */
data class WithdrawRequest(
    @SerializedName("confirm")
    val confirm: Boolean = true
)

data class WithdrawResponse(
    /** 서버가 Firestore 트리·구매 토큰 매핑·Auth 계정을 전부 지웠으면 true */
    @SerializedName("ok")
    val ok: Boolean = false,

    /** 실패 사유. 로그 확인용 */
    @SerializedName("error")
    val error: String? = null
)
