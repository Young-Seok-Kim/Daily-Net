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

    suspend fun analyzeFoodAndExercise(settlement: SettlementModel): AnalysisResponse = withContext(Dispatchers.IO) {
        val prompt = """
            사용자의 다이어트 데이터를 분석하여 칼로리 밸런스를 계산하세요.
            [신체 정보] 체중: ${settlement.currentWeight}kg
            [식단] 아침: ${settlement.breakfast}, 점심: ${settlement.lunch}, 저녁: ${settlement.dinner}, 간식: ${settlement.snack}
            [활동] 운동: ${settlement.exercise}, 메모: ${settlement.noteInput}
            
            반드시 아래 JSON 형식으로만 응답하세요:
            {
              "net_calories": 정수,
              "feedback": "전문적인 조언 한 줄"
            }
        """.trimIndent()

        try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: ""
            val jsonString = responseText.replace("```json", "").replace("```", "").trim()
            val jsonObject = JSONObject(jsonString)

            AnalysisResponse(
                netCalories = jsonObject.optInt("net_calories",0),
                feedback = jsonObject.optString("feedback", "분석 결과를 가져올 수 없습니다.")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AnalysisResponse(0, "데이터 분석 중 오류가 발생했습니다.")
        }
    }
}