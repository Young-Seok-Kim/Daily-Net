package com.youngs.dailynet.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.youngs.dailynet.data.dao.SettlementDao
import com.youngs.dailynet.data.model.SettlementModel

@Database(entities = [SettlementModel::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settlementDao(): SettlementDao
}