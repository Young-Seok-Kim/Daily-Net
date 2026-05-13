package com.youngs.dailynet.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_drafts")
data class SettlementModel(
    @PrimaryKey
    val date: String = "",           // yyyy-MM-dd
    val title: String = "",

    // 식사 및 입력 항목
    val breakfast: String = "",
    val lunch: String = "",
    val dinner: String = "",
    val snack: String = "",

    // 💡 Firestore 필드명(exercise)과 일치시킴
    val exercise: String = "",
    val noteInput: String = "",
    val weight: Float = 0f,

    val netCalories: Int = 0,
    val isExercise: Boolean = false,
    val currentWeight: Float = 0f,
    val tags: List<String> = listOf(),
    val note: String = "",           // 제미나이 피드백
    val details: List<Map<String, Any>> = listOf(),

    // 💡 Firestore 필드명(finalized, analyzing)과 일치시킴
    val isFinalizing: Boolean = false,
    val isAnalyzing: Boolean = false,

    val analysisResult: String = ""
)