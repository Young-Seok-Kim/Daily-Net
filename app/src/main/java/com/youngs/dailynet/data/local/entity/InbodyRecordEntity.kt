package com.youngs.dailynet.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.youngs.dailynet.data.model.InbodyData

/**
 * 인바디 측정 한 건. 측정일이 키다 — 같은 날 두 번 재는 일은 없고, Firestore 문서 ID와 맞추기도 쉽다.
 *
 * b40까지는 프로필(user_profile)에 마지막 한 건만 컬럼으로 두었다. b41부터 이 표에 전부 쌓고,
 * 가장 최근 것을 프로필 컬럼과 `users/{uid}.inbody`에 그대로 복사해 둔다(구버전 호환·설정 요약용).
 * 식단 분석에 들어가는 기초대사량도 가장 최근 것이다.
 */
@Entity(tableName = "inbody_records")
data class InbodyRecordEntity(
    @PrimaryKey val measuredAt: String,   // yyyy-MM-dd
    val bmr: Int = 0,
    val skeletalMuscle: Float = 0f,
    val bodyFat: Float = 0f,
    val bodyFatPercent: Float = 0f,
    val weight: Float = 0f,
    val visceralFatLevel: Int = 0,
    val inbodyScore: Int = 0,
    val bodyWater: Float = 0f,
    val analysis: String = "",
    val analyzedAt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 디버그 빌드에서 추이 화면 확인용으로 넣은 가짜 측정. 계정에 올리지 않고 분석에도 쓰지 않는다 */
    val isDummy: Boolean = false
) {
    fun toData(): InbodyData = InbodyData(
        bmr = bmr,
        skeletalMuscle = skeletalMuscle,
        bodyFat = bodyFat,
        bodyFatPercent = bodyFatPercent,
        weight = weight,
        measuredAt = measuredAt,
        visceralFatLevel = visceralFatLevel,
        inbodyScore = inbodyScore,
        bodyWater = bodyWater,
        analysis = analysis,
        analyzedAt = analyzedAt
    )

    companion object {
        fun fromData(data: InbodyData, createdAt: Long = System.currentTimeMillis()): InbodyRecordEntity =
            InbodyRecordEntity(
                measuredAt = data.measuredAt,
                bmr = data.bmr,
                skeletalMuscle = data.skeletalMuscle,
                bodyFat = data.bodyFat,
                bodyFatPercent = data.bodyFatPercent,
                weight = data.weight,
                visceralFatLevel = data.visceralFatLevel,
                inbodyScore = data.inbodyScore,
                bodyWater = data.bodyWater,
                analysis = data.analysis,
                analyzedAt = data.analyzedAt,
                createdAt = createdAt
            )
    }
}
