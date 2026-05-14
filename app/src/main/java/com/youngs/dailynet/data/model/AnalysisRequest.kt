package com.youngs.dailynet.data.model

data class AnalysisRequest(
    val weight: Float,
    val height: Float,
    val breakfast: String,
    val lunch: String,
    val dinner: String,
    val snack: String,
    val exercise: String
)
