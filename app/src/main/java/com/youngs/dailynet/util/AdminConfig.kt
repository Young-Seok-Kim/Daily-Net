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
 * 지금은 Firestore 문서 하나에 이메일을 **배열로** 모아두고, 앱이 그걸 읽어서 판단한다.
 *
 * ── Firestore 구조 ──
 *
 *   unlimited_users (컬렉션)
 *     └─ list (문서 — 문서 ID를 반드시 "list"로 직접 입력. 자동 ID 쓰면 안 됨)
 *          └─ emails : array<string>
 *                        0: "someone@gmail.com"
 *                        1: "another@gmail.com"
 *
 * 사람을 추가/삭제하려면 이 배열만 고치면 되고 앱을 새로 배포할 필요가 없다.
 * 대소문자는 신경 쓰지 않아도 된다 (읽을 때 소문자로 맞춘다).
 *
 * ── Firestore 보안 규칙 ──
 *
 *   match /unlimited_users/{doc} {
 *     allow read: if request.auth != null;
 *     allow write: if false;   // 추가/삭제는 콘솔에서만
 *   }
 *
 * 주의: 배열 한 문서에 모아두는 구조라 문서를 읽을 수 있는 사람은 목록 전체를 보게 된다.
 * (로그인한 사용자면 누구나 등록된 이메일을 볼 수 있다)
 * 이걸 감추려면 판별을 앱이 아니라 Cloud Function 쪽으로 옮겨야 한다.
 */
@Singleton
class AdminManager @Inject constructor(
    private val firestore: FirebaseFirestore,
    @ApplicationContext context: Context
) {
    companion object {
        const val COLLECTION = "unlimited_users"
        const val DOCUMENT = "list"
        const val FIELD = "emails"
        private const val CACHE_KEY = "unlimited_emails"
    }

    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    /** 이 이메일이 무제한 사용 대상인지 */
    suspend fun isUnlimited(email: String?): Boolean {
        val key = email?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return false
        return key in loadEmails()
    }

    /**
     * 등록된 이메일 목록을 읽어온다.
     *
     * 조회에 실패하면(오프라인 등) 마지막으로 받아둔 목록을 쓴다.
     * 비행기 모드에서 갑자기 제한이 걸리는 상황을 막기 위함이다.
     */
    private suspend fun loadEmails(): Set<String> {
        return try {
            val snapshot = firestore.collection(COLLECTION).document(DOCUMENT).get().await()
            val emails = (snapshot.get(FIELD) as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.lowercase() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()

            prefs.edit().putStringSet(CACHE_KEY, emails).apply()
            emails
        } catch (e: Exception) {
            e.printStackTrace()
            // getStringSet이 돌려주는 Set은 수정하면 안 되므로 복사해서 쓴다
            prefs.getStringSet(CACHE_KEY, emptySet())?.toSet().orEmpty()
        }
    }
}
