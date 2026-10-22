package com.youngs.dailynet.data.network

import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.data.model.AnalysisUsage
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.model.AnalysisRequest
import com.youngs.dailynet.data.model.AnalysisResponse
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.model.InbodyAnalysisRequest
import com.youngs.dailynet.data.model.InbodyData
import com.youngs.dailynet.data.model.InbodyPhotoRequest
import com.youngs.dailynet.data.model.MealPhotoRequest
import com.youngs.dailynet.util.Constants
import com.youngs.dailynet.util.CrashReporter
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
        /** 가장 최근 인바디 측정. 없으면 서버가 공식으로 기초대사량을 추정한다 */
        inbody: InbodyData? = null,
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
                    language = Locale.getDefault().toLanguageTag(),
                    // 인바디를 넣어 뒀으면 같이 보낸다. 서버가 기초대사량을 이 값으로 바꿔 쓴다
                    inbody = inbody?.takeIf { it.hasData }
                )

                // 서버가 사용자를 확인하고 하루 횟수를 세는 데 쓴다.
                // 토큰을 못 얻어도 요청 자체는 보낸다 (서버가 예전 방식으로 처리한다).
                val token = runCatching {
                    auth.currentUser?.getIdToken(false)?.await()?.token
                }.getOrNull()

                // 서버 호출. 서버에 닿지도 못하고 바로 튕기면 한 번 더 보낸다.
                retryOnceIfRejectedFast("analyzeDiet") {
                    apiService.analyzeFoodAndExercise(
                        authorization = token?.let { "Bearer $it" },
                        request = request
                    )
                }
            } catch (e: HttpException) {
                if (e.code() == HTTP_TOO_MANY_REQUESTS) {
                    // 한도 초과는 정상 동작이므로 오류로 보고하지 않는다
                    throw AnalysisLimitException(parseUsage(e))
                }
                CrashReporter.report("analyzeDiet:http${e.code()}", e)
                null
            } catch (e: Exception) {
                CrashReporter.report("analyzeDiet", e)
                null
            }
        }
    }

    /** 429 응답 본문에서 사용량을 꺼낸다. 못 읽어도 흐름을 막지 않는다. */
    private fun parseUsage(e: HttpException): AnalysisUsage? = runCatching {
        val body = e.response()?.errorBody()?.string()
        Gson().fromJson(body, AnalysisResponse::class.java)?.usage
    }.getOrNull()

    /**
     * 음식 사진에서 메뉴명을 읽어온다. 실패하면 null.
     *
     * 여러 장을 넘기면 한 요청에 모두 실어 보낸다. 같은 끼니를 접시별로 찍은 사진이라
     * 한 번에 봐야 겹치는 음식을 두 번 세지 않는다.
     *
     * 분석과 달리 하루 횟수를 소모하지 않는다. 입력을 돕는 단계일 뿐이라
     * 여러 번 다시 찍어도 부담이 없어야 하기 때문이다. 서버에는 계정 탈취 같은
     * 비용 사고를 막는 높은 시도 상한만 있고, 그때 429가 온다.
     */
    /** 저장된 인바디 값의 해석 글. 실패하면 null */
    suspend fun analyzeInbody(userProfile: UserProfileEntity, inbody: InbodyData): String? {
        return withContext(Dispatchers.IO) {
            try {
                val token = runCatching {
                    auth.currentUser?.getIdToken(false)?.await()?.token
                }.getOrNull()
                val response = retryOnceIfRejectedFast("analyzeInbody") {
                    apiService.analyzeInbody(
                        url = Constants.ANALYZE_INBODY_URL,
                        authorization = token?.let { "Bearer $it" },
                        request = InbodyAnalysisRequest(
                            inbody = inbody,
                            height = userProfile.height,
                            weight = userProfile.initialWeight,
                            isMale = userProfile.isMale,
                            birthDate = userProfile.birthDate,
                            language = Locale.getDefault().toLanguageTag()
                        )
                    )
                }
                response.text.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                CrashReporter.report("analyzeInbody", e)
                null
            }
        }
    }

    /**
     * 인바디 결과지 사진에서 체성분 숫자를 읽어온다. 못 읽었으면 null.
     * @throws PhotoLimitException 사진 인식 시도 상한(식사 사진과 공유)에 닿은 경우
     */
    suspend fun extractInbodyFromPhoto(base64Image: String): InbodyData? {
        return withContext(Dispatchers.IO) {
            try {
                val token = runCatching {
                    auth.currentUser?.getIdToken(false)?.await()?.token
                }.getOrNull()

                val response = retryOnceIfRejectedFast("extractInbody") {
                    apiService.extractInbody(
                        url = Constants.EXTRACT_INBODY_URL,
                        authorization = token?.let { "Bearer $it" },
                        request = InbodyPhotoRequest(
                            image = base64Image,
                            language = Locale.getDefault().toLanguageTag()
                        )
                    )
                }
                response.inbody?.takeIf { response.found && it.hasData }
            } catch (e: HttpException) {
                if (e.code() == HTTP_TOO_MANY_REQUESTS) {
                    throw PhotoLimitException()
                }
                CrashReporter.report("extractInbody:http${e.code()}", e)
                null
            } catch (e: Exception) {
                CrashReporter.report("extractInbody", e)
                null
            }
        }
    }

    suspend fun extractMealFromPhoto(base64Images: List<String>): String? {
        require(base64Images.isNotEmpty()) { "사진이 없다" }
        return withContext(Dispatchers.IO) {
            try {
                val token = runCatching {
                    auth.currentUser?.getIdToken(false)?.await()?.token
                }.getOrNull()

                val response = retryOnceIfRejectedFast("extractMeal") {
                    apiService.extractMeal(
                        url = Constants.EXTRACT_MEAL_URL,
                        authorization = token?.let { "Bearer $it" },
                        request = MealPhotoRequest(
                            image = base64Images.first(),
                            images = base64Images,
                            language = Locale.getDefault().toLanguageTag()
                        )
                    )
                }
                response.text.takeIf { it.isNotBlank() }
            } catch (e: HttpException) {
                if (e.code() == HTTP_TOO_MANY_REQUESTS) {
                    throw PhotoLimitException()
                }
                CrashReporter.report("extractMeal:http${e.code()}", e)
                null
            } catch (e: Exception) {
                CrashReporter.report("extractMeal", e)
                null
            }
        }
    }

    /**
     * 서버 코드에 닿지도 못하고 **바로** 튕긴 5xx는 한 번만 다시 보낸다.
     *
     * 2026-10-23 07:23 실제로 겪었다 — Cloud Run이 컨테이너를 띄우기도 전에 0초 만에 500을 돌려줬다
     * (요청 로그만 남고 인스턴스 기동·함수 로그가 전무, 30일 중 유일). 9분 뒤 다시 누르자 6.7초에
     * 정상 처리됐다. 이런 인프라 일시 오류는 바로 다시 보내면 사용자는 모른다.
     *
     * 오래 걸린 뒤의 5xx는 다시 보내지 않는다. 그건 서버가 Gemini를 80초 넘게 기다리다 포기한 것이라
     * (2026-10-22 21:02, 84초 뒤 500) 다시 보내면 사용자가 또 80초를 기다린다. 서버 함수가 실제로 돌면
     * 인증·횟수 확인·모델 호출을 합쳐 3초는 넘기므로, 그보다 빨리 온 5xx만 "서버에 닿지 못한 것"으로 본다.
     *
     * 429·4xx는 그대로 던진다. 한도 초과나 인증 실패는 다시 보내도 같은 답이 온다.
     */
    private suspend fun <T> retryOnceIfRejectedFast(where: String, call: suspend () -> T): T {
        val started = System.nanoTime()
        return try {
            call()
        } catch (e: HttpException) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            if (e.code() < 500 || elapsedMs > FAST_REJECT_MS) throw e
            // 얼마나 자주 겪는지 보려고 남긴다. 재시도까지 실패하면 호출한 쪽이 다시 보고한다.
            CrashReporter.report("$where:http${e.code()} ${elapsedMs}ms 만에 튕김, 1회 재시도", e)
            call()
        }
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** 이보다 빨리 온 5xx는 서버 함수가 돌지도 않은 것이다 (retryOnceIfRejectedFast 참고) */
        private const val FAST_REJECT_MS = 3000L
    }
}
