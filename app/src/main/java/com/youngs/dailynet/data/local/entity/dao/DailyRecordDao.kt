package com.youngs.dailynet.data.local.entity.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youngs.dailynet.data.model.DailyRecordModel
import kotlinx.coroutines.flow.Flow // 👈 추가

@Dao
interface DailyRecordDao {
    @Query("SELECT * FROM daily_drafts WHERE date = :date")
    suspend fun getDailyRecordByDate(date: String): DailyRecordModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(dailyRecord: DailyRecordModel)

    @Query("SELECT * FROM daily_drafts ORDER BY date DESC")
    fun getAllDailyRecordRoomFlow(): Flow<List<DailyRecordModel>>

    @Query("SELECT weight FROM daily_drafts WHERE weight > 0.0 ORDER BY date DESC LIMIT 1")
    suspend fun getLatestWeight(): Float?

    @Query("SELECT weight FROM daily_drafts WHERE weight > 0.0 AND date < :targetDate ORDER BY date DESC LIMIT 1")
    suspend fun getLatestWeightBefore(targetDate: String): Float?

    @Query("DELETE FROM daily_drafts WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM daily_drafts")
    suspend fun clearAllDailyRecord()
}