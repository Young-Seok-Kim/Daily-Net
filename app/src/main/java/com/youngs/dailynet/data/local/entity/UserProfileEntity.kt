package com.youngs.dailynet.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// UserProfile용 (키, 시작 몸무게 등 고정 정보)
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 0, // 단일 행만 유지
    val name: String = "",
    val email: String = "", // 구글 로그인 이메일
    val height: Float = 0f,
    val isMale: Boolean = true,
    val initialWeight: Float,
    val birthDate: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAnalyzedDate: String = "",
    val isSubscribed: Boolean = false,
    val todayAnalysisCount: Int = 0,

    // 인바디 결과 (한 덩어리로 다루려면 InbodyData.fromProfile). 0·빈 문자열이면 없음.
    // b39에서 추가. Firestore users 문서의 inbody 맵과 같은 내용이다.
    val inbodyBmr: Int = 0,
    val inbodyMuscle: Float = 0f,
    val inbodyFat: Float = 0f,
    val inbodyFatPercent: Float = 0f,
    val inbodyWeight: Float = 0f,
    val inbodyMeasuredAt: String = ""
)