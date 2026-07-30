package com.youngs.dailynet.data.network

import com.google.firebase.auth.FirebaseAuth
import com.youngs.dailynet.data.model.SubscriptionVerifyRequest
import com.youngs.dailynet.util.Constants
import com.youngs.dailynet.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 구독 여부를 서버에 확인받는다.
 *
 * 예전에는 앱이 Play Billing을 보고 Firestore의 isSubscribed를 직접 썼다.
 * 그런데 그 문서는 본인이 쓸 수 있어서, 결제하지 않고 필드만 true로 바꾸면
 * 분석 무제한이 됐다. 그래서 판단을 서버로 넘기고 앱은 구매 토큰만 전달한다.
 */
@Singleton
class SubscriptionVerifier @Inject constructor(
    private val apiService: DailyNetApiService,
    private val auth: FirebaseAuth
) {
    /**
     * 구매 토큰을 서버에 보내 구독 여부를 확인받는다.
     *
     * @param purchaseToken 활성 구독의 구매 토큰. null이면 "활성 구매 없음"으로 보고해
     *                      서버가 구독을 해제한다. 구매 조회에 **실패**했을 때는
     *                      null을 넘기면 안 된다 (정상 구독자가 구독을 잃는다).
     * @return 서버가 판단한 구독 여부. 판단하지 못했으면 null이며,
     *         이때는 기존 상태를 그대로 두어야 한다.
     */
    suspend fun verify(purchaseToken: String?): Boolean? = withContext(Dispatchers.IO) {
        try {
            // 서버가 어느 사용자인지 알아야 기록할 수 있다. 토큰을 못 얻으면 판단 자체가 불가능하다.
            val idToken = auth.currentUser?.getIdToken(false)?.await()?.token
                ?: return@withContext null

            apiService.verifySubscription(
                url = Constants.VERIFY_SUBSCRIPTION_URL,
                authorization = "Bearer $idToken",
                request = SubscriptionVerifyRequest(purchaseToken)
            ).isSubscribed
        } catch (e: Exception) {
            CrashReporter.report("verifySubscription", e)
            null
        }
    }
}
