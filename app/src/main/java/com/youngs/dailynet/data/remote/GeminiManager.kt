package com.youngs.dailynet.data.remote

import com.google.ai.client.generativeai.GenerativeModel
import com.google.type.DateTime
import com.youngs.dailynet.data.model.SettlementModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
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

    suspend fun analyzeFoodAndExercise(
        settlement: SettlementModel,
        userHeight: Float
    ): AnalysisResponse =
        withContext(Dispatchers.IO) {
            val prompt = """
    당신은 전문 다이어트 영양사이자 스마트한 분석 엔진입니다. 
    사용자의 신체 데이터와 활동량을 분석하여 오늘의 '순 칼로리'를 계산하고, 사용자가 한눈에 볼 수 있는 '상세 분석 레포트'를 피드백에 작성하세요.

    [신체 데이터]
    - 날짜: ${LocalDate.now()}
    - 키: ${userHeight}cm
    - 현재 체중: ${settlement.currentWeight}kg
    
    [입력 데이터]
    - 아침: ${settlement.breakfast} / 점심: ${settlement.lunch} / 저녁: ${settlement.dinner} / 간식: ${settlement.snack}
    - 운동: ${settlement.exercise}
    
    분석 및 출력 규칙:
    1. BMR(기초대사량): Mifflin-St Jeor 공식을 사용하여 정교하게 계산하세요.
    2. 피드백(feedback) 구성: 아래의 '로그 테이블' 형식을 반드시 포함하여 작성하세요.
       - 구분 | 상세 데이터 | 칼로리 연산 | 비고
       - 기초 사양 | 기초대사량 (BMR) | -1,950 | 기초 소모 칼로리
       - 활동 인입 | 러닝 1시간 | -850 | 고강도 인터벌 가속 모드
       - 섭취 인입 | 점심: 닭가슴살 1팩 | +130 | 단백질 인입 성공
       ... (이런 식으로 모든 항목 나열)
    3. 순 칼로리(net_calories): 모든 항목의 합계를 정수로 산출하세요.

    반드시 아래 JSON 형식으로 응답하세요:
    {
      "net_calories": -1800,
      "feedback": "구분 | 상세 데이터 | 칼로리 연산 | 비고\n--------------------------------------\n기초 사양 | 기초대사량(BMR) | -1,950 | 기본 엔진 가동\n활동 인입 | MMA 1시간 | -850 | 고강도 인터벌 가속\n섭취 인입 | 점심: 닭가슴살 | +130 | 단백질 인입\n...\n\n오늘의 결과: 약 -1,800kcal. 지방 삭제 로직이 아주 성공적으로 작동 중입니다!"
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