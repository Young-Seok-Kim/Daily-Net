package com.youngs.dailynet.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.youngs.dailynet.data.local.entity.dao.DailyRecordDao
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.local.entity.dao.UserProfileDao

@Database(
    entities = [
        DailyRecordModel::class,
        UserProfileEntity::class
    ],
    version = 7, // 👈 user_profile에 email 추가 (MIGRATION_6_7로 기존 데이터 유지)
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailyRecordDao(): DailyRecordDao
    abstract fun userProfileDao(): UserProfileDao
}

/**
 * user_profile에 email 컬럼 추가.
 *
 * 마이그레이션을 안 쓰고 버전만 올리면 fallbackToDestructiveMigration이 걸려
 * 기존 사용자의 정산 기록 캐시까지 통째로 지워진다. (Firestore에서 다시 받아오긴 하지만
 * 업데이트 직후 첫 실행이 느려지고, 오프라인이면 빈 화면이 된다)
 * 컬럼 하나 추가하는 것뿐이라 ALTER TABLE로 충분하다.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_profile ADD COLUMN email TEXT NOT NULL DEFAULT ''")
    }
}