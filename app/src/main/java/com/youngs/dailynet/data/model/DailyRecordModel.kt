package com.youngs.dailynet.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_records")
data class DailyRecordModel(
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
    val remark: String = "",
    val steps: Int = 0,              // 걸음수 (오늘이면 자동 기입, 과거면 저장값)
    val isMale: Boolean = true,

    val netCalories: Int = 0,
    val hasExercise: Boolean = false,
    val weight: Float = 0f,
    val tags: List<String> = listOf(),
    val note: String = "",           // 제미나이 피드백
    val details: List<Map<String, Any>> = listOf(),

    val finalized: Boolean = false,
    val analyzing: Boolean = false,

    val analysisResult: String = ""
)