package com.youngs.dailynet.util

import com.youngs.dailynet.data.local.entity.FavoriteItemEntity

/**
 * 즐겨찾기 중 **사용자가 손댄 것**만 Firestore에 올리고 내려받기 위한 변환.
 *
 * 횟수(useCount 등)는 올리지 않는다. 기록(settlements)이 이미 Firestore에 있어 새 기기에서도
 * 백필로 그대로 다시 센다. 반면 고정(★)·숨김(✕)·직접 추가는 기록에 없어서 앱을 지우면 사라졌다.
 * 그래서 그 셋만 `users/{uid}/favorites/items` 문서 하나에 목록으로 둔다.
 *
 * 문서 하나에 목록으로 두는 이유: 항목 이름을 문서 ID나 필드 키로 쓰면 "/"나 "."가 들어간 메뉴명이 깨진다.
 */
object FavoriteCloud {
    const val DOC_ID = "items"
    const val FIELD_ITEMS = "items"

    data class Entry(
        val kind: String,
        val text: String,
        val pinnedFields: String,
        val hidden: Boolean,
        /** 분석에 나온 적 없이 시트에서 직접 추가한 항목. 새 기기에서 백필로는 안 생기므로 만들어 줘야 한다 */
        val manual: Boolean
    )

    /** 올릴 가치가 있는 항목만 고른다: 고정했거나, 숨겼거나, 직접 추가한 것 */
    fun toEntries(items: List<FavoriteItemEntity>): List<Entry> = items
        .filter { it.isPinnedAnywhere || it.hidden || it.useCount == 0 }
        .map { Entry(it.kind, it.text, it.pinnedFields, it.hidden, manual = it.useCount == 0) }

    fun toMap(entry: Entry): Map<String, Any> = mapOf(
        "kind" to entry.kind,
        "text" to entry.text,
        "pinnedFields" to entry.pinnedFields,
        "hidden" to entry.hidden,
        "manual" to entry.manual
    )

    /** Firestore에서 읽은 목록. 형식이 어긋난 원소는 버린다 */
    fun fromList(raw: Any?): List<Entry> = (raw as? List<*>).orEmpty().mapNotNull { element ->
        val map = element as? Map<*, *> ?: return@mapNotNull null
        val kind = map["kind"] as? String ?: return@mapNotNull null
        val text = (map["text"] as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: return@mapNotNull null
        Entry(
            kind = kind,
            text = text,
            pinnedFields = map["pinnedFields"] as? String ?: "",
            hidden = map["hidden"] as? Boolean ?: false,
            manual = map["manual"] as? Boolean ?: false
        )
    }

    /**
     * 내려받은 항목 하나를 기기 항목에 얹는다. 횟수는 기기 것을 그대로 두고 고정·숨김만 덮어쓴다.
     * 기기에 없는 항목은 횟수 0으로 새로 만든다 (직접 추가했던 것이거나, 아직 백필 전인 것).
     */
    fun apply(existing: FavoriteItemEntity?, entry: Entry, now: Long): FavoriteItemEntity =
        (existing ?: FavoriteItemEntity(kind = entry.kind, text = entry.text, lastUsedAt = now))
            .copy(pinnedFields = entry.pinnedFields, hidden = entry.hidden)
}
