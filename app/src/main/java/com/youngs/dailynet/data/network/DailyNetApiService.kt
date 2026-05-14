package com.youngs.dailynet.data.network

import com.youngs.dailynet.data.model.AnalysisRequest
import com.youngs.dailynet.data.model.AnalysisResponse
import retrofit2.http.POST
import retrofit2.http.Body

interface DailyNetApiService {
    @POST("analyzeDiet") // index.js에서 exports한 함수명
    suspend fun analyzeFoodAndExercise(
        @Body request: AnalysisRequest
    ): AnalysisResponse
}