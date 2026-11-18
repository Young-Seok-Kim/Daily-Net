package com.youngs.dailynet.data.network

import com.google.firebase.auth.FirebaseAuth
import com.youngs.dailynet.data.model.WithdrawRequest
import com.youngs.dailynet.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 회원탈퇴를 서버에 맡긴다.
 *
 * 예전에는 앱이 users/{uid} 문서를 지우고 Auth 계정을 스스로 삭제했다.
 * 그런데 Firestore는 문서를 지워도 서브컬렉션(settlements, settings 등)을 같이 지우지 않고,
 * 클라이언트 SDK는 서브컬렉션 목록을 읽을 수도 없다. 그래서 탈퇴자의 기록이
 * "필드 없는 문서" 밑에 그대로 남았다. 서버는 Admin SDK로 트리를 통째로 지울 수 있다.
 *
 * Auth 계정 삭제도 서버가 하므로 "최근 로그인 필요" 오류가 더는 나지 않는다.
 */
@Singleton
class AccountWithdrawer @Inject constructor(
    private val apiService: DailyNetApiService,
    private val auth: FirebaseAuth
) {
    sealed class Result {
        object Success : Result()
        /** 토큰을 못 얻었거나 서버가 401을 돌려줬다. 다시 로그인해야 한다 */
        object Unauthenticated : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun withdraw(): Result = withContext(Dispatchers.IO) {
        val idToken = auth.currentUser?.getIdToken(false)?.await()?.token
            ?: return@withContext Result.Unauthenticated
        try {
            val res = apiService.withdrawAccount(
                url = Constants.WITHDRAW_ACCOUNT_URL,
                authorization = "Bearer $idToken",
                request = WithdrawRequest()
            )
            if (res.ok) Result.Success else Result.Failed(res.error ?: "unknown")
        } catch (e: HttpException) {
            if (e.code() == 401) Result.Unauthenticated else Result.Failed("HTTP ${e.code()}")
        } catch (e: Exception) {
            Result.Failed(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }
}
