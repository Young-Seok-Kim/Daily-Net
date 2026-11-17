package com.youngs.dailynet.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 운동 이용권 한 장. 헬스장 3개월(기간제)이나 PT 10회(횟수권) 같은 것.
 *
 * Firestore `users/{uid}/passes/{id}`가 원본이고 Room `exercise_passes`는 캐시다 (ExercisePassRepository).
 *
 * 횟수권의 "쓴 횟수"는 저장하지 않는다. 정산 기록의 운동 칸에 [keyword]가 들어간 날을 세어 매번 계산한다
 * (util/PassUsage.kt). 그래야 기록을 고치거나 지우면 횟수도 따라온다. 잘못 세어진 것만 [adjust]로 보정한다.
 */
@Entity(tableName = "exercise_passes")
data class ExercisePassEntity(
    @PrimaryKey val id: String,
    val name: String = "",
    /** [KIND_PERIOD] 또는 [KIND_COUNT] */
    val kind: String = KIND_PERIOD,
    /** yyyy-MM-dd. 이 날부터 센다 */
    val startDate: String = "",
    /** yyyy-MM-dd. 기간제는 필수, 횟수권은 유효기간(비우면 무기한) */
    val endDate: String = "",
    /** 횟수권의 전체 횟수. 기간제는 0 */
    val totalCount: Int = 0,
    /** 횟수권 자동 차감 키워드. 쉼표로 여러 개. 운동 칸에 하나라도 들어 있으면 그날 1회 */
    val keyword: String = "",
    /** 수동 보정. +1이면 한 번 더 쓴 것으로, -1이면 한 번 덜 쓴 것으로 센다 */
    val adjust: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isCount: Boolean get() = kind == KIND_COUNT

    /** 쉼표로 나눈 키워드. 빈 것은 뺀다 */
    val keywords: List<String>
        get() = keyword.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "kind" to kind,
        "startDate" to startDate,
        "endDate" to endDate,
        "totalCount" to totalCount,
        "keyword" to keyword,
        "adjust" to adjust,
        "createdAt" to createdAt
    )

    companion object {
        const val KIND_PERIOD = "period"
        const val KIND_COUNT = "count"

        /**
         * 새 이용권의 id. Firestore 콘솔에서 문서 이름만 보고도 무엇인지 알 수 있게 "이름_만든시각"으로 만든다.
         * 이름은 나중에 바꿔도 id는 그대로다(캘린더 일정·Firestore 문서가 이 값으로 묶여 있다).
         * "/"는 Firestore 문서 id에 못 쓰고, 공백은 보기 싫어 밑줄로 바꾼다.
         */
        fun newId(name: String, now: Long = System.currentTimeMillis()): String {
            val base = name.trim().replace(Regex("[/\s]+"), "_").take(20).ifBlank { "pass" }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(now))
            return "${base}_$stamp"
        }

        /** Firestore 문서 → 엔티티. id나 이름이 없으면 null */
        fun fromMap(raw: Map<String, Any?>?): ExercisePassEntity? {
            val map = raw ?: return null
            val id = (map["id"] as? String).orEmpty()
            val name = (map["name"] as? String).orEmpty()
            if (id.isBlank() || name.isBlank()) return null
            return ExercisePassEntity(
                id = id,
                name = name,
                kind = (map["kind"] as? String).takeIf { it == KIND_COUNT } ?: KIND_PERIOD,
                startDate = (map["startDate"] as? String).orEmpty(),
                endDate = (map["endDate"] as? String).orEmpty(),
                totalCount = (map["totalCount"] as? Number)?.toInt() ?: 0,
                keyword = (map["keyword"] as? String).orEmpty(),
                adjust = (map["adjust"] as? Number)?.toInt() ?: 0,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
