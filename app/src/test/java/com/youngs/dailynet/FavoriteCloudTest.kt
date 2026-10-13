package com.youngs.dailynet

import com.youngs.dailynet.data.local.entity.FavoriteItemEntity
import com.youngs.dailynet.data.local.entity.FavoriteItemEntity.Companion.KIND_MEAL
import com.youngs.dailynet.util.FavoriteCloud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 즐겨찾기 고정·숨김·직접 추가를 Firestore에 올리고 내려받는 변환 검증.
 */
class FavoriteCloudTest {

    private fun meal(text: String, useCount: Int = 3, pinned: String = "", hidden: Boolean = false) =
        FavoriteItemEntity(kind = KIND_MEAL, text = text, useCount = useCount, pinnedFields = pinned, hidden = hidden)

    @Test
    fun onlyTouchedItemsAreUploaded() {
        val entries = FavoriteCloud.toEntries(
            listOf(
                meal("밥"),                            // 자동 집계만 된 것 → 안 올림
                meal("닭가슴살", pinned = "lunch"),
                meal("과자", hidden = true),
                meal("직접 추가", useCount = 0)
            )
        )
        assertEquals(listOf("닭가슴살", "과자", "직접 추가"), entries.map { it.text })
        assertTrue(entries.first { it.text == "직접 추가" }.manual)
        assertFalse(entries.first { it.text == "닭가슴살" }.manual)
    }

    @Test
    fun mapRoundTripKeepsEverything() {
        val entry = FavoriteCloud.Entry(KIND_MEAL, "밥 1공기, 조금/반", "lunch,dinner", hidden = false, manual = true)
        val back = FavoriteCloud.fromList(listOf(FavoriteCloud.toMap(entry)))
        assertEquals(listOf(entry), back)
    }

    @Test
    fun malformedElementsAreDropped() {
        val back = FavoriteCloud.fromList(
            listOf("문자열", mapOf("kind" to KIND_MEAL), mapOf("kind" to KIND_MEAL, "text" to "  "))
        )
        assertTrue(back.isEmpty())
    }

    @Test
    fun applyKeepsLocalCountsAndOverridesPinHidden() {
        val local = meal("닭가슴살", useCount = 7)
        val applied = FavoriteCloud.apply(
            local, FavoriteCloud.Entry(KIND_MEAL, "닭가슴살", "lunch", hidden = false, manual = false), now = 1L
        )
        assertEquals(7, applied.useCount)
        assertEquals("lunch", applied.pinnedFields)
    }

    @Test
    fun applyCreatesMissingItemWithZeroCount() {
        val applied = FavoriteCloud.apply(
            null, FavoriteCloud.Entry(KIND_MEAL, "직접 추가", "", hidden = false, manual = true), now = 99L
        )
        assertEquals(0, applied.useCount)
        assertEquals(99L, applied.lastUsedAt)
        assertEquals("직접 추가", applied.text)
    }
}
