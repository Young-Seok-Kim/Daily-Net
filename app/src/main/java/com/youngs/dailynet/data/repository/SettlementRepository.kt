package com.youngs.dailynet.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.local.entity.dao.SettlementDao
import com.youngs.dailynet.data.model.SettlementModel
import com.youngs.dailynet.data.network.GeminiManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettlementRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val geminiManager: GeminiManager,
    private val settlementDao: SettlementDao,
    private val appDatabase: com.youngs.dailynet.data.local.AppDatabase,
) {
    private var lastVisibleDocument: DocumentSnapshot? = null
    var isLastPageReached = false
        private set

    private val PAGE_SIZE = 5L

    private val userSettlementsCollection
        get() = firestore.collection("users")
            .document(auth.currentUser?.uid ?: "guest")
            .collection("settlements")

    /**
     * 1. 실시간 리스트 조회 (Firestore 감시)
     */
    fun getAllSettlementsFirebase(): Flow<List<SettlementModel>> {
        return userSettlementsCollection
            .orderBy("date", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(SettlementModel::class.java)
            }
    }

    fun getAllSettlementsRoom(): Flow<List<SettlementModel>> {
        return settlementDao.getAllSettlementsRoomFlow()
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
            var query = userSettlementsCollection
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
            val serverList = snapshot.toObjects(SettlementModel::class.java)
            serverList.forEach { model ->
                settlementDao.insertOrUpdate(model)
            }
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

    suspend fun analyzeAndSave(settlement: SettlementModel, userProfile: UserProfileEntity): SettlementModel {
        val analysisResponse =
            geminiManager.analyzeFoodAndExercise(settlement, userProfile)
                ?: throw Exception("서버 분석 중 오류가 발생했습니다.")


        val finalizedModel = settlement.copy(
            netCalories = analysisResponse.netCalories,
            analysisResult = analysisResponse.feedback,
            finalized = true,
            analyzing = false
        )

        userSettlementsCollection.document(finalizedModel.date).set(finalizedModel).await()
        settlementDao.insertOrUpdate(finalizedModel)

        return finalizedModel
    }

    suspend fun getSettlementByDate(date: String): SettlementModel? {
        val localData = settlementDao.getSettlementByDate(date) // room에서 먼저 데이터를 가져옴
        if (localData != null) return localData

        return try {
            val snapshot = userSettlementsCollection.document(date).get().await() // room에서 가져온 데이터가 없으면 firebase에서 가져옴
            snapshot.toObject(SettlementModel::class.java)
        } catch (e: Exception) {
            null
        }
    }
    suspend fun getLatestWeight(): Float {
        val latestWeight = settlementDao.getLatestWeight()
        return latestWeight ?: 0f
    }

    suspend fun clearAllLocalData() {
        // Room DB의 모든 테이블 데이터를 삭제합니다.
        appDatabase.clearAllTables()
    }
}