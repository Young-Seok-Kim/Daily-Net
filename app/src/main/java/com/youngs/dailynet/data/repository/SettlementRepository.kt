package com.youngs.dailynet.data.repository

import com.google.firebase.auth.FirebaseAuth // 👈 추가
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
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
    private val auth: FirebaseAuth, // 👈 주입 추가
    private val geminiManager: GeminiManager
) {
    // 💡 보안 규칙에 맞게 경로 수정: /users/{uid}/settlements
    private val userSettlementsCollection
        get() = firestore.collection("users")
            .document(auth.currentUser?.uid ?: "guest") // 로그인 안된 경우 대비
            .collection("settlements")

    /**
     * 1. 대시보드용 실시간 리스트 조회
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
     * 2. 날짜별 상세 데이터 가져오기
     */
    suspend fun getSettlementByDate(date: String): SettlementModel? {
        return try {
            val snapshot = userSettlementsCollection.document(date).get().await()
            snapshot.toObject(SettlementModel::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 3. AI 분석 및 최종 저장
     */
    suspend fun analyzeAndSave(settlement: SettlementModel): SettlementModel {
        val analysisResponse = geminiManager.analyzeFoodAndExercise(settlement)

        val finalizedModel = settlement.copy(
            netCalories = analysisResponse.netCalories,
            analysisResult = analysisResponse.feedback,
            isFinalizing = true,
            isAnalyzing = false
        )

        // 설정하신 규칙에 따라 본인 UID 하위 경로에 저장됩니다.
        userSettlementsCollection.document(finalizedModel.date).set(finalizedModel).await()

        return finalizedModel
    }

    /**
     * 4. 임시 저장 및 단순 업데이트
     */
    suspend fun insertOrUpdate(settlement: SettlementModel) {
        userSettlementsCollection.document(settlement.date).set(settlement).await()
    }
}