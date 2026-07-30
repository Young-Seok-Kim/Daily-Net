package com.youngs.dailynet.data.network

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context
) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCT_ID_MONTHLY = "dailynet_pro_monthly"
    }

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    // ViewModel에서 성공 이벤트를 구독할 수 있도록 콜백 변수 지정
    var onPurchaseSuccess: ((Purchase) -> Unit)? = null

    init {
        startConnection()
    }

    private fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _isReady.value = true
                }
            }
            override fun onBillingServiceDisconnected() {
                startConnection()
            }
        })
    }

    // 프리미엄 구독 창 띄우기
    fun launchBillingFlow(activity: Activity, productId: String) {
        Log.d("Billing", "isReady: ${_isReady.value}")
        if (!_isReady.value) return

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsResult ->
            val productDetailsList = productDetailsResult.productDetailsList
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                val productDetails = productDetailsList[0]
                val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: ""

                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetails)
                                .setOfferToken(offerToken)
                                .build()
                        )
                    )
                    .build()

                billingClient.launchBillingFlow(activity, billingFlowParams)
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    handlePurchase(purchase)
                }
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (!purchase.isAcknowledged) {
            val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    onPurchaseSuccess?.invoke(purchase)
                }
            }
        }
    }

    /**
     * 활성 구독의 구매 토큰을 가져온다. 서버 검증(verifySubscription)에 넘길 값이다.
     *
     * 조회 실패와 "구매가 없음"을 반드시 구분한다.
     * 실패를 구매 없음으로 처리하면, 결제 서비스가 잠깐 응답하지 않을 때
     * 정상 구독자의 구독이 서버에서 해제된다.
     *
     * @param onResult 조회 성공이면 [Result.success](토큰, 구매가 없으면 null),
     *                 조회 자체가 실패했으면 [Result.failure]
     */
    fun queryActivePurchaseToken(onResult: (Result<String?>) -> Unit) {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { billingResult, purchaseList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val token = purchaseList
                    .firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    ?.purchaseToken
                onResult(Result.success(token))
            } else {
                onResult(
                    Result.failure(
                        IllegalStateException("queryPurchases 실패: ${billingResult.responseCode}")
                    )
                )
            }
        }
    }

    /**
     * 구글 결제 서버 기준으로 실제 구독 중인지 확인한다.
     *
     * 화면 흐름(구독 안내를 띄울지)을 정하는 용도로만 쓴다.
     * 실제 권한 판단은 서버가 [queryActivePurchaseToken]으로 받은 토큰을 검증해서 한다.
     *
     * 무제한 사용자(관리자 등) 판별은 Firestore 조회가 필요해 여기서 하지 않는다.
     * 호출부(MainViewModel)에서 [com.youngs.dailynet.util.AdminManager]로 먼저 걸러낸다.
     */
    fun checkSubscriptionStatus(onResult: (Boolean) -> Unit) {
        // 👑 구글 서버에 현재 유저의 구독권 정보를 물어봅니다.
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { billingResult, purchaseList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                // 현재 활성화된 구독권이 하나라도 있으면 true
                val isSubscribed = purchaseList.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                onResult(isSubscribed)
            } else {
                onResult(false)
            }
        }

    }
}