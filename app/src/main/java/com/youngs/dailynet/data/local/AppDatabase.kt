package com.youngs.dailynet.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.youngs.dailynet.data.local.entity.dao.SettlementDao
import com.youngs.dailynet.data.local.entity.dao.WeightDao
import com.youngs.dailynet.data.model.SettlementModel
import com.youngs.dailynet.data.local.entity.WeightEntity
import com.youngs.dailynet.data.local.entity.UserProfileEntity

@Database(
    entities = [
        SettlementModel::class,
        WeightEntity::class,
        UserProfileEntity::class
    ],
    version = 2, // 👈 엔티티 추가로 인해 버전을 올립니다.
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settlementDao(): SettlementDao
    abstract fun weightDao(): WeightDao // 👈 이 메서드가 있어야 에러가 안 납니다.
}