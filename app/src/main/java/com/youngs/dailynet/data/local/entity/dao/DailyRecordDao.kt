package com.youngs.dailynet.data.local.entity.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youngs.dailynet.data.model.DailyRecordModel
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyRecordDao {
    @Query("SELECT * FROM daily_records WHERE date = :date")
    suspend fun getDailyRecordByDate(date: String): DailyRecordModel?

    /**
     * 하루치 기록을 계속 지켜본다. 홈 화면 위젯이 쓴다.
     *
     * 위젯이 살아 있는 동안에는 이 흐름으로 바로 다시 그린다.
     * 밖에서 갱신을 밀어넣는 방식만 쓰면 WorkManager를 거치느라
     * 분석을 끝내고도 위젯에 반영되기까지 1분 넘게 걸릴 수 있다.
     */
    @Query("SELECT * FROM daily_records WHERE date = :date")
    fun observeDailyRecordByDate(date: String): Flow<DailyRecordModel?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(dailyRecord: DailyRecordModel)

    @Query("SELECT * FROM daily_records ORDER BY date DESC")
    fun getAllDailyRecordRoomFlow(): Flow<List<DailyRecordModel>>

    @Query("SELECT COUNT(*) FROM daily_records")
    suspend fun getCount(): Int

    @Query("SELECT weight FROM daily_records WHERE weight > 0.0 ORDER BY date DESC LIMIT 1")
    suspend fun getLatestWeight(): Float?

    @Query("SELECT weight FROM daily_records WHERE weight > 0.0 AND date < :targetDate ORDER BY date DESC LIMIT 1")
    suspend fun getLatestWeightBefore(targetDate: String): Float?

    @Query("DELETE FROM daily_records WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM daily_records")
    suspend fun clearAllDailyRecord()
}