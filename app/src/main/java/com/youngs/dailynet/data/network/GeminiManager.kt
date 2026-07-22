package com.youngs.dailynet.data.network

import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.model.AnalysisRequest
import com.youngs.dailynet.data.model.AnalysisResponse
import com.youngs.dailynet.data.model.DailyRecordModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiManager @Inject constructor(
    private val apiService: DailyNetApiService // Hilt로 주입받은 Retrofit 서비스
) {
    suspend fun analyzeFoodAndExercise(
        dailyRecordModel: DailyRecordModel,
        userProfile: UserProfileEntity,
    ): AnalysisResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val sendWeight = if (dailyRecordModel.weight == 0f) {
                    userProfile.initialWeight
                } else {
                    dailyRecordModel.weight
                }

                val request = AnalysisRequest(
                    weight = sendWeight,
                    height = userProfile.height,
                    isMale = userProfile.isMale,
                    breakfast = dailyRecordModel.breakfast,
                    lunch = dailyRecordModel.lunch,
                    dinner = dailyRecordModel.dinner,
                    snack = dailyRecordModel.snack,
                    exercise = dailyRecordModel.exercise,
                    remark = dailyRecordModel.remark,
                    steps = dailyRecordModel.steps
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