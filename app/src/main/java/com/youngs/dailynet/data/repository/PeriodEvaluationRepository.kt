package com.youngs.dailynet.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.youngs.dailynet.data.model.PeriodEvaluation
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 주·월·연 AI 평가의 저장소. `users/{uid}/evaluations/{키}` 한 곳이다.
 *
 * Room에 두지 않는 이유: 평가는 기간마다 하나뿐이고 사용자가 그 패널을 펼쳤을 때만 필요하다.
 * Firestore SDK가 오프라인 캐시를 들고 있어 비행기 모드에서도 한 번 본 평가는 다시 보인다.
 * 테이블을 늘리면 마이그레이션이 하나 더 생기는데, 그 대가로 얻는 게 없다.
 */
@Singleton
class PeriodEvaluationRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val TAG = "PeriodEvalRepo"

    private val collection
        get() = auth.currentUser?.uid?.let {
            firestore.collection("users").document(it).collection("evaluations")
        }

    /** 저장된 평가. 없거나 못 읽으면 null */
    suspend fun get(key: String): PeriodEvaluation? {
        val col = collection ?: return null
        return try {
            PeriodEvaluation.fromMap(col.document(key).get().await().data)
        } catch (e: Exception) {
            Log.w(TAG, "평가 읽기 실패 $key: ${e.message}")
            null
        }
    }

    suspend fun save(key: String, evaluation: PeriodEvaluation) {
        val col = collection ?: return
        col.document(key).set(evaluation.toMap()).await()
    }
}
