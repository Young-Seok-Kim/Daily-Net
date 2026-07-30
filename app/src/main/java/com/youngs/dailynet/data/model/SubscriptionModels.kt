package com.youngs.dailynet.data.model

import com.google.gson.annotations.SerializedName

/**
 * 구독 여부를 확인해 달라는 요청.
 *
 * 앱은 "내가 구독자다"를 주장하지 않고 구매 토큰만 넘긴다.
 * 판단은 서버가 Google Play에 직접 물어봐서 한다.
 */
data class SubscriptionVerifyRequest(
    /**
     * Play Billing이 준 구매 토큰.
     *
     * null이면 "활성 구매가 없다"는 뜻이고 서버가 구독을 해제한다.
     * 구매 조회 자체에 실패했을 때는 이 요청을 보내면 안 된다.
     * (조회 실패를 해지로 오해해 정상 구독자의 구독이 풀린다)
     */
    @SerializedName("purchaseToken")
    val purchaseToken: String?
)

data class SubscriptionVerifyResponse(
    /**
     * 서버가 Play에 확인한 결과.
     *
     * 서버가 판단하지 못했으면(Play 장애 등) null이 온다.
     * 그때는 false로 취급하지 말고 기존 상태를 그대로 둬야 한다.
     */
    @SerializedName("isSubscribed")
    val isSubscribed: Boolean? = null,

    /** SUBSCRIPTION_STATE_ACTIVE 같은 Play의 상태 문자열. 로그 확인용. */
    @SerializedName("state")
    val state: String? = null,

    /** 만료 시각(ISO-8601). 지금은 쓰지 않지만 서버가 함께 내려준다. */
    @SerializedName("expiryTime")
    val expiryTime: String? = null
)
