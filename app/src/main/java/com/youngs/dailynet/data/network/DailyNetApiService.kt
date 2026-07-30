package com.youngs.dailynet.data.network

import com.youngs.dailynet.data.model.AnalysisRequest
import com.youngs.dailynet.data.model.AnalysisResponse
import com.youngs.dailynet.data.model.MealPhotoRequest
import com.youngs.dailynet.data.model.MealPhotoResponse
import com.youngs.dailynet.data.model.SubscriptionVerifyRequest
import com.youngs.dailynet.data.model.SubscriptionVerifyResponse
import retrofit2.http.POST
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Url

interface DailyNetApiService {
    @POST("analyzeDiet") // index.js에서 exports한 함수명
    suspend fun analyzeFoodAndExercise(
        /**
         * "Bearer {Firebase ID 토큰}".
         * 서버가 이걸로 사용자를 확인하고 하루 분석 횟수를 센다.
         * null이면 Retrofit이 헤더를 아예 붙이지 않는다.
         */
        @Header("Authorization") authorization: String?,
        @Body request: AnalysisRequest
    ): AnalysisResponse

    /**
     * 음식 사진에서 메뉴명을 읽어온다.
     *
     * Cloud Functions는 함수마다 URL이 달라서 baseUrl을 쓸 수 없다.
     * 그래서 전체 주소를 [Url]로 직접 넘긴다.
     */
    @POST
    suspend fun extractMeal(
        @Url url: String,
        @Header("Authorization") authorization: String?,
        @Body request: MealPhotoRequest
    ): MealPhotoResponse

    /**
     * 구매 토큰을 서버에 보내 구독 여부를 확인받는다.
     *
     * 앱이 Firestore의 isSubscribed를 직접 쓰던 것을 대체한다.
     * 앱이 쓰면 결제 없이 필드만 바꿔 분석 무제한이 되기 때문이다.
     */
    @POST
    suspend fun verifySubscription(
        @Url url: String,
        @Header("Authorization") authorization: String?,
        @Body request: SubscriptionVerifyRequest
    ): SubscriptionVerifyResponse
}
