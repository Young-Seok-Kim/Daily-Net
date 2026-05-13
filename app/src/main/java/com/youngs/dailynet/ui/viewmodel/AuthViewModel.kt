package com.youngs.dailynet.ui.viewmodel

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
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

    fun signIn(context: Context) {
        val credentialManager = CredentialManager.create(context)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        viewModelScope.launch {
            try {
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken.idToken, null)

                // Task를 Coroutine으로 변환해서 처리하면 더 깔끔합니다 (await() 사용 가능)
                val signInResult = auth.signInWithCredential(firebaseCredential)

                // OnCompleteListener 대신 Firebase의 상태 변경을 감지하거나
                // 아래처럼 결과에 따라 flow를 업데이트합니다.
                signInResult.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        _user.value = auth.currentUser
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun signOut(context: Context) {
        auth.signOut()
        _user.value = null
        viewModelScope.launch {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}