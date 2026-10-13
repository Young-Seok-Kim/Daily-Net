package com.youngs.dailynet.data.model

import com.google.gson.annotations.SerializedName
import com.youngs.dailynet.data.local.entity.UserProfileEntity

/**
 * 인바디(체성분) 결과. 설정에서 넣고, Firestore `users/{uid}.inbody`와 Room 프로필 양쪽에 둔다.
 *
 * 분석 요청에 같이 실려 가고, 서버는 [bmr]이 있으면 공식 대신 그 값을 기초대사량으로 쓴다
 * (functions/inbody.js). 나머지는 피드백의 근거로만 쓰인다.
 *
 * 0은 "없음"이다. 결과지에 그 칸이 없거나 사용자가 비워 둔 것이라, 분석에서는 그 칸을 모르는 것으로 친다.
 */
data class InbodyData(
    /** 기초대사량 (kcal) */
    @SerializedName("bmr") val bmr: Int = 0,
    /** 골격근량 (kg) */
    @SerializedName("skeletalMuscle") val skeletalMuscle: Float = 0f,
    /** 체지방량 (kg) */
    @SerializedName("bodyFat") val bodyFat: Float = 0f,
    /** 체지방률 (%) */
    @SerializedName("bodyFatPercent") val bodyFatPercent: Float = 0f,
    /** 측정 당시 체중 (kg). 일일 기록의 체중과 별개다 */
    @SerializedName("weight") val weight: Float = 0f,
    /** 측정일 yyyy-MM-dd. 모르면 빈 문자열 */
    @SerializedName("measuredAt") val measuredAt: String = "",

    // b41부터. 결과지에 없으면 0
    /** 내장지방 레벨 (1~30) */
    @SerializedName("visceralFatLevel") val visceralFatLevel: Int = 0,
    /** 인바디 점수 (0~100) */
    @SerializedName("inbodyScore") val inbodyScore: Int = 0,
    /** 체수분 (L) */
    @SerializedName("bodyWater") val bodyWater: Float = 0f,

    /**
     * 서버(analyzeInbody)가 써 준 해석 글. 저장할 때 한 번 받고 값이 바뀌면 다시 받는다.
     * 분석 요청에는 실려 가지 않는다 (@Transient — Gson이 뺀다). 서버가 쓸 일이 없는 긴 글이다.
     */
    @Transient val analysis: String = "",
    /** 해석을 받은 날 yyyy-MM-dd */
    @Transient val analyzedAt: String = ""
) {
    /** 뭐라도 들어 있는지. 전부 0이면 저장할 가치가 없다 */
    val hasData: Boolean
        get() = bmr > 0 || skeletalMuscle > 0f || bodyFat > 0f || bodyFatPercent > 0f || weight > 0f ||
            visceralFatLevel > 0 || inbodyScore > 0 || bodyWater > 0f

    /** Firestore에 넣을 형태 */
    fun toMap(): Map<String, Any> = mapOf(
        "bmr" to bmr,
        "skeletalMuscle" to skeletalMuscle,
        "bodyFat" to bodyFat,
        "bodyFatPercent" to bodyFatPercent,
        "weight" to weight,
        "measuredAt" to measuredAt,
        "visceralFatLevel" to visceralFatLevel,
        "inbodyScore" to inbodyScore,
        "bodyWater" to bodyWater,
        "analysis" to analysis,
        "analyzedAt" to analyzedAt
    )

    companion object {
        /** Firestore 문서의 `inbody` 맵. 없거나 형식이 다르면 null */
        fun fromMap(raw: Any?): InbodyData? {
            val map = raw as? Map<*, *> ?: return null
            fun f(key: String): Float = (map[key] as? Number)?.toFloat() ?: 0f
            val data = InbodyData(
                bmr = (map["bmr"] as? Number)?.toInt() ?: 0,
                skeletalMuscle = f("skeletalMuscle"),
                bodyFat = f("bodyFat"),
                bodyFatPercent = f("bodyFatPercent"),
                weight = f("weight"),
                measuredAt = (map["measuredAt"] as? String).orEmpty(),
                visceralFatLevel = (map["visceralFatLevel"] as? Number)?.toInt() ?: 0,
                inbodyScore = (map["inbodyScore"] as? Number)?.toInt() ?: 0,
                bodyWater = f("bodyWater"),
                analysis = (map["analysis"] as? String).orEmpty(),
                analyzedAt = (map["analyzedAt"] as? String).orEmpty()
            )
            return data.takeIf { it.hasData }
        }

        /** Room 프로필에 흩어진 컬럼을 한 덩어리로 */
        fun fromProfile(profile: UserProfileEntity): InbodyData = InbodyData(
            bmr = profile.inbodyBmr,
            skeletalMuscle = profile.inbodyMuscle,
            bodyFat = profile.inbodyFat,
            bodyFatPercent = profile.inbodyFatPercent,
            weight = profile.inbodyWeight,
            measuredAt = profile.inbodyMeasuredAt,
            analysis = profile.inbodyAnalysis,
            analyzedAt = profile.inbodyAnalyzedAt
        )
    }
}

/** 프로필에 인바디 값을 얹은 사본. null이면 지운다 */
fun UserProfileEntity.withInbody(data: InbodyData?): UserProfileEntity {
    val d = data?.takeIf { it.hasData } ?: InbodyData()
    return copy(
        inbodyBmr = d.bmr,
        inbodyMuscle = d.skeletalMuscle,
        inbodyFat = d.bodyFat,
        inbodyFatPercent = d.bodyFatPercent,
        inbodyWeight = d.weight,
        inbodyMeasuredAt = d.measuredAt,
        inbodyAnalysis = d.analysis,
        inbodyAnalyzedAt = d.analyzedAt
    )
}

/** 저장된 인바디 값을 해석해 달라는 요청. 서버가 BMI·체지방 구간을 계산하므로 신체 정보도 같이 보낸다 */
data class InbodyAnalysisRequest(
    val inbody: InbodyData,
    val height: Float,
    val weight: Float,
    val isMale: Boolean,
    val birthDate: String,
    val language: String
)

data class InbodyAnalysisResponse(
    /** 화면에 그대로 보여줄 해석 글. 실패하면 빈 문자열 */
    @SerializedName("text") val text: String = ""
)

/** 인바디 결과지 사진을 읽어달라는 요청 */
data class InbodyPhotoRequest(
    /** Base64 JPEG 한 장. 결과지는 한 장이면 된다 */
    val image: String,
    val mimeType: String = "image/jpeg",
    val language: String
)

data class InbodyPhotoResponse(
    @SerializedName("found") val found: Boolean = false,
    /** 읽은 값. 결과지가 아니거나 못 읽었으면 null */
    @SerializedName("inbody") val inbody: InbodyData? = null
)
