package com.youngs.dailynet.data.model

data class AnalysisRequest(
    val weight: Float,
    val height: Float,
    val isMale: Boolean, // 추가: 남성이면 true, 여성이면 false
    val breakfast: String,
    val lunch: String,
    val dinner: String,
    val snack: String,
    val exercise: String,
    val remark: String,
    val steps: Int = 0
)
