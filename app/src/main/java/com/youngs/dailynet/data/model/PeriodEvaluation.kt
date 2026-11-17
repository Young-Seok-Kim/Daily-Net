package com.youngs.dailynet.data.model

import com.google.gson.annotations.SerializedName

/**
 * 주·월·연 정산의 AI 평가.
 *
 * 서버(evaluatePeriod)가 그 기간의 일별 기록을 집계해 모델에게 평가를 받아 돌려준다.
 * 받은 결과는 Firestore `users/{uid}/evaluations/{키}`에 두고 다시 열어도 서버를 부르지 않는다.
 * (키는 [com.youngs.dailynet.ui.view.PeriodKind] 참고 — "week-202646", "month-2026-11", "year-2026")
 *
 * 평가를 받은 뒤 그 기간의 기록이 바뀌면(정산 추가·수정·삭제) [fingerprint]가 어긋나
 * 화면이 "기록이 바뀌었습니다"를 띄우고 다시 받을 수 있게 한다. 자동으로 다시 받지는 않는다 —
 * 하루에 정산을 한 번 할 때마다 그 주·달·해 평가가 세 번 다시 나가면 비용이 그만큼 든다.
 */
data class PeriodEvaluation(
    /** 0~100. 서버가 범위를 보장한다 */
    @SerializedName("score") val score: Int = 0,
    /** 한 줄 제목 */
    @SerializedName("headline") val headline: String = "",
    /** 총평 */
    @SerializedName("summary") val summary: String = "",
    /** 식단 평가 */
    @SerializedName("diet") val diet: String = "",
    /** 운동 평가 */
    @SerializedName("exercise") val exercise: String = "",
    /** 다음 기간에 할 일. 최대 3개 */
    @SerializedName("actions") val actions: List<String> = emptyList(),

    // ── 아래는 서버 응답에 없고 앱이 저장할 때 붙인다 ──
    /** 평가를 받은 날 yyyy-MM-dd */
    @SerializedName("evaluatedAt") val evaluatedAt: String = "",
    /** 평가 당시 기록의 지문. 지금 기록과 다르면 "기록이 바뀜" */
    @SerializedName("fingerprint") val fingerprint: String = "",
    /** 평가 당시 기록 일수. 화면에 "n일 기준"으로 보여준다 */
    @SerializedName("recordDays") val recordDays: Int = 0
) {
    val hasContent: Boolean
        get() = summary.isNotBlank() || diet.isNotBlank() || exercise.isNotBlank()

    fun toMap(): Map<String, Any> = mapOf(
        "score" to score,
        "headline" to headline,
        "summary" to summary,
        "diet" to diet,
        "exercise" to exercise,
        "actions" to actions,
        "evaluatedAt" to evaluatedAt,
        "fingerprint" to fingerprint,
        "recordDays" to recordDays
    )

    companion object {
        /** Firestore 문서 → 모델. 내용이 없으면 null */
        fun fromMap(raw: Map<String, Any?>?): PeriodEvaluation? {
            val map = raw ?: return null
            fun s(key: String): String = (map[key] as? String).orEmpty()
            val actions = (map["actions"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            val data = PeriodEvaluation(
                score = (map["score"] as? Number)?.toInt() ?: 0,
                headline = s("headline"),
                summary = s("summary"),
                diet = s("diet"),
                exercise = s("exercise"),
                actions = actions,
                evaluatedAt = s("evaluatedAt"),
                fingerprint = s("fingerprint"),
                recordDays = (map["recordDays"] as? Number)?.toInt() ?: 0
            )
            return data.takeIf { it.hasContent }
        }
    }
}

/** 평가 요청에 실리는 하루치. 서버가 집계에 쓰는 숫자와, 식단·운동 텍스트의 앞부분만 */
data class PeriodEvalDay(
    val date: String,
    val net: Int,
    val breakfastKcal: Int,
    val lunchKcal: Int,
    val dinnerKcal: Int,
    val snackKcal: Int,
    val exerciseKcal: Int,
    val carb: Float,
    val protein: Float,
    val fat: Float,
    val bmr: Int,
    val weight: Float,
    val steps: Int,
    val hasExercise: Boolean,
    val breakfast: String,
    val lunch: String,
    val dinner: String,
    val snack: String,
    val exercise: String
)

/** 어느 기간인지. type은 "week" / "month" / "year" */
data class PeriodEvalPeriod(
    val type: String,
    /** 저장 키에 쓰는 식별자. 주 202646 / 월 2026-11 / 연 2026 */
    val label: String,
    /** 화면에 보이는 제목 ("11월 3주차", "2026년 11월"). 프롬프트에 그대로 들어간다 */
    val title: String,
    val start: String,
    val end: String
)

data class PeriodEvalProfile(
    val height: Float,
    val weight: Float,
    val isMale: Boolean,
    val birthDate: String
)

/** "자주 먹은 음식" 한 줄 */
data class NamedCount(
    val name: String,
    val count: Int
)

data class PeriodEvalRequest(
    val period: PeriodEvalPeriod,
    val today: String,
    val profile: PeriodEvalProfile,
    val inbody: InbodyData?,
    val days: List<PeriodEvalDay>,
    val topFoods: List<NamedCount>,
    val topExercises: List<NamedCount>,
    val language: String,
    val appVersion: Int
)

data class PeriodEvalResponse(
    @SerializedName("evaluation") val evaluation: PeriodEvaluation? = null,
    @SerializedName("error") val error: String? = null
)
