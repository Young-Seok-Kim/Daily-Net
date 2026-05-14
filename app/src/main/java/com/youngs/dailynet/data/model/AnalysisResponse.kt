package com.youngs.dailynet.data.model

import com.google.gson.annotations.SerializedName

data class AnalysisResponse(
    @SerializedName("net_calories")
    val netCalories: Int,

    @SerializedName("feedback")
    val feedback: String
)
