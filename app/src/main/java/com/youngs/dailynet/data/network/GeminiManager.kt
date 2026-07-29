package com.youngs.dailynet.data.network

import com.google.firebase.auth.FirebaseAuth
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.model.AnalysisRequest
import com.youngs.dailynet.data.model.AnalysisResponse
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.model.MealPhotoRequest
import com.youngs.dailynet.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiManager @Inject constructor(
    private val apiService: DailyNetApiService, // Hilt로 주입받은 Retrofit 서비스
    private val auth: FirebaseAuth
) {
    /**
     * @throws AnalysisLimitException 오늘 분석 한도를 초과한 경우.
     *         일반 실패(null 반환)와 달리 사용자에게 다른 안내를 해야 하므로 구분해서 던진다.
     */
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

                // 서버가 사용자를 확인하고 하루 횟수를 세는 데 쓴다.
                // 토큰을 못 얻어도 요청 자체는 보낸다 (서버가 예전 방식으로 처리한다).
                val token = runCatching {
                    auth.currentUser?.getIdToken(false)?.await()?.token
                }.getOrNull()

                // 서버 호출
                apiService.analyzeFoodAndExercise(
                    authorization = token?.let { "Bearer $it" },
                    request = request
                )
            } catch (e: HttpException) {
                if (e.code() == HTTP_TOO_MANY_REQUESTS) {
                    throw AnalysisLimitException()
                }
                e.printStackTrace()
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * 음식 사진에서 메뉴명을 읽어온다. 실패하면 null.
     *
     * 분석과 달리 하루 횟수를 소모하지 않는다. 입력을 돕는 단계일 뿐이라
     * 여러 번 다시 찍어도 부담이 없어야 하기 때문이다.
     */
    suspend fun extractMealFromPhoto(base64Image: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val token = runCatching {
                    auth.currentUser?.getIdToken(false)?.await()?.token
                }.getOrNull()

                val response = apiService.extractMeal(
                    url = Constants.EXTRACT_MEAL_URL,
                    authorization = token?.let { "Bearer $it" },
                    request = MealPhotoRequest(
                        image = base64Image,
                        language = Locale.getDefault().toLanguageTag()
                    )
                )
                response.text.takeIf { it.isNotBlank() }
            } catch (e: HttpException) {
                if (e.code() == HTTP_TOO_MANY_REQUESTS) {
                    throw PhotoLimitException()
                }
                e.printStackTrace()
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
