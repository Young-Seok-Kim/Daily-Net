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

/**
 * ⚠️ 컬럼을 바꾸면 반드시 version을 올리고 마이그레이션을 추가할 것.
 *
 * Room은 스키마의 해시를 DB에 저장해두고 열 때마다 대조한다. version은 그대로 두고
 * 엔티티만 고치면 해시가 어긋나 **앱이 시작하자마자 죽는다.**
 * (`Room cannot verify the data integrity ... Expected identity hash`)
 *
 * 특히 아직 배포하지 않은 버전의 스키마를 개발 중에 또 고칠 때가 위험하다.
 * 이때는 version을 올릴 필요가 없는 대신, 테스트 기기의 앱 데이터(또는 DB 파일)를
 * 지워야 한다. 이미 그 버전의 옛 스키마로 만들어진 DB가 폰에 남아 있기 때문이다.
 */
@Database(
    entities = [
        DailyRecordModel::class,
        UserProfileEntity::class
    ],
    version = 8, // 👈 daily_records에 분석 집계값 추가 (MIGRATION_7_8로 기존 데이터 유지)
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

/**
 * daily_records에 분석 집계값(끼니별 칼로리·탄단지·기초대사량) 컬럼 추가.
 *
 * MIGRATION_6_7과 같은 이유로 반드시 필요하다. 정의하지 않으면
 * fallbackToDestructiveMigration이 걸려 기존 사용자의 정산 기록 캐시가 통째로 지워진다.
 *
 * 기존 행은 전부 기본값 0이 된다. 예전에 분석한 기록에는 이 데이터가 애초에 없었고,
 * 소급해서 만들어낼 방법도 없기 때문이다. 화면에서는 DailyRecordModel.hasNutritionData로
 * 걸러서 "데이터 없음"으로 다룬다.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 권장 칼로리는 저장하지 않는다. bmr에서 그대로 계산되는 값이라 따로 둘 이유가 없다.
        val intColumns = listOf(
            "breakfastKcal", "lunchKcal", "dinnerKcal", "snackKcal",
            "exerciseKcal", "bmr"
        )
        intColumns.forEach {
            db.execSQL("ALTER TABLE daily_records ADD COLUMN $it INTEGER NOT NULL DEFAULT 0")
        }

        val floatColumns = listOf("carbGram", "proteinGram", "fatGram")
        floatColumns.forEach {
            db.execSQL("ALTER TABLE daily_records ADD COLUMN $it REAL NOT NULL DEFAULT 0")
        }
    }
}