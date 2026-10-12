package com.youngs.dailynet.data.local.entity

import androidx.room.Entity

/**
 * 입력 화면 즐겨찾기 한 항목. 메뉴 하나("현미밥 한 공기") 또는 운동 하나("스쿼트 100개").
 *
 * 사용자가 직접 저장하는 게 아니라, **분석이 끝날 때마다 서버가 정규화해 준 항목명**을
 * 자동으로 모은다 ([com.youngs.dailynet.data.repository.FavoriteRepository.recordUsage]).
 * 그래서 오타나 표현 차이로 같은 음식이 여러 개 쌓이지 않고, 며칠만 써도 "자주 먹는 것"이
 * 저절로 채워진다. 사용자는 그 위에 고정(★)과 숨김(✕)만 얹는다.
 *
 * 끼니별 횟수를 따로 두는 이유: 점심 칸 아래에는 점심에 먹던 것을 먼저 보여줘야 한다.
 * 그렇다고 끼니마다 별도 항목으로 쪼개면 아침에 먹던 걸 점심에 넣을 때 못 찾는다.
 * 항목은 하나로 두고 끼니별 횟수만 컬럼으로 센다.
 */
@Entity(tableName = "favorites", primaryKeys = ["kind", "text"])
data class FavoriteItemEntity(
    /** [KIND_MEAL] 또는 [KIND_EXERCISE]. 식사와 운동은 서로 섞이면 안 된다 */
    val kind: String,
    val text: String,

    /** 분석 결과에 등장한 총 횟수. 직접 추가한 항목은 0에서 시작한다 */
    val useCount: Int = 0,
    val breakfastCount: Int = 0,
    val lunchCount: Int = 0,
    val dinnerCount: Int = 0,
    val snackCount: Int = 0,

    /** 평균 kcal을 내기 위한 누적값. 시트에서 "평균 ○○ kcal" 힌트로만 쓴다 */
    val kcalSum: Int = 0,
    val kcalCount: Int = 0,

    /** 마지막으로 분석 결과에 등장한 시각(ms). 같은 횟수면 최근 것이 앞에 온다 */
    val lastUsedAt: Long = 0L,

    /**
     * 사용자가 ★로 고정한 **칸** 목록. "lunch,dinner"처럼 쉼표로 잇는다. 비어 있으면 고정 안 함.
     *
     * 처음엔 항목당 고정 하나(Boolean)였는데, 점심에서 고정하면 아침·저녁 칸에서도 맨 앞에 와버렸다.
     * 고정은 "이 칸에서 자주 넣는다"는 뜻이라 칸마다 따로 둔다. 운동은 칸이 하나라 "exercise"만 들어간다.
     */
    val pinnedFields: String = "",
    /** 사용자가 ✕로 숨긴 항목. 어디에도 보이지 않지만, 같은 이름을 다시 추가하면 풀린다 */
    val hidden: Boolean = false
) {
    /** 평균 kcal. 분석에 한 번도 안 나온(직접 추가한) 항목은 null */
    val averageKcal: Int?
        get() = if (kcalCount > 0) kcalSum / kcalCount else null

    /** [field] 칸에서 고정했는지 */
    fun isPinnedFor(field: String): Boolean = field in pinnedFieldSet

    /** 고정한 칸이 하나라도 있는지 */
    val isPinnedAnywhere: Boolean
        get() = pinnedFields.isNotBlank()

    /** [field] 칸의 고정을 켜거나 끈 사본 */
    fun withPinned(field: String, pinned: Boolean): FavoriteItemEntity {
        val set = pinnedFieldSet.toMutableSet()
        if (pinned) set.add(field) else set.remove(field)
        return copy(pinnedFields = set.sorted().joinToString(","))
    }

    private val pinnedFieldSet: Set<String>
        get() = pinnedFields.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 특정 끼니 칸에서 쓰인 횟수. 운동이나 비고처럼 끼니가 아니면 0 */
    fun countFor(field: String): Int = when (field) {
        "breakfast" -> breakfastCount
        "lunch" -> lunchCount
        "dinner" -> dinnerCount
        "snack" -> snackCount
        else -> 0
    }

    companion object {
        const val KIND_MEAL = "meal"
        const val KIND_EXERCISE = "exercise"

        /** 입력칸 이름 → 즐겨찾기 종류. 비고처럼 즐겨찾기가 없는 칸이면 null */
        fun kindOf(field: String): String? = when (field) {
            "breakfast", "lunch", "dinner", "snack" -> KIND_MEAL
            "exercise" -> KIND_EXERCISE
            else -> null
        }
    }
}
