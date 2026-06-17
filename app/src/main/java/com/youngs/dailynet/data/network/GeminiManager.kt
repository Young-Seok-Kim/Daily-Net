package com.youngs.dailynet.data.network

import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.model.AnalysisRequest
import com.youngs.dailynet.data.model.AnalysisResponse
import com.youngs.dailynet.data.model.SettlementModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiManager @Inject constructor(
    private val apiService: DailyNetApiService // Hilt로 주입받은 Retrofit 서비스
) {
    suspend fun analyzeFoodAndExercise(
        settlement: SettlementModel,
        userProfile: UserProfileEntity,
    ): AnalysisResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val sendWeight = if (settlement.weight == 0f) {
                    userProfile.initialWeight
                } else {
                    settlement.weight
                }

                val request = AnalysisRequest(
                    weight = sendWeight,
                    height = userProfile.height,
                    isMale = userProfile.isMale,
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
                null
            }
        }
    }
}