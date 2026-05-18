package com.youngs.dailynet.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
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
) {
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

    suspend fun fetchAndSyncFromFirebase() {
        try {
            val snapshot = userSettlementsCollection.get().await()
            val serverList = snapshot.toObjects(SettlementModel::class.java)

            serverList.forEach { model ->
                settlementDao.insertOrUpdate(model)

            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun analyzeAndSave(settlement: SettlementModel, userHeight: Float): SettlementModel {
        val analysisResponse = geminiManager.analyzeFoodAndExercise(settlement, userHeight, settlement.isMale)

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
        val localData = settlementDao.getSettlementByDate(date)
        if (localData != null) return localData

        return try {
            val snapshot = userSettlementsCollection.document(date).get().await()
            snapshot.toObject(SettlementModel::class.java)
        } catch (e: Exception) {
            null
        }
    }
}