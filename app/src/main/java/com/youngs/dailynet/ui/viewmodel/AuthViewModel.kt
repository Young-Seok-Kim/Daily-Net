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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            try {
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken.idToken, null)

                auth.signInWithCredential(firebaseCredential).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        _user.value = auth.currentUser
                        android.util.Log.d("AuthViewModel", "구글 로그인 성공: ${auth.currentUser?.displayName}")
                        onResult(true)
                    } else {
                        android.util.Log.e("AuthViewModel", "Firebase 인증 실패", task.exception)
                        Toast.makeText(
                            context,
                            "로그인 실패: ${task.exception?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        onResult(false)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthViewModel", "로그인 오류", e)
                Toast.makeText(context, "로그인 오류: ${e.message}", Toast.LENGTH_LONG).show()
                onResult(false)
            }
        }
    }
    fun resetUserState() {
        _user.value = null
    }
}