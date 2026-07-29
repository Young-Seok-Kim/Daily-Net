package com.youngs.dailynet.data.network

import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.model.AnalysisRequest
import com.youngs.dailynet.data.model.AnalysisResponse
import com.youngs.dailynet.data.model.DailyRecordModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
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
                    birthDate = userProfile.birthDate,
                    breakfast = dailyRecordModel.breakfast,
                    lunch = dailyRecordModel.lunch,
                    dinner = dailyRecordModel.dinner,
                    snack = dailyRecordModel.snack,
                    exercise = dailyRecordModel.exercise,
                    remark = dailyRecordModel.remark,
                    steps = dailyRecordModel.steps,
                    appVersion = BuildConfig.VERSION_CODE,
                    // 기기 언어를 그대로 보낸다. 앱 화면과 분석 리포트의 언어를 일치시키기 위함이다.
                    language = Locale.getDefault().toLanguageTag()
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