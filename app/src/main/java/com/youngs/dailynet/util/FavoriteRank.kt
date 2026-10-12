package com.youngs.dailynet.util

import com.youngs.dailynet.data.local.entity.FavoriteItemEntity

/**
 * 즐겨찾기 정렬과 입력칸에 이어 붙이는 규칙. 화면·ViewModel이 공유하고, 유닛 테스트로 고정한다.
 */
object FavoriteRank {

    /** 입력칸 아래 칩 줄에 보여줄 최대 개수. 그 뒤는 "더보기"로 넘긴다 */
    const val CHIP_LIMIT = 8

    /**
     * [field] 칸에 보여줄 순서로 정렬한다. 숨긴 항목은 뺀다.
     *
     * **그 칸에서** 고정한 항목이 먼저, 그 다음은 **그 칸에서 쓰인 횟수**가 많은 순이다.
     * 점심에서 고정한 것은 아침 칸에서는 고정으로 치지 않는다. 점심 칸에는 점심에
     * 먹던 것이 먼저 나오고, 다른 끼니에서만 먹던 것은 뒤로 간다. 횟수가 같으면 전체 횟수,
     * 그래도 같으면 최근에 쓴 것이 앞이다.
     */
    fun rankForField(items: List<FavoriteItemEntity>, field: String): List<FavoriteItemEntity> =
        items.filter { !it.hidden }
            .sortedWith(
                compareByDescending<FavoriteItemEntity> { it.isPinnedFor(field) }
                    .thenByDescending { it.countFor(field) }
                    .thenByDescending { it.useCount }
                    .thenByDescending { it.lastUsedAt }
                    .thenBy { it.text }
            )

    /** 시트의 "전체" 탭 순서. [field] 칸에서 고정한 것이 먼저, 그 다음은 끼니를 가리지 않고 많이 쓴 순 */
    fun rankOverall(items: List<FavoriteItemEntity>, field: String): List<FavoriteItemEntity> =
        items.filter { !it.hidden }
            .sortedWith(
                compareByDescending<FavoriteItemEntity> { it.isPinnedFor(field) }
                    .thenByDescending { it.useCount }
                    .thenByDescending { it.lastUsedAt }
                    .thenBy { it.text }
            )

    /**
     * 입력칸 텍스트 끝에 [item]을 ", "로 이어 붙인다.
     *
     * 이미 같은 항목이 들어 있으면 그대로 돌려준다. 칩을 두 번 눌러 "샐러드, 샐러드"가 되는 것보다
     * 아무 일도 안 일어나는 쪽이 낫다. 비어 있으면 그냥 그 항목이 된다.
     */
    fun append(current: String, item: String): String {
        val trimmed = item.trim()
        if (trimmed.isEmpty()) return current
        val existing = splitItems(current)
        if (existing.any { it.equals(trimmed, ignoreCase = true) }) return current
        val base = current.trimEnd().trimEnd(',').trimEnd()
        return if (base.isEmpty()) trimmed else "$base, $trimmed"
    }

    /** 여러 항목을 한 번에 이어 붙인다 (시트에서 여러 개 골랐을 때) */
    fun appendAll(current: String, items: List<String>): String =
        items.fold(current) { acc, item -> append(acc, item) }

    /** 입력칸 텍스트를 항목 단위로 나눈다. 쉼표 기준이고 빈 조각은 버린다 */
    fun splitItems(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
