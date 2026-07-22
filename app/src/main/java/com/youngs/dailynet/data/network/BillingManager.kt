package com.youngs.dailynet.data.network

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.youngs.dailynet.util.AdminConfig
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

    fun checkSubscriptionStatus(userEmail: String?, onResult: (Boolean) -> Unit) {

        if (AdminConfig.isUserAdmin(userEmail)) {
            onResult(true)
            return
        }
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