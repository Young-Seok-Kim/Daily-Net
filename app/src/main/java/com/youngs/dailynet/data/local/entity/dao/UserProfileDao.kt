package com.youngs.dailynet.data.local.entity.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {

    // 1. 프로필 정보 저장 및 수정 (onConflict로 오타 수정)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserProfileEntity)

    // 2. 단일 행 조회 (id = 0으로 명확하게 지정)
    @Query("SELECT * FROM user_profile WHERE id = 0")
    suspend fun getProfile(): UserProfileEntity?

    // 3. Flow 형태로 프로필 실시간 관찰 (UI 데이터 바인딩용)
    @Query("SELECT * FROM user_profile WHERE id = 0")
    fun getProfileFlow(): Flow<UserProfileEntity?>

    @Query("DELETE FROM user_profile")
    suspend fun clearProfile()
}