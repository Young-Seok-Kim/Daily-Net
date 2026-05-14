package com.youngs.dailynet.data.repository

import com.google.firebase.auth.FirebaseAuth // 👈 추가
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.youngs.dailynet.data.dao.SettlementDao
import com.youngs.dailynet.data.model.SettlementModel
import com.youngs.dailynet.data.remote.GeminiManager
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
    private val settlementDao: SettlementDao // 👈 로컬 저장을 위해 추가
) {
    private val userSettlementsCollection
        get() = firestore.collection("users")
            .document(auth.currentUser?.uid ?: "guest")
            .collection("settlements")

    /**
     * 1. 실시간 리스트 조회 (Firestore 감시)
     */
    fun getAllSettlements(): Flow<List<SettlementModel>> {
        return userSettlementsCollection
            .orderBy("date", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(SettlementModel::class.java)
            }
    }

    /**
     * AI 분석 후 Firestore와 Room에 모두 저장
     */
    suspend fun analyzeAndSave(settlement: SettlementModel): SettlementModel {
        val analysisResponse = geminiManager.analyzeFoodAndExercise(settlement)

        val finalizedModel = settlement.copy(
            netCalories = analysisResponse.netCalories,
            analysisResult = analysisResponse.feedback,
            finalized = true, // 💡 최종 완료됨
            analyzing = false
        )

        // 1. 서버(Firestore) 저장
        userSettlementsCollection.document(finalizedModel.date).set(finalizedModel).await()

        // 2. 로컬(Room) 저장
        settlementDao.insertOrUpdate(finalizedModel)

        return finalizedModel
    }

    /**
     * 날짜별 데이터 가져오기 (로컬에 우선순위를 둠)
     */
    suspend fun getSettlementByDate(date: String): SettlementModel? {
        // 1. 로컬에서 먼저 확인 (오프라인 대응)
        val localData = settlementDao.getSettlementByDate(date)
        if (localData != null) return localData

        // 2. 로컬에 없으면 서버에서 가져오기
        return try {
            val snapshot = userSettlementsCollection.document(date).get().await()
            snapshot.toObject(SettlementModel::class.java)
        } catch (e: Exception) {
            null
        }
    }
}