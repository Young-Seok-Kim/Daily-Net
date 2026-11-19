package com.youngs.dailynet.data.model

import com.google.gson.annotations.SerializedName

/**
 * 인바디 해석의 **구조** (b68~). 서버(functions/inbody.js inbodyReportDetail)가 글(`analysis`)과 함께 내려준다.
 *
 * 글만 저장하던 때는 화면이 한 덩어리 텍스트였고, 판정·할 일을 따로 쓸 수 없었다. 이제 칸별 글과
 * 서버가 센 판정 코드를 그대로 두어 카드로 그리고, 추이·AI 질문에도 짧게 넘긴다.
 * 식단의 [AnalysisDetail]과 같은 방식으로 Room에는 JSON 한 칸(Converters), Firestore에는 중첩 맵으로 들어간다.
 *
 * b68 이전에 받은 해석에는 없어 null이다. 그때는 예전처럼 글을 보여준다.
 */
data class InbodyAnalysisDetail(
    /** 전체 상태 두세 문장 */
    @SerializedName("summary") val summary: String = "",
    /** 체지방 설명. 측정값이 없거나 모델이 비우면 "" */
    @SerializedName("bodyFat") val bodyFat: String = "",
    @SerializedName("muscle") val muscle: String = "",
    @SerializedName("bmr") val bmr: String = "",
    /** 앞으로 2~3개월 할 일. 최대 3개 */
    @SerializedName("actions") val actions: List<String> = emptyList(),
    @SerializedName("metrics") val metrics: InbodyMetrics = InbodyMetrics()
) {
    /** 그릴 내용이 하나라도 있는지. 전부 비면 글로 물러난다 */
    val isRenderable: Boolean
        get() = summary.isNotBlank() || bodyFat.isNotBlank() || muscle.isNotBlank() ||
            bmr.isNotBlank() || actions.isNotEmpty()

    /** Firestore 문서의 `analysisDetail` 맵 */
    fun toMap(): Map<String, Any> = mapOf(
        "summary" to summary,
        "bodyFat" to bodyFat,
        "muscle" to muscle,
        "bmr" to bmr,
        "actions" to actions,
        "metrics" to metrics.toMap()
    )

    companion object {
        /** Firestore 맵. 없거나 형식이 다르면 null */
        fun fromMap(raw: Any?): InbodyAnalysisDetail? {
            val map = raw as? Map<*, *> ?: return null
            fun s(key: String): String = (map[key] as? String).orEmpty()
            return InbodyAnalysisDetail(
                summary = s("summary"),
                bodyFat = s("bodyFat"),
                muscle = s("muscle"),
                bmr = s("bmr"),
                actions = (map["actions"] as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() }.orEmpty(),
                metrics = InbodyMetrics.fromMap(map["metrics"]) ?: InbodyMetrics()
            )
        }
    }
}

/**
 * 서버가 센 수치 판정. 모델이 아니라 표준 구간으로 정한 값이라 화면이 그대로 뱃지로 쓴다.
 * 판정 코드는 [LEVEL_LOW]·[LEVEL_STANDARD]·[LEVEL_HIGH]·[LEVEL_UNKNOWN].
 */
data class InbodyMetrics(
    @SerializedName("bmi") val bmi: Float = 0f,
    @SerializedName("fatLevel") val fatLevel: String = LEVEL_UNKNOWN,
    /** "10~20%" 처럼 서버가 만든 표준 구간 글자 */
    @SerializedName("fatStandard") val fatStandard: String = "",
    /** 골격근량이 체중에서 차지하는 비율(%) */
    @SerializedName("smmRatio") val smmRatio: Float = 0f,
    @SerializedName("muscleLevel") val muscleLevel: String = LEVEL_UNKNOWN,
    @SerializedName("muscleStandard") val muscleStandard: String = "",
    /** 키·체중·나이 공식 기초대사량 */
    @SerializedName("formulaBmr") val formulaBmr: Int = 0,
    /** 인바디 기초대사량 - 공식값 */
    @SerializedName("bmrDiff") val bmrDiff: Int = 0,
    @SerializedName("visceralLevel") val visceralLevel: String = LEVEL_UNKNOWN,
    @SerializedName("visceralStandard") val visceralStandard: String = "",
    /** 근손실을 막는 단백질 목표 (g/일) */
    @SerializedName("proteinTarget") val proteinTarget: Int = 0
) {
    fun toMap(): Map<String, Any> = mapOf(
        "bmi" to bmi,
        "fatLevel" to fatLevel,
        "fatStandard" to fatStandard,
        "smmRatio" to smmRatio,
        "muscleLevel" to muscleLevel,
        "muscleStandard" to muscleStandard,
        "formulaBmr" to formulaBmr,
        "bmrDiff" to bmrDiff,
        "visceralLevel" to visceralLevel,
        "visceralStandard" to visceralStandard,
        "proteinTarget" to proteinTarget
    )

    companion object {
        const val LEVEL_LOW = "low"
        const val LEVEL_STANDARD = "standard"
        const val LEVEL_HIGH = "high"
        const val LEVEL_UNKNOWN = "unknown"

        fun fromMap(raw: Any?): InbodyMetrics? {
            val map = raw as? Map<*, *> ?: return null
            fun f(key: String): Float = (map[key] as? Number)?.toFloat() ?: 0f
            fun i(key: String): Int = (map[key] as? Number)?.toInt() ?: 0
            fun s(key: String, default: String = ""): String = (map[key] as? String) ?: default
            return InbodyMetrics(
                bmi = f("bmi"),
                fatLevel = s("fatLevel", LEVEL_UNKNOWN),
                fatStandard = s("fatStandard"),
                smmRatio = f("smmRatio"),
                muscleLevel = s("muscleLevel", LEVEL_UNKNOWN),
                muscleStandard = s("muscleStandard"),
                formulaBmr = i("formulaBmr"),
                bmrDiff = i("bmrDiff"),
                visceralLevel = s("visceralLevel", LEVEL_UNKNOWN),
                visceralStandard = s("visceralStandard"),
                proteinTarget = i("proteinTarget")
            )
        }
    }
}
