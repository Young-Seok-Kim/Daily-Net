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

    // ⚠️ b39~b40이 쓰던 인바디 자리. b41부터 inbody_records 표로 옮겼고(MIGRATION_13_14) 지금은 읽지 않는다.
    //    컬럼을 빼려면 테이블을 다시 만들어야 해서 그대로 둔다. 값은 전부 기본값이다.
    val inbodyBmr: Int = 0,
    val inbodyMuscle: Float = 0f,
    val inbodyFat: Float = 0f,
    val inbodyFatPercent: Float = 0f,
    val inbodyWeight: Float = 0f,
    val inbodyMeasuredAt: String = "",
    /** 서버가 써 준 인바디 해석 글과 받은 날 (b40) */
    val inbodyAnalysis: String = "",
    val inbodyAnalyzedAt: String = ""
)