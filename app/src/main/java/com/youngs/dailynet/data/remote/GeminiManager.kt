package com.youngs.dailynet.data.remote

import androidx.compose.ui.unit.Constraints
import com.google.ai.client.generativeai.GenerativeModel
import com.youngs.dailynet.BuildConfig

object GeminiManager {
    private val generativeModel = GenerativeModel(
        modelName = Constants.MODEL_NAME,
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    // 식단 분석 함수 예시
    suspend fun analyzeDiet(userInput: String): String {
        return try {
            val response = generativeModel.generateContent(userInput)
            response.text ?: "분석 실패"
        } catch (e: Exception) {
            e.printStackTrace()
            "에러 발생: ${e.localizedMessage}"
        }
    }
}