package com.youngs.dailynet.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.youngs.dailynet.data.local.entity.dao.DailyRecordDao
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.local.entity.dao.UserProfileDao

@Database(
    entities = [
        DailyRecordModel::class,
        UserProfileEntity::class
    ],
    version = 6, // 👈 steps / stepsManual 필드 추가로 스키마 변경 → 버전 상향 (파괴적 마이그레이션)
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailyRecordDao(): DailyRecordDao
    abstract fun userProfileDao(): UserProfileDao
}