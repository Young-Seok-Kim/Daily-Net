package com.youngs.dailynet.data.local.entity.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youngs.dailynet.data.model.SettlementModel

@Dao
interface SettlementDao {
    // 오늘 날짜로 저장된 데이터 가져오기
    @Query("SELECT * FROM daily_drafts WHERE date = :date")
    suspend fun getSettlementByDate(date: String): SettlementModel?

    // 저장 또는 업데이트 (isFinalizing 상태까지 한꺼번에 처리)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(settlement: SettlementModel)

    // (옵션) 나중에 히스토리 볼 때 쓸 전체 목록
    @Query("SELECT * FROM daily_drafts ORDER BY date DESC")
    suspend fun getAllSettlements(): List<SettlementModel>
}