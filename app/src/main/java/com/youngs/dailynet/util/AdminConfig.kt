package com.youngs.dailynet.util

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 분석 횟수 제한 없이 쓸 수 있는 사용자(관리자/베타 테스터 등)를 판별한다.
 *
 * 예전에는 이메일이 코드에 하드코딩되어 있어 대상을 바꾸려면 앱을 새로 배포해야 했다.
 * 지금은 Firestore에 이메일을 문서 ID로 하는 빈 문서를 하나 만들어 두면 그 계정이 곧바로
 * 무제한이 된다.
 *
 * ── Firestore 구조 ──
 *
 *   unlimited_users (컬렉션)
 *     ├─ someone@gmail.com   (문서 — ID가 곧 이메일. 필드는 없어도 됨)
 *     └─ another@gmail.com
 *
 * 문서 ID는 반드시 소문자로 넣는다. 보안 규칙이 소문자로 비교하기 때문.
 *
 * ── Firestore 보안 규칙 ──
 *
 *   match /unlimited_users/{email} {
 *     allow read: if request.auth != null
 *                 && request.auth.token.email != null
 *                 && request.auth.token.email.lower() == email;
 *     allow write: if false;   // 추가/삭제는 콘솔에서만
 *   }
 *
 * 이 구조라면 각자 본인 문서만 읽을 수 있어서, 등록된 이메일 목록 전체가 노출되지 않는다.
 * (배열 하나에 모아두면 읽을 수 있는 사람이 목록을 통째로 보게 된다)
 */
@Singleton
class AdminManager @Inject constructor(
    private val firestore: FirebaseFirestore,
    @ApplicationContext context: Context
) {
    companion object {
        const val COLLECTION = "unlimited_users"
        private const val CACHE_PREFIX = "unlimited_"
    }

    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 이 이메일이 무제한 사용 대상인지.
     *
     * 조회에 실패하면(오프라인 등) 마지막으로 확인된 값을 쓴다.
     * 비행기 모드에서 갑자기 제한이 걸리는 상황을 막기 위함이다.
     */
    suspend fun isUnlimited(email: String?): Boolean {
        val key = email?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return false

        return try {
            val exists = firestore.collection(COLLECTION).document(key).get().await().exists()
            prefs.edit().putBoolean(CACHE_PREFIX + key, exists).apply()
            exists
        } catch (e: Exception) {
            e.printStackTrace()
            prefs.getBoolean(CACHE_PREFIX + key, false)
        }
    }
}
