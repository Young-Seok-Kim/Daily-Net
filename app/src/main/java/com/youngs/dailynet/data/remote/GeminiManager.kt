package com.youngs.dailynet.data.remote

import com.google.ai.client.generativeai.GenerativeModel
import com.google.type.DateTime
import com.youngs.dailynet.data.model.AnalysisRequest
import com.youngs.dailynet.data.model.AnalysisResponse
import com.youngs.dailynet.data.model.SettlementModel
import com.youngs.dailynet.data.network.DailyNetApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.http.POST
import retrofit2.http.Body

@Singleton
class GeminiManager @Inject constructor(
    private val apiService: DailyNetApiService // Hilt로 주입받은 Retrofit 서비스
) {
    suspend fun analyzeFoodAndExercise(
        settlement: SettlementModel,
        userHeight: Float
    ): AnalysisResponse {
        return withContext(Dispatchers.IO) {
            try {
                val request = AnalysisRequest(
                    weight = settlement.currentWeight,
                    height = userHeight,
                    breakfast = settlement.breakfast,
                    lunch = settlement.lunch,
                    dinner = settlement.dinner,
                    snack = settlement.snack,
                    exercise = settlement.exercise,
                    remark = settlement.remark
                )

                // 서버 호출
                apiService.analyzeFoodAndExercise(request)
            } catch (e: Exception) {
                e.printStackTrace()
                AnalysisResponse(0, "서버 분석 중 오류가 발생했습니다.")
            }
        }
    }
}