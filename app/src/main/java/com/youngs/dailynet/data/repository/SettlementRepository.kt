package com.youngs.dailynet.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.youngs.dailynet.data.model.SettlementModel
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettlementRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val collection = firestore.collection("daily_settlements")

    // 기획서: Document ID를 yyyy-MM-dd 형식으로 저장
    suspend fun saveSettlement(settlement: SettlementModel): Result<Unit> {
        return try {
            collection.document(settlement.date)
                .set(settlement)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 날짜별 데이터 가져오기
    suspend fun getSettlement(date: String): SettlementModel? {
        return try {
            val snapshot = collection.document(date).get().await()
            snapshot.toObject(SettlementModel::class.java)
        } catch (e: Exception) {
            null
        }
    }
}