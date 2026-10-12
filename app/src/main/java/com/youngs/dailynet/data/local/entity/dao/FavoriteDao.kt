package com.youngs.dailynet.data.local.entity.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youngs.dailynet.data.local.entity.FavoriteItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    /** 종류별 전체 항목. 숨긴 것도 포함하므로 화면에서 걸러야 한다 (정렬은 화면 쪽에서 한다) */
    @Query("SELECT * FROM favorites WHERE kind = :kind")
    fun observeByKind(kind: String): Flow<List<FavoriteItemEntity>>

    @Query("SELECT * FROM favorites WHERE kind = :kind AND text = :text")
    suspend fun get(kind: String, text: String): FavoriteItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FavoriteItemEntity)

    @Query("UPDATE favorites SET pinned = :pinned WHERE kind = :kind AND text = :text")
    suspend fun setPinned(kind: String, text: String, pinned: Boolean)

    @Query("UPDATE favorites SET hidden = :hidden WHERE kind = :kind AND text = :text")
    suspend fun setHidden(kind: String, text: String, hidden: Boolean)

    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun count(): Int
}
