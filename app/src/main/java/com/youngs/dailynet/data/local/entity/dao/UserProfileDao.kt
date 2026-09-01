package com.youngs.dailynet.data.local.entity.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {

    // 1. 프로필 정보 저장 및 수정 (onConflict로 오타 수정)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserProfileEntity)

    /**
     * 단일 행 조회 (id = 0으로 명확하게 지정).
     *
     * **행이 없으면 null이다.** 반환형을 non-null로 두면 Room이 만든 코드는 그대로 null을
     * 돌려주는데 호출부는 그걸 모르고 곧장 뒤져서 NPE가 난다. 실제로 그럴 수 있는 자리가 있다 —
     * MainViewModel.checkProfile이 네트워크 오류로 실패하면 로컬 행이 없는 채로 화면이 뜬다.
     */
    @Query("SELECT * FROM user_profile WHERE id = 0")
    suspend fun getProfile(): UserProfileEntity?

    // 3. Flow 형태로 프로필 실시간 관찰 (UI 데이터 바인딩용)
    @Query("SELECT * FROM user_profile WHERE id = 0")
    fun getProfileFlow(): Flow<UserProfileEntity?>

    @Query("DELETE FROM user_profile")
    suspend fun clearProfile()

    @Transaction
    @Query("UPDATE user_profile SET todayAnalysisCount = :count, lastAnalyzedDate = :date WHERE id = 0")
    suspend fun updateAnalysisInfo(count: Int, date: String) : Int

    @Transaction
    suspend fun updateAndGetLatest(count: Int, date: String): UserProfileEntity? {
        updateAnalysisInfo(count, date)
        return getProfile()
    }
}