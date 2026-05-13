package com.youngs.dailynet.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_drafts") // 테이블명을 초안(Draft)으로 명시
data class SettlementModel(
    @PrimaryKey
    val date: String = "",           // yyyy-MM-dd
    val title: String = "",

    // 입력창 6개 항목
    val breakfast: String = "",
    val lunch: String = "",
    val dinner: String = "",
    val snack: String = "",
    val exerciseInput: String = "",
    val noteInput: String = "",

    val netCalories: Int = 0,
    val isExercise: Boolean = false,
    val currentWeight: Double = 0.0,
    val tags: List<String> = listOf(),
    val note: String = "",           // 제미나이 피드백
    val details: List<Map<String, Any>> = listOf(),

    val isFinalized: Boolean = false, // 분석 및 저장 완료 여부
    val isAnalyzing: Boolean = false,
    val analysisResult: String = ""
)