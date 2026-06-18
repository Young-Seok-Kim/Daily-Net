package com.youngs.dailynet.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.youngs.dailynet.data.local.entity.dao.SettlementDao
import com.youngs.dailynet.data.model.SettlementModel
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.local.entity.dao.UserProfileDao

@Database(
    entities = [
        SettlementModel::class,
        UserProfileEntity::class
    ],
    version = 3, // 👈 엔티티 추가로 인해 버전을 올립니다.
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settlementDao(): SettlementDao
    abstract fun userProfileDao(): UserProfileDao
}