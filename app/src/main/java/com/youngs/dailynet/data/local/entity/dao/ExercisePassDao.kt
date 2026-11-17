package com.youngs.dailynet.data.local.entity.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youngs.dailynet.data.local.entity.ExercisePassEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExercisePassDao {
    @Query("SELECT * FROM exercise_passes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExercisePassEntity>>

    @Query("SELECT * FROM exercise_passes ORDER BY createdAt DESC")
    suspend fun getAll(): List<ExercisePassEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pass: ExercisePassEntity)

    @Query("DELETE FROM exercise_passes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM exercise_passes")
    suspend fun clear()
}
