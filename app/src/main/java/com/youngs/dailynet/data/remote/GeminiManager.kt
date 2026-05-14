package com.youngs.dailynet.data.remote

import com.google.ai.client.generativeai.GenerativeModel
import com.youngs.dailynet.data.model.SettlementModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiManager @Inject constructor(
    // GenerativeModel은 AppModule에서 @Provides로 제공됩니다.
    private val generativeModel: GenerativeModel
) {
    data class AnalysisResponse(
        val netCalories: Int,
        val feedback: String
    )

    suspend fun analyzeFoodAndExercise(settlement: SettlementModel): AnalysisResponse =
        withContext(Dispatchers.IO) {
            val prompt = """
                당신은 전문 다이어트 영양사입니다. 
                사용자의 신체 정보와 활동량을 바탕으로 오늘의 '순 칼로리(Net Calories)'를 계산하세요.
                
                [신체 정보] 체중: ${settlement.currentWeight}kg
                [식단] 아침: ${settlement.breakfast}, 점심: ${settlement.lunch}, 저녁: ${settlement.dinner}, 간식: ${settlement.snack}
                [활동] 운동: ${settlement.exercise}
                
                분석 규칙:
                1. 기초대사량(BMR) 계산: 사용자의 체중을 기반으로 대략적인 BMR을 포함하세요. (예: 체중 * 24kcal 등 간이 방식 활용)
                2. 순 칼로리 계산 공식: (섭취 칼로리) - (기초대사량 + 운동 소모 칼로리)
                3. 결과가 마이너스라면 사용자가 살이 빠지는 상태임을 의미합니다.
                4. net_calories는 반드시 '정수 숫자'만 출력하세요.
                
                반드시 아래 JSON 형식으로 응답하세요:
                {
                  "net_calories": -200,
                  "feedback": "전문적인 조언 한 줄"
                }
            """.trimIndent()

            try {
                val response = generativeModel.generateContent(prompt)
                val responseText = response.text ?: ""
                val jsonString = responseText.replace("```json", "").replace("```", "").trim()
                val jsonObject = JSONObject(jsonString)

                AnalysisResponse(
                    netCalories = jsonObject.optInt("net_calories", 0),
                    feedback = jsonObject.optString("feedback", "분석 결과를 가져올 수 없습니다.")
                )
            } catch (e: Exception) {
                e.printStackTrace()
                AnalysisResponse(0, "데이터 분석 중 오류가 발생했습니다.")
            }
        }
}