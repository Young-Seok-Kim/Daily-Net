package com.youngs.dailynet

import com.youngs.dailynet.data.local.entity.FavoriteItemEntity
import com.youngs.dailynet.data.local.entity.FavoriteItemEntity.Companion.KIND_MEAL
import com.youngs.dailynet.util.FavoriteRank
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 입력 즐겨찾기의 정렬과 이어 붙이기 규칙 검증.
 */
class FavoriteRankTest {

    private fun meal(
        text: String,
        lunch: Int = 0,
        dinner: Int = 0,
        pinned: String = "",
        hidden: Boolean = false,
        lastUsedAt: Long = 0L
    ) = FavoriteItemEntity(
        kind = KIND_MEAL, text = text,
        useCount = lunch + dinner, lunchCount = lunch, dinnerCount = dinner,
        pinnedFields = pinned, hidden = hidden, lastUsedAt = lastUsedAt
    )

    @Test
    fun `그 칸에서 고정한 항목이 먼저, 그 다음은 그 칸에서 쓴 횟수 순`() {
        val items = listOf(
            meal("샐러드", dinner = 10),          // 저녁에만 많이 먹음
            meal("김치찌개", lunch = 3),
            meal("닭가슴살", lunch = 1, pinned = "lunch"), // 점심에서만 고정
            meal("현미밥", lunch = 5)
        )

        val lunch = FavoriteRank.rankForField(items, "lunch").map { it.text }
        assertEquals(listOf("닭가슴살", "현미밥", "김치찌개", "샐러드"), lunch)

        // 점심에서 고정한 건 저녁 칸에서는 고정이 아니다. 횟수대로 간다
        val dinner = FavoriteRank.rankForField(items, "dinner").map { it.text }
        assertEquals(listOf("샐러드", "현미밥", "김치찌개", "닭가슴살"), dinner)
    }

    @Test
    fun `칸별 고정을 켜고 끄면 다른 칸은 그대로`() {
        val item = meal("닭가슴살").withPinned("lunch", true).withPinned("dinner", true)
        assertEquals(true, item.isPinnedFor("lunch"))
        assertEquals(true, item.isPinnedFor("dinner"))
        assertEquals(false, item.isPinnedFor("breakfast"))

        val unpinned = item.withPinned("lunch", false)
        assertEquals(false, unpinned.isPinnedFor("lunch"))
        assertEquals(true, unpinned.isPinnedFor("dinner"))
        assertEquals("dinner", unpinned.pinnedFields)
        assertEquals(false, unpinned.withPinned("dinner", false).isPinnedAnywhere)
    }

    @Test
    fun `숨긴 항목은 어디에도 나오지 않는다`() {
        val items = listOf(
            meal("라면", lunch = 20, hidden = true),
            meal("현미밥", lunch = 5)
        )
        assertEquals(listOf("현미밥"), FavoriteRank.rankForField(items, "lunch").map { it.text })
        assertEquals(listOf("현미밥"), FavoriteRank.rankOverall(items, "lunch").map { it.text })
    }

    @Test
    fun `횟수가 같으면 최근에 쓴 것이 앞`() {
        val items = listOf(
            meal("A", lunch = 2, lastUsedAt = 100),
            meal("B", lunch = 2, lastUsedAt = 300),
            meal("C", lunch = 2, lastUsedAt = 200)
        )
        assertEquals(listOf("B", "C", "A"), FavoriteRank.rankForField(items, "lunch").map { it.text })
    }

    @Test
    fun `빈 칸에는 그대로, 내용이 있으면 쉼표로 이어 붙인다`() {
        assertEquals("샐러드", FavoriteRank.append("", "샐러드"))
        assertEquals("김치찌개, 샐러드", FavoriteRank.append("김치찌개", "샐러드"))
        // 끝에 쉼표나 공백이 남아 있어도 깔끔하게 붙는다
        assertEquals("김치찌개, 샐러드", FavoriteRank.append("김치찌개, ", "샐러드"))
    }

    @Test
    fun `이미 들어 있는 항목은 다시 붙이지 않는다`() {
        assertEquals("김치찌개, 샐러드", FavoriteRank.append("김치찌개, 샐러드", "샐러드"))
        assertEquals("김치찌개, 샐러드", FavoriteRank.append("김치찌개, 샐러드", " 샐러드 "))
        assertEquals("Salad", FavoriteRank.append("Salad", "salad"))
    }

    @Test
    fun `여러 개를 한 번에 붙이면 순서대로, 중복은 빼고`() {
        val result = FavoriteRank.appendAll("현미밥", listOf("샐러드", "현미밥", "두부"))
        assertEquals("현미밥, 샐러드, 두부", result)
    }

    @Test
    fun `쉼표로 나눌 때 빈 조각은 버린다`() {
        assertEquals(listOf("김치찌개", "현미밥"), FavoriteRank.splitItems(" 김치찌개 ,, 현미밥, "))
        assertEquals(emptyList<String>(), FavoriteRank.splitItems("  "))
    }
}
