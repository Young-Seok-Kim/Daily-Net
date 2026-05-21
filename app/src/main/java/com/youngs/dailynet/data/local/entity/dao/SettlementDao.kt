package com.youngs.dailynet.data.local.entity.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youngs.dailynet.data.model.SettlementModel
import kotlinx.coroutines.flow.Flow // 👈 추가

@Dao
interface SettlementDao {
    @Query("SELECT * FROM daily_drafts WHERE date = :date")
    suspend fun getSettlementByDate(date: String): SettlementModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(settlement: SettlementModel)

    @Query("SELECT * FROM daily_drafts ORDER BY date DESC")
    fun getAllSettlementsRoomFlow(): Flow<List<SettlementModel>>

    @Query("SELECT currentWeight FROM daily_drafts WHERE currentWeight > 0.0 ORDER BY date DESC LIMIT 1")
    suspend fun getLatestWeight(): Float?

    @Query("DELETE FROM daily_drafts WHERE date = :date")
    suspend fun deleteByDate(date: String)
}