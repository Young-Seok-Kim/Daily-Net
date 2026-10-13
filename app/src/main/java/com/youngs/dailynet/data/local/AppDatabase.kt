package com.youngs.dailynet.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.youngs.dailynet.data.local.entity.dao.DailyRecordDao
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.local.entity.FavoriteItemEntity
import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
import com.youngs.dailynet.data.local.entity.dao.InbodyDao
import com.youngs.dailynet.data.local.entity.dao.FavoriteDao
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
        UserProfileEntity::class,
        FavoriteItemEntity::class,
        InbodyRecordEntity::class
    ],
    version = 14, // 👈 inbody_records 표 (인바디 이력, MIGRATION_13_14)
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailyRecordDao(): DailyRecordDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun inbodyDao(): InbodyDao
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

/**
 * daily_records에 항목별 분석 결과(JSON)와 사용자 수정 여부 컬럼 추가.
 *
 * 기존 행은 analysisDetail이 NULL이다. 예전 기록에는 이 구조가 없었고 텍스트만 있으니
 * 화면이 텍스트로 물러난다. 사용자가 고칠 수 있는 건 b33 이후 분석한 기록부터다.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE daily_records ADD COLUMN analysisDetail TEXT")
        db.execSQL("ALTER TABLE daily_records ADD COLUMN resultEdited INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 입력 즐겨찾기 테이블(favorites) 추가. 기존 테이블은 건드리지 않는다.
 *
 * 컬럼 정의는 [com.youngs.dailynet.data.local.entity.FavoriteItemEntity]와 글자 하나까지 같아야 한다.
 * Room은 앱을 열 때 엔티티에서 기대하는 스키마와 실제 테이블을 대조하므로, NOT NULL 하나만
 * 어긋나도 "Migration didn't properly handle" 예외로 시작하자마자 죽는다.
 *
 * 기존 사용자의 항목 목록은 비어 있지 않다. 앱을 켜면 Room에 쌓인 과거 분석 결과를 한 번 훑어
 * 채운다 ([com.youngs.dailynet.data.repository.FavoriteRepository.backfillIfNeeded]).
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `favorites` (
                `kind` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `useCount` INTEGER NOT NULL,
                `breakfastCount` INTEGER NOT NULL,
                `lunchCount` INTEGER NOT NULL,
                `dinnerCount` INTEGER NOT NULL,
                `snackCount` INTEGER NOT NULL,
                `kcalSum` INTEGER NOT NULL,
                `kcalCount` INTEGER NOT NULL,
                `lastUsedAt` INTEGER NOT NULL,
                `pinned` INTEGER NOT NULL,
                `hidden` INTEGER NOT NULL,
                PRIMARY KEY(`kind`, `text`)
            )
            """.trimIndent()
        )
    }
}

/**
 * favorites 고정을 항목당 하나(pinned)에서 칸별(pinnedFields)로 바꾼다.
 *
 * 점심에서 ★를 누르면 아침·저녁 칸에서도 맨 앞에 와버려서, 어느 칸에서 고정했는지를 남기게 했다.
 * 기존에 고정했던 항목은 식사면 네 끼 전부, 운동이면 운동 칸에 고정한 것으로 옮긴다 (보이던 그대로).
 *
 * 컬럼을 빼야 하는데 minSdk 26의 SQLite는 DROP COLUMN을 못 하므로 새 테이블을 만들어 옮기고 바꿔 끼운다.
 * 컬럼 순서·NOT NULL은 엔티티와 글자 하나까지 같아야 한다 (MIGRATION_9_10 설명 참고).
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `favorites_new` (
                `kind` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `useCount` INTEGER NOT NULL,
                `breakfastCount` INTEGER NOT NULL,
                `lunchCount` INTEGER NOT NULL,
                `dinnerCount` INTEGER NOT NULL,
                `snackCount` INTEGER NOT NULL,
                `kcalSum` INTEGER NOT NULL,
                `kcalCount` INTEGER NOT NULL,
                `lastUsedAt` INTEGER NOT NULL,
                `pinnedFields` TEXT NOT NULL,
                `hidden` INTEGER NOT NULL,
                PRIMARY KEY(`kind`, `text`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `favorites_new`
                (`kind`, `text`, `useCount`, `breakfastCount`, `lunchCount`, `dinnerCount`, `snackCount`,
                 `kcalSum`, `kcalCount`, `lastUsedAt`, `pinnedFields`, `hidden`)
            SELECT `kind`, `text`, `useCount`, `breakfastCount`, `lunchCount`, `dinnerCount`, `snackCount`,
                 `kcalSum`, `kcalCount`, `lastUsedAt`,
                 CASE
                     WHEN `pinned` = 0 THEN ''
                     WHEN `kind` = 'meal' THEN 'breakfast,dinner,lunch,snack'
                     ELSE 'exercise'
                 END,
                 `hidden`
            FROM `favorites`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `favorites`")
        db.execSQL("ALTER TABLE `favorites_new` RENAME TO `favorites`")
    }
}

/**
 * user_profile에 인바디 컬럼 6개 추가 (b39, 설정 > 인바디 결과).
 * 컬럼 추가뿐이라 ALTER TABLE로 충분하다. 값은 Firestore에도 있어 로그인하면 다시 채워진다.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_profile ADD COLUMN inbodyBmr INTEGER NOT NULL DEFAULT 0")
        listOf("inbodyMuscle", "inbodyFat", "inbodyFatPercent", "inbodyWeight").forEach {
            db.execSQL("ALTER TABLE user_profile ADD COLUMN $it REAL NOT NULL DEFAULT 0")
        }
        db.execSQL("ALTER TABLE user_profile ADD COLUMN inbodyMeasuredAt TEXT NOT NULL DEFAULT ''")
    }
}

/** user_profile에 인바디 해석 글·해석일 컬럼 추가 (b40, 인바디 화면의 해석 리포트) */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_profile ADD COLUMN inbodyAnalysis TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE user_profile ADD COLUMN inbodyAnalyzedAt TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * 인바디 이력 표 추가 (b41). 프로필 컬럼에 있던 마지막 한 건(b39·b40)을 첫 줄로 옮긴다.
 * 측정일이 비어 있던 값은 오늘 날짜를 키로 쓴다. 프로필 컬럼은 "가장 최근 측정" 복사본으로 계속 쓴다.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `inbody_records` (" +
                "`measuredAt` TEXT NOT NULL, `bmr` INTEGER NOT NULL, `skeletalMuscle` REAL NOT NULL, " +
                "`bodyFat` REAL NOT NULL, `bodyFatPercent` REAL NOT NULL, `weight` REAL NOT NULL, " +
                "`visceralFatLevel` INTEGER NOT NULL, `inbodyScore` INTEGER NOT NULL, `bodyWater` REAL NOT NULL, " +
                "`analysis` TEXT NOT NULL, `analyzedAt` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`measuredAt`))"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO `inbody_records` " +
                "(measuredAt, bmr, skeletalMuscle, bodyFat, bodyFatPercent, weight, visceralFatLevel, inbodyScore, bodyWater, analysis, analyzedAt, createdAt) " +
                "SELECT COALESCE(NULLIF(inbodyMeasuredAt, ''), date('now')), inbodyBmr, inbodyMuscle, inbodyFat, inbodyFatPercent, inbodyWeight, " +
                "0, 0, 0, inbodyAnalysis, inbodyAnalyzedAt, (strftime('%s','now') * 1000) " +
                "FROM `user_profile` WHERE id = 0 AND (inbodyBmr > 0 OR inbodyMuscle > 0 OR inbodyFat > 0 OR inbodyFatPercent > 0 OR inbodyWeight > 0)"
        )
    }
}
