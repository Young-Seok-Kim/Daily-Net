package com.youngs.dailynet.data.local.entity.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InbodyDao {
    /** 최근 측정이 위로 */
    @Query("SELECT * FROM inbody_records ORDER BY measuredAt DESC")
    fun observeAll(): Flow<List<InbodyRecordEntity>>

    @Query("SELECT * FROM inbody_records ORDER BY measuredAt DESC")
    suspend fun getAll(): List<InbodyRecordEntity>

    @Query("SELECT * FROM inbody_records WHERE measuredAt = :measuredAt")
    suspend fun get(measuredAt: String): InbodyRecordEntity?

    /** 가장 최근 실제 측정 (더미 제외). 식단 분석과 설정 요약이 쓴다 */
    @Query("SELECT * FROM inbody_records WHERE isDummy = 0 ORDER BY measuredAt DESC LIMIT 1")
    suspend fun getLatest(): InbodyRecordEntity?

    /** 계정에 올릴 실제 측정만 */
    @Query("SELECT * FROM inbody_records WHERE isDummy = 0 ORDER BY measuredAt DESC")
    suspend fun getAllReal(): List<InbodyRecordEntity>

    @Query("DELETE FROM inbody_records WHERE isDummy = 1")
    suspend fun clearDummy()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: InbodyRecordEntity)

    @Query("UPDATE inbody_records SET analysis = :analysis, analyzedAt = :analyzedAt WHERE measuredAt = :measuredAt")
    suspend fun setAnalysis(measuredAt: String, analysis: String, analyzedAt: String)

    @Query("DELETE FROM inbody_records WHERE measuredAt = :measuredAt")
    suspend fun delete(measuredAt: String)

    @Query("DELETE FROM inbody_records")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM inbody_records")
    suspend fun count(): Int
}
