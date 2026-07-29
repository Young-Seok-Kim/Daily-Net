package com.youngs.dailynet.data.repository

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.local.entity.dao.DailyRecordDao
import com.youngs.dailynet.data.model.AnalysisUsage
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.R
import com.youngs.dailynet.data.network.GeminiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyRecordRepository @Inject constructor(
    @ApplicationContext private val context: Context, // 예외 문구를 기기 언어로 꺼내기 위해 필요
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val geminiManager: GeminiManager,
    private val dailyRecordDao: DailyRecordDao,
    private val appDatabase: com.youngs.dailynet.data.local.AppDatabase,
) {
    private var lastVisibleDocument: DocumentSnapshot? = null
    var isLastPageReached = false
        private set

    private val PAGE_SIZE = 7L

    private val userDailyRecordCollection
        get() = firestore.collection("users")
            .document(auth.currentUser?.uid ?: "guest")
            .collection("settlements")

    /**
     * 1. 실시간 리스트 조회 (Firestore 감시)
     */
    fun getAllDailyRecordFirebase(): Flow<List<DailyRecordModel>> {
        return userDailyRecordCollection
            .orderBy("date", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(DailyRecordModel::class.java)
            }
    }

    fun getAllDailyRecordRoom(): Flow<List<DailyRecordModel>> {
        return dailyRecordDao.getAllDailyRecordRoomFlow()
    }

//    suspend fun fetchAndSyncFromFirebase() {
//        try {
//            val snapshot = userSettlementsCollection.get().await()
//            val serverList = snapshot.toObjects(SettlementModel::class.java)
//
//            serverList.forEach { model ->
//                settlementDao.insertOrUpdate(model)
//
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
//    }

    suspend fun fetchAndSyncFromFirebase() {
        if (isLastPageReached) return

        try {
            // 기본 쿼리: 날짜 내림차순 정렬 + 20개 제한
            var query = userDailyRecordCollection
                .orderBy("date", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE)

            // 이전 페이지의 마지막 문서가 있다면 그 다음부터 가져오도록 커서 설정
            lastVisibleDocument?.let {
                query = query.startAfter(it)
            }

            val snapshot = query.get().await()

            if (snapshot.isEmpty) {
                isLastPageReached = true
                return
            }

            // 다음 페이징 처리를 위해 이번 페이지의 마지막 문서 스냅샷을 저장
            lastVisibleDocument = snapshot.documents.lastOrNull()

            // 가져온 데이터가 요청한 사이즈보다 적다면 마지막 페이지에 도달한 것
            if (snapshot.size() < PAGE_SIZE) {
                isLastPageReached = true
            }

            // Room DB에 누적 캐싱 (기존 데이터를 지우지 않고 추가/업데이트)
            val serverList = snapshot.toObjects(DailyRecordModel::class.java)
            serverList.forEach { model ->
                dailyRecordDao.insertOrUpdate(model)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 로컬(Room)에 캐시된 정산 기록 개수 */
    suspend fun getCachedRecordCount(): Int = dailyRecordDao.getCount()

    /**
     * 첫 로그인/최초 로드 시 전체 정산 기록을 한 번에 가져와 Room에 캐싱한다.
     * (페이징 없이 단일 쿼리로 모두 조회 → 스크롤로 내려받을 필요 없음)
     */
    suspend fun fetchAllFromFirebase() {
        try {
            val snapshot = userDailyRecordCollection
                .orderBy("date", Query.Direction.DESCENDING)
                .get()
                .await()

            val serverList = snapshot.toObjects(DailyRecordModel::class.java)
            serverList.forEach { model ->
                dailyRecordDao.insertOrUpdate(model)
            }
            // 전체를 다 가져왔으므로 추가 페이징 불필요
            lastVisibleDocument = snapshot.documents.lastOrNull()
            isLastPageReached = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 💡 [추가] 로그아웃 혹은 계정 전환 시 페이징 상태를 초기화해 주어야 합니다.
     */
    fun resetPagingState() {
        lastVisibleDocument = null
        isLastPageReached = false
    }

    /**
     * 분석 결과와, 서버가 센 오늘 사용량.
     * [usage]는 서버가 횟수를 세어준 경우에만 채워진다 (구버전 경로에서는 null).
     */
    data class AnalysisOutcome(
        val record: DailyRecordModel,
        val usage: AnalysisUsage?
    )

    suspend fun analyzeAndSave(dailyRecordModel: DailyRecordModel, userProfile: UserProfileEntity): AnalysisOutcome {
        val analysisResponse =
            geminiManager.analyzeFoodAndExercise(dailyRecordModel, userProfile)
                ?: throw Exception(context.getString(R.string.error_server_analysis))


        // 구조화 데이터는 b24 이전 서버 응답에는 없다. 없으면 집계값을 건드리지 않고
        // 예전처럼 텍스트만 저장한다 (전부 0으로 남아 hasNutritionData가 false가 된다).
        val detail = analysisResponse.structured

        val finalizedModel = dailyRecordModel.copy(
            netCalories = analysisResponse.netCalories,
            analysisResult = analysisResponse.feedback,
            finalized = true,
            analyzing = false,

            breakfastKcal = detail?.calories?.breakfast ?: 0,
            lunchKcal = detail?.calories?.lunch ?: 0,
            dinnerKcal = detail?.calories?.dinner ?: 0,
            snackKcal = detail?.calories?.snack ?: 0,
            exerciseKcal = detail?.calories?.exercise ?: 0,

            carbGram = detail?.macros?.carb ?: 0f,
            proteinGram = detail?.macros?.protein ?: 0f,
            fatGram = detail?.macros?.fat ?: 0f,

            bmr = detail?.bmr ?: 0
        )

        userDailyRecordCollection.document(finalizedModel.date).set(finalizedModel).await()
        dailyRecordDao.insertOrUpdate(finalizedModel)

        return AnalysisOutcome(record = finalizedModel, usage = analysisResponse.usage)
    }

    suspend fun getDailyRecordByDate(date: String): DailyRecordModel? {
        val localData = dailyRecordDao.getDailyRecordByDate(date) // room에서 먼저 데이터를 가져옴
        if (localData != null) return localData

        return try {
            val snapshot = userDailyRecordCollection.document(date).get().await() // room에서 가져온 데이터가 없으면 firebase에서 가져옴
            snapshot.toObject(DailyRecordModel::class.java)
        } catch (e: Exception) {
            null
        }
    }
    suspend fun getLatestWeight(targetDate: String): Float {
        val latestWeight = dailyRecordDao.getLatestWeightBefore(targetDate)
        return latestWeight ?: 0f
    }
    suspend fun clearAllLocalData() {
        // Room DB의 모든 테이블 데이터를 삭제합니다.
        appDatabase.clearAllTables()
    }
}