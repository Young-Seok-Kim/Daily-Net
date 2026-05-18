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

    // 💡 [수정] List 대신 Flow를 반환하도록 변경하여 로컬 DB 데이터 변경을 실시간 감지합니다.
    @Query("SELECT * FROM daily_drafts ORDER BY date DESC")
    fun getAllSettlementsRoomFlow(): Flow<List<SettlementModel>>
}