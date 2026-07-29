package com.youngs.dailynet.ui.viewmodel

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: FirebaseAuth
) : ViewModel() {
    private val _user = MutableStateFlow(auth.currentUser)
    val user = _user.asStateFlow()

    fun signIn(context: Context, onResult: (Boolean) -> Unit) {
        val credentialManager = CredentialManager.create(context)
        // "Sign in with Google" 버튼 클릭용 옵션 → 항상 계정 선택 UI가 뜬다.
        val signInOption =
            GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        viewModelScope.launch {
            val maxAttempts = 3
            var attempt = 0
            while (attempt < maxAttempts) {
                attempt++
                try {
                    val result = credentialManager.getCredential(context, request)
                    val credential = result.credential
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
                    val firebaseCredential =
                        GoogleAuthProvider.getCredential(googleIdToken.idToken, null)

                    auth.signInWithCredential(firebaseCredential).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            _user.value = auth.currentUser
                            android.util.Log.d("AuthViewModel", "구글 로그인 성공: ${auth.currentUser?.displayName}")
                            onResult(true)
                        } else {
                            android.util.Log.e("AuthViewModel", "Firebase 인증 실패", task.exception)
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.login_failed,
                                    task.exception?.message.orEmpty()
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                            onResult(false)
                        }
                    }
                    return@launch // 자격증명 획득 성공 → 종료 (Firebase 결과는 콜백에서 처리)
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    // "[16] Account reauth failed" 처럼 재인증 실패일 때만 자동 재시도 (사용자 취소는 제외)
                    val isReauth = msg.contains("reauth", ignoreCase = true)
                    android.util.Log.e(
                        "AuthViewModel",
                        "로그인 시도 $attempt/$maxAttempts 실패 (reauth=$isReauth)", e
                    )

                    if (isReauth && attempt < maxAttempts) {
                        // 자격증명 상태를 초기화하고 잠깐 뒤 재시도
                        try {
                            credentialManager.clearCredentialState(ClearCredentialStateRequest())
                        } catch (_: Exception) {
                        }
                        delay(700)
                        // while 루프 계속 → 재시도
                    } else {
                        val userMsg = if (isReauth) {
                            context.getString(R.string.login_reauth_failed)
                        } else {
                            context.getString(R.string.login_error, msg)
                        }
                        Toast.makeText(context, userMsg, Toast.LENGTH_LONG).show()
                        onResult(false)
                        return@launch
                    }
                }
            }
        }
    }
    fun resetUserState() {
        _user.value = null
    }
}