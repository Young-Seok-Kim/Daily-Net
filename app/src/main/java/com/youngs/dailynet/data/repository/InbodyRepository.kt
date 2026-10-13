package com.youngs.dailynet.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
import com.youngs.dailynet.data.local.entity.dao.InbodyDao
import com.youngs.dailynet.data.model.InbodyData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 인바디 측정 이력 저장소. Room `inbody_records`가 화면의 원본이고, Firestore
 * `users/{uid}/inbody/{측정일}`이 계정 쪽 원본이다. 인바디는 이 두 곳에만 있다.
 *
 * - 저장·삭제는 Firestore에 먼저 쓰고 Room에 반영한다 (오프라인이면 SDK가 들고 있다가 보낸다)
 * - 로그인·화면 진입 때 [sync]로 Firestore 것을 Room에 덮어쓴다 (계정이 기준이다)
 * - b39·b40이 쓰던 `users/{uid}.inbody` 필드는 심사 중에 b41로 대체돼 실제 사용자에게 간 적이 없다.
 *   남아 있으면 [sync]가 서브컬렉션으로 옮기고 지운다. 같은 값을 두 군데 두지 않는다
 */
@Singleton
class InbodyRepository @Inject constructor(
    private val inbodyDao: InbodyDao,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val TAG = "InbodyRepo"

    private val userDoc
        get() = auth.currentUser?.uid?.let { firestore.collection("users").document(it) }

    fun observe(): Flow<List<InbodyRecordEntity>> = inbodyDao.observeAll()

    suspend fun get(measuredAt: String): InbodyData? = inbodyDao.get(measuredAt)?.toData()

    /** 가장 최근 측정. 식단 분석에 실어 보내는 값이다 */
    suspend fun latest(): InbodyData? = inbodyDao.getLatest()?.toData()

    /**
     * Firestore의 이력을 Room에 덮어쓴다. 로그인 직후와 목록 화면 진입 때 부른다.
     *
     * 계정에 아무것도 없는데 기기에는 있으면(b40에서 올라와 마이그레이션으로 생긴 한 건, 또는
     * 구버전 자리 `users/{uid}.inbody`에만 있던 값) 그것을 계정에 올려 둔다. 그래야 다음 기기에서도 보인다.
     * 구버전 자리는 옮긴 뒤 지운다.
     */
    suspend fun sync() {
        val doc = userDoc ?: return
        try {
            val snapshot = doc.collection("inbody").get().await()
            val cloud = snapshot.documents.mapNotNull { d ->
                InbodyData.fromMap(d.data)?.takeIf { it.measuredAt.isNotBlank() }
            }
            val legacy = doc.get().await().get("inbody")
            if (cloud.isNotEmpty()) {
                cloud.forEach { inbodyDao.upsert(InbodyRecordEntity.fromData(it)) }
                Log.i(TAG, "sync: ${cloud.size} records from cloud")
            } else {
                // 계정에 이력이 없다. 기기에 있는 것(마이그레이션 산출물)이나 구버전 자리의 한 건을 올린다
                val local = inbodyDao.getAll().map { it.toData() }.ifEmpty {
                    listOfNotNull(InbodyData.fromMap(legacy)?.takeIf { it.measuredAt.isNotBlank() })
                }
                local.forEach { data ->
                    doc.collection("inbody").document(data.measuredAt).set(data.toMap()).await()
                    inbodyDao.upsert(InbodyRecordEntity.fromData(data))
                }
                if (local.isNotEmpty()) Log.i(TAG, "sync: uploaded ${local.size} local records")
            }
            if (legacy != null) {
                doc.set(mapOf("inbody" to FieldValue.delete()), SetOptions.merge()).await()
                Log.i(TAG, "sync: removed legacy users.inbody field")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
        }
    }

    /** 한 건 저장. 측정일이 키라 같은 날짜면 덮어쓴다. 값이 바뀌면 예전 해석은 비운다 */
    suspend fun save(data: InbodyData) {
        require(data.measuredAt.isNotBlank()) { "measuredAt required" }
        val doc = userDoc ?: throw IllegalStateException("not signed in")
        val existing = inbodyDao.get(data.measuredAt)
        val sameValues = existing != null && existing.toData().copy(analysis = "", analyzedAt = "") ==
            data.copy(analysis = "", analyzedAt = "")
        val toSave = if (sameValues) data.copy(analysis = existing!!.analysis, analyzedAt = existing.analyzedAt)
        else data.copy(analysis = "", analyzedAt = "")

        doc.collection("inbody").document(data.measuredAt)
            .set(toSave.toMap() + mapOf("createdAt" to (existing?.createdAt ?: System.currentTimeMillis())))
            .await()
        inbodyDao.upsert(InbodyRecordEntity.fromData(toSave, existing?.createdAt ?: System.currentTimeMillis()))
    }

    suspend fun delete(measuredAt: String) {
        val doc = userDoc ?: throw IllegalStateException("not signed in")
        doc.collection("inbody").document(measuredAt).delete().await()
        inbodyDao.delete(measuredAt)
    }

    suspend fun setAnalysis(measuredAt: String, analysis: String, analyzedAt: String) {
        val doc = userDoc ?: throw IllegalStateException("not signed in")
        doc.collection("inbody").document(measuredAt)
            .set(mapOf("analysis" to analysis, "analyzedAt" to analyzedAt), SetOptions.merge())
            .await()
        inbodyDao.setAnalysis(measuredAt, analysis, analyzedAt)
    }

    suspend fun clearLocal() = inbodyDao.clear()
}
