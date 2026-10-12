package com.youngs.dailynet.data.repository

import android.content.Context
import android.util.Log
import com.youngs.dailynet.data.local.entity.FavoriteItemEntity
import com.youngs.dailynet.data.local.entity.FavoriteItemEntity.Companion.KIND_EXERCISE
import com.youngs.dailynet.data.local.entity.FavoriteItemEntity.Companion.KIND_MEAL
import com.youngs.dailynet.data.local.entity.dao.DailyRecordDao
import com.youngs.dailynet.data.local.entity.dao.FavoriteDao
import com.youngs.dailynet.data.model.AnalysisDetail
import com.youngs.dailynet.data.model.AnalysisItem
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.util.FavoriteRank
import com.youngs.dailynet.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 입력 즐겨찾기 저장소. 지금은 기기(Room)에만 둔다.
 *
 * 분석 결과에서 자동으로 모으는 것이 핵심이다. 사용자가 저장 버튼을 누르는 방식은
 * 처음 며칠 동안 아무것도 없어 쓰이지 않는다. 서버가 메뉴명을 정규화해 주므로
 * 그 이름을 그대로 세면 "닭가슴살"과 "닭가슴살 " 같은 중복도 생기지 않는다.
 */
@Singleton
class FavoriteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favoriteDao: FavoriteDao,
    private val dailyRecordDao: DailyRecordDao
) {
    private val prefs
        get() = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    fun observe(kind: String): Flow<List<FavoriteItemEntity>> = favoriteDao.observeByKind(kind)

    /**
     * 분석 결과 하나에 나온 항목을 전부 센다. 분석이 끝날 때마다 부른다.
     *
     * [usedAt]은 보통 지금이지만, 과거 기록을 한꺼번에 채울 때는 그 기록의 날짜를 준다.
     * 그래야 "최근에 쓴 순"이 실제 순서와 맞는다.
     */
    suspend fun recordUsage(detail: AnalysisDetail?, usedAt: Long = System.currentTimeMillis()) {
        if (detail == null) return
        val meals = detail.meals
        countAll(KIND_MEAL, "breakfast", meals.breakfast, usedAt)
        countAll(KIND_MEAL, "lunch", meals.lunch, usedAt)
        countAll(KIND_MEAL, "dinner", meals.dinner, usedAt)
        countAll(KIND_MEAL, "snack", meals.snack, usedAt)
        countAll(KIND_EXERCISE, "exercise", detail.exercises, usedAt)
    }

    private suspend fun countAll(kind: String, field: String, items: List<AnalysisItem>, usedAt: Long) {
        // 같은 끼니에 같은 메뉴가 두 번 나오면(서버가 접시별로 쪼갠 경우) 한 번으로 친다
        val seen = HashSet<String>()
        for (item in items) {
            val name = item.name.trim()
            if (name.isEmpty() || !seen.add(name.lowercase())) continue
            val existing = favoriteDao.get(kind, name) ?: FavoriteItemEntity(kind = kind, text = name)
            favoriteDao.upsert(
                existing.copy(
                    useCount = existing.useCount + 1,
                    breakfastCount = existing.breakfastCount + (if (field == "breakfast") 1 else 0),
                    lunchCount = existing.lunchCount + (if (field == "lunch") 1 else 0),
                    dinnerCount = existing.dinnerCount + (if (field == "dinner") 1 else 0),
                    snackCount = existing.snackCount + (if (field == "snack") 1 else 0),
                    kcalSum = existing.kcalSum + item.kcal.coerceAtLeast(0),
                    kcalCount = existing.kcalCount + (if (item.kcal > 0) 1 else 0),
                    lastUsedAt = maxOf(existing.lastUsedAt, usedAt)
                )
            )
        }
    }

    /**
     * 사용자가 시트의 검색창에 쳐서 직접 추가한 항목. 횟수 0으로 들어가고, 숨겨둔 것이었으면 다시 보인다.
     * 이름이 같은 항목(대소문자만 다른 것 포함)이 이미 있으면 그것을 돌려준다.
     */
    suspend fun add(kind: String, text: String): FavoriteItemEntity? {
        val name = text.trim()
        if (name.isEmpty()) return null
        val existing = favoriteDao.get(kind, name)
            ?: favoriteDao.observeByKind(kind).first().firstOrNull { it.text.equals(name, ignoreCase = true) }
        val item = (existing ?: FavoriteItemEntity(kind = kind, text = name)).copy(
            hidden = false,
            lastUsedAt = System.currentTimeMillis()
        )
        favoriteDao.upsert(item)
        return item
    }

    /** [field] 칸에서만 고정/해제한다. 다른 칸의 고정은 그대로 둔다 */
    suspend fun setPinned(item: FavoriteItemEntity, field: String, pinned: Boolean) {
        val current = favoriteDao.get(item.kind, item.text) ?: item
        favoriteDao.setPinnedFields(item.kind, item.text, current.withPinned(field, pinned).pinnedFields)
    }

    suspend fun hide(item: FavoriteItemEntity) = favoriteDao.setHidden(item.kind, item.text, true)

    /**
     * 방금 끝난 분석 결과를 센다. 백필과 겹쳐 같은 결과를 두 번 세지 않도록 한 줄로 세운다.
     *
     * 아직 백필 전이면 결과를 따로 세지 않고 백필을 지금 돌린다. 기록은 이미 Room에 저장된 뒤라
     * 백필이 이 결과까지 함께 센다. (새로 설치해 로그인한 뒤 첫 분석을 하는 경우가 여기에 해당한다.
     * 시작할 때는 Room이 비어 백필이 미뤄졌고, 그 사이 과거 기록이 동기화되어 들어와 있다.)
     */
    suspend fun recordAnalysis(detail: AnalysisDetail?) = lock.withLock {
        if (isBackfilled) recordUsage(detail) else backfillLocked()
    }

    /**
     * 이 기능이 들어가기 전에 쌓인 기록으로 목록을 한 번 채운다. 앱이 시작할 때 부른다.
     * 항목별 분석 결과가 있으면 그것을, 없으면 입력칸 글을 쓴다. 그동안 적은 것이 전부 반영된다.
     *
     * Room이 비어 있으면(설치 직후, 로그인 직후 동기화 전) 아무것도 못 채우므로 플래그를 남기지 않고
     * 끝낸다. 다음 시작이나 첫 분석([recordAnalysis]) 때 다시 시도한다. 한 번 채우면 플래그를 남겨
     * 다시 돌지 않는다. 두 번 돌면 횟수가 두 배가 된다.
     */
    suspend fun backfillIfNeeded() = lock.withLock {
        if (!isBackfilled) backfillLocked()
    }

    private val lock = Mutex()
    private val TAG = "FavoriteRepo"

    private val isBackfilled: Boolean
        get() = prefs.getBoolean(Constants.KEY_FAVORITES_BACKFILLED, false)

    private suspend fun backfillLocked() {
        val records = dailyRecordDao.getAllDailyRecordRoomFlow().first()
        if (records.isEmpty()) {
            Log.i(TAG, "backfill skipped: no records in Room yet")
            return
        }
        Log.i(TAG, "backfill start: ${records.size} records (${records.count { it.analysisDetail != null }} with detail)")

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        records.forEach { record ->
            val usedAt = runCatching { fmt.parse(record.date)?.time }.getOrNull() ?: 0L
            val detail = record.analysisDetail
            if (detail != null) {
                recordUsage(detail, usedAt)
            } else {
                recordUsageFromText(record, usedAt)
            }
        }
        prefs.edit().putBoolean(Constants.KEY_FAVORITES_BACKFILLED, true).apply()
        Log.i(TAG, "backfill done: ${favoriteDao.count()} favorites")
    }

    /**
     * 항목별 분석 결과가 없는 옛 기록(b33 이전)은 입력칸에 적은 글을 쉼표로 나눠 센다.
     *
     * 서버가 정규화한 이름이 아니라 사용자가 친 글 그대로라 "현미밥 한공기"와 "현미밥 한 공기"가
     * 따로 쌓일 수 있다. 그래도 몇 달치 기록을 통째로 버리는 것보다는 낫다. kcal은 모르니 더하지 않는다.
     */
    private suspend fun recordUsageFromText(record: DailyRecordModel, usedAt: Long) {
        fun items(text: String) = FavoriteRank.splitItems(text).map { AnalysisItem(name = it, kcal = 0) }
        countAll(KIND_MEAL, "breakfast", items(record.breakfast), usedAt)
        countAll(KIND_MEAL, "lunch", items(record.lunch), usedAt)
        countAll(KIND_MEAL, "dinner", items(record.dinner), usedAt)
        countAll(KIND_MEAL, "snack", items(record.snack), usedAt)
        countAll(KIND_EXERCISE, "exercise", items(record.exercise), usedAt)
    }
}
