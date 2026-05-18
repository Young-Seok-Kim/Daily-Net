package com.youngs.dailynet.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weight_history")
data class WeightEntity(
    @PrimaryKey val date: String, // yyyy-MM-dd
    val weight: Float
)

// UserProfile용 (키, 시작 몸무게 등 고정 정보)
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 0, // 단일 행만 유지
    val height: Float = 0f,
    val isMale: Boolean = true,
    val initialWeight: Float,
    val birthDate: String,
    val createdAt: Long = System.currentTimeMillis()
)