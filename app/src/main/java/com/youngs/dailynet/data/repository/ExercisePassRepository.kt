package com.youngs.dailynet.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.youngs.dailynet.data.local.entity.ExercisePassEntity
import com.youngs.dailynet.data.local.entity.dao.ExercisePassDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 운동 이용권 저장소. `users/{uid}/passes/{id}`가 원본, Room `exercise_passes`가 캐시다.
 *
 * 인바디 이력(InbodyRepository)과 같은 방식이다.
 * - 저장·삭제는 Firestore에 먼저 쓰고 Room에 반영한다 (오프라인이면 SDK가 들고 있다가 보낸다)
 * - 로그인 직후와 이용권 화면 진입 때 [sync]로 Firestore 것을 Room에 덮어쓴다 (계정이 기준이다)
 */
@Singleton
class ExercisePassRepository @Inject constructor(
    private val passDao: ExercisePassDao,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val TAG = "PassRepo"

    private val collection
        get() = auth.currentUser?.uid?.let {
            firestore.collection("users").document(it).collection("passes")
        }

    fun observe(): Flow<List<ExercisePassEntity>> = passDao.observeAll()

    /** Firestore의 이용권을 Room에 덮어쓴다. 계정에서 지워진 것은 기기에서도 지운다 */
    suspend fun sync() {
        val col = collection ?: return
        try {
            val cloud = col.get().await().documents.mapNotNull { ExercisePassEntity.fromMap(it.data) }
            val cloudIds = cloud.map { it.id }.toSet()
            passDao.getAll().filter { it.id !in cloudIds }.forEach { passDao.delete(it.id) }
            cloud.forEach { passDao.upsert(it) }
            Log.i(TAG, "sync: ${cloud.size} passes")
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
        }
    }

    suspend fun save(pass: ExercisePassEntity) {
        require(pass.id.isNotBlank() && pass.name.isNotBlank()) { "id and name required" }
        val col = collection ?: throw IllegalStateException("not signed in")
        col.document(pass.id).set(pass.toMap()).await()
        passDao.upsert(pass)
    }

    suspend fun delete(id: String) {
        val col = collection ?: throw IllegalStateException("not signed in")
        col.document(id).delete().await()
        passDao.delete(id)
    }

    suspend fun clearLocal() = passDao.clear()
}
