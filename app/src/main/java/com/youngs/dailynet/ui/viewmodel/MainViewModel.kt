package com.youngs.dailynet.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.local.entity.dao.SettlementDao
import com.youngs.dailynet.data.local.entity.dao.UserProfileDao
import com.youngs.dailynet.data.model.SettlementModel
import com.youngs.dailynet.data.repository.SettlementRepository
import com.youngs.dailynet.ui.view.getWeekIdentifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class CategoryInfo(
    val label: String,
    val hint: String,
    val fieldName: String
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: SettlementRepository,
    private val settlementDao: SettlementDao,
    private val userProfileDao: UserProfileDao,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _isMale = MutableStateFlow(true)
    val isMale = _isMale.asStateFlow()

    private val _isLoggingOut = MutableStateFlow(false)
    val isLoggingOut = _isLoggingOut.asStateFlow() // 외부에서는 읽기만 가능

    var cachedWeight: Float = 0f

    fun setGender(isMale: Boolean) {
        _isMale.value = isMale
    }

    var showProfileDialog by mutableStateOf(false)
        private set

    private val currentUserId: String?
        get() = auth.currentUser?.uid

    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    fun saveInitialProfile(googleName : String, height: Float, weight: Float, isMale: Boolean, birthDate: String) {
        val uid = currentUserId ?: return

        viewModelScope.launch {
            try {
                val timestamp = System.currentTimeMillis()

                val serverProfile = mapOf(
                    "googleName " to googleName,
                    "height" to height,
                    "initialWeight" to weight,
                    "isMale" to isMale,
                    "birthDate" to birthDate,
                    "createdAt" to timestamp
                )
                firestore.collection("users").document(uid).set(serverProfile).await()

                userProfileDao.insertProfile(
                    UserProfileEntity(
                        height = height,
                        initialWeight = weight,
                        isMale = isMale,
                        birthDate = birthDate,
                        createdAt = timestamp
                    )
                )

                _isMale.value = isMale
                showProfileDialog = false
                _uiState.update { it.copy(weight = weight, currentWeight = weight) } // 💡 진입 시점 일치를 위해 currentWeight도 세팅
            } catch (e: Exception) {
                e.printStackTrace()
                val ToastMessage = "프로필 저장 실패: ${e.message}"
                onToastShown(ToastMessage)
            }
        }
    }

    fun checkProfile() {
        val uid = currentUserId ?: return

        viewModelScope.launch {
            try {
                val localProfile = userProfileDao.getProfile()
                if (localProfile != null) {
                    showProfileDialog = false
                    _isMale.value = localProfile.isMale

                    repository.fetchAndSyncFromFirebase()

                    return@launch
                }

                val document = firestore.collection("users").document(uid).get().await()
                val serverHeight = document.getDouble("height")

                if (document.exists() && serverHeight != null && serverHeight > 0.0) {
                    val initialWeight = (document.getDouble("initialWeight") ?: 0.0).toFloat()
                    val isMale = document.getBoolean("isMale") ?: true
                    val birthDate = document.getString("birthDate") ?: ""
                    val createdAt = document.getLong("createdAt") ?: System.currentTimeMillis()

                    // 1. 프로필 복원
                    userProfileDao.insertProfile(
                        UserProfileEntity(
                            height = serverHeight.toFloat(),
                            initialWeight = initialWeight,
                            isMale = isMale,
                            birthDate = birthDate,
                            createdAt = createdAt
                        )
                    )

                    repository.fetchAndSyncFromFirebase()

                    _isMale.value = isMale
                    showProfileDialog = false
                } else {
                    showProfileDialog = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showProfileDialog = true
            }
        }
    }

    val allSettlements: StateFlow<List<SettlementModel>> = repository.getAllSettlementsRoom()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val totalNetCalories: StateFlow<Int> = allSettlements
        .map { list -> list.sumOf { it.netCalories } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val categoryList = listOf(
        CategoryInfo("아침", "예: 사과 1개, 닭가슴살", "breakfast"),
        CategoryInfo("점심", "예: 김치찌개, 현미밥 1공기", "lunch"),
        CategoryInfo("저녁", "예: 샐러드, 스테이크, 흰쌀밥 1공기, 고등어 등등", "dinner"),
        CategoryInfo("간식", "예: 아메리카노, 견과류", "snack"),
        CategoryInfo("운동", "예: 스쿼트 100개, 런닝 5km", "exercise"),
        CategoryInfo("비고", "오늘의 특이사항 \n ex)삼겹살 먹을 때 비계 떼고 살코기 위주로 먹었고, 쌈을 많이 싸먹었어요.\n도보를 6000보 걸었어요.", "remark")
    )

    private val _uiState = MutableStateFlow(SettlementModel(date = today))
    val uiState = _uiState.asStateFlow()

    var toastMessage by mutableStateOf<String?>(null)
        private set

    val weeklyCaloriesMap: StateFlow<Map<Int, Int>> = allSettlements
        .map { list ->
            list.groupBy { getWeekIdentifier(it.date) }
                .mapValues { entry ->
                    // 해당 주차에 속한 모든 정산의 netCalories 합계
                    entry.value.sumOf { it.netCalories }
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    fun onToastShown() { toastMessage = null }

    init {
        initializeTodayData()
    }

    fun initializeTodayData() = viewModelScope.launch {
        try {
            // 1. 먼저 최근 몸무게를 DB에서 확실하게 가져와 cachedWeight를 채웁니다.
            val latestWeight = repository.getLatestWeight()
            cachedWeight = latestWeight

            // 2. 최근 몸무게 조회가 완전히 '끝난 후'에 오늘치 초안 생성을 시작합니다.
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val savedDraft = repository.getSettlementByDate(today)

            if (savedDraft != null) {
                _uiState.value = savedDraft
                cachedWeight = savedDraft.currentWeight
            } else {
                // 이제 위에서 보장된 cachedWeight가 안전하게 주입됩니다.
                _uiState.value = SettlementModel(
                    date = today,
                    weight = 0f,
                    currentWeight = cachedWeight,
                    breakfast = "", lunch = "", dinner = "", snack = "", exercise = "", remark = "",
                    analysisResult = "", netCalories = 0, hasExercise = false, finalized = false, analyzing = false
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadDateData(date: String) = viewModelScope.launch {
        val savedData = repository.getSettlementByDate(date)
        if (savedData != null) {
            _uiState.value = savedData
        }
    }

    fun updateField(fieldName: String, text: String) {
        _uiState.update { current ->
            when (fieldName) {
                "breakfast" -> current.copy(breakfast = text)
                "lunch" -> current.copy(lunch = text)
                "dinner" -> current.copy(dinner = text)
                "snack" -> current.copy(snack = text)
                "remark" -> current.copy(remark = text)
                "currentWeight" -> {
                    // 💡 [백스페이스 버그 해결] 사용자가 다 지웠을 때(" ")는 0f로 변환하되,
                    // UI 단에서 좀비처럼 살아나지 않도록 상태 필드 분리 바인딩의 기반을 마련합니다.
                    val weightVal = text.toFloatOrNull() ?: 0f
                    current.copy(currentWeight = weightVal, weight = weightVal)
                }
                "exercise" -> {
                    current.copy(
                        exercise = text,
                        hasExercise = text.isNotBlank()
                    )
                }
                else -> current
            }
        }
    }

    fun deleteSettlement(date: String) = viewModelScope.launch {
        try {
            // 1. Room 삭제
            settlementDao.deleteByDate(date)

            // 2. Firebase 삭제 (Firestore 경로에 맞게 작성)
            val userId = auth.currentUser?.uid ?: return@launch
            firestore.collection("users").document(userId)
                .collection("settlements").document(date)
                .delete()
                .await()
            onToastShown("정산 기록이 삭제 되었습니다.")
        } catch (e: Exception) {
            onToastShown("삭제 실패: ${e.message}")
        }
    }

    fun withdrawAccount(context: android.content.Context, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            val currentUser = auth.currentUser
            if (currentUser != null) {
                val userId = currentUser.uid

                // 1. 원격 Firebase Firestore 데이터 삭제
                // (유저 메타데이터 및 하위 정산 기록 컬렉션 삭제)
                // 주의: 프로덕션 환경에서 하위 컬렉션이 많다면 Cloud Functions나 반복문 분할 삭제가 안전합니다.
                firestore.collection("users").document(userId)
                    .delete()
                    .await()

                // 2. 로컬 Room DB 데이터 전체 삭제 (현재 로그인했던 유저의 기록 초기화)
                // 데이터 혼선을 방지하기 위해 clearAllTables 또는 기존 Dao의 삭제 메서드를 호출합니다.
                // 예: settlementDao.deleteAll()이 구현되어 있다면 사용, 없다면 아래 쿼리 기반 메서드 추가 필요
                settlementDao.clearAllSettlements()
                userProfileDao.clearProfile()

                // 3. Firebase Authentication 유저 계정 탈퇴 처리
                currentUser.delete().await()

                onToastShown("회원탈퇴가 정상적으로 처리되었습니다.")
                onResult(true)
            } else {
                onToastShown("인증 정보가 만료되었습니다. 다시 로그인 해주세요.")
                onResult(false)
            }
        } catch (e: Exception) {
            // Firebase 보안 규칙 상, 로그인한 지 오래된 유저는 계정 삭제 시 '상대적 최근 인증(Requires Recent Login)' 에러가 발생할 수 있습니다.
            if (e.message?.contains("CREDENTIAL_TOO_OLD_LOGIN_AGAIN") == true) {
                onToastShown("보안을 위해 재인증이 필요합니다. 로그아웃 후 다시 로그인하여 시도해 주세요.")
            } else {
                onToastShown("회원탈퇴 실패: ${e.localizedMessage}")
            }
            onResult(false)
        }
    }

    fun analyzeAndFinalize(
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        viewModelScope.launch {
            val currentState = _uiState.value
            _uiState.update { it.copy(analyzing = true) }

            try {
                val profile = userProfileDao.getProfile() ?: throw Exception("유저 프로필 정보가 없습니다. 설정을 먼저 완료해주세요.")
                val userHeight = profile.height

                val analyzedData = repository.analyzeAndSave(
                    currentState.copy(isMale = _isMale.value),
                    profile
                )
                _uiState.update { analyzedData.copy(analyzing = false) }
                val savedWeight = _uiState.value.currentWeight
                cachedWeight = savedWeight
                onToastShown("분석 및 저장이 완료되었습니다.")

                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(analyzing = false) }

                val ToastMessage = "오류 발생: ${e.message}"
                onToastShown(ToastMessage)
                // 💡 실패 시 화면 이동 없이 현재 데이터 유지(onFailure 호출)
                onFailure(e.message ?: "알 수 없는 오류")
            }
        }
    }

    fun logout(context: Context, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                _isLoggingOut.value = true
                // 1. Firebase 로그아웃 (이건 기본적으로 비동기지만 안전하게 처리)
                auth.signOut()

                val credentialManager = CredentialManager.create(context)
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    // Credential 삭제 실패는 무시해도 괜찮습니다.
                    e.printStackTrace()
                }


                // 2. Room DB 삭제 작업을 백그라운드 스레드(IO)에서 실행
                withContext(Dispatchers.IO) {
                    repository.clearAllLocalData()
                }
                _isLoggingOut.value = false // 로딩 종료
                onResult(true)

            } catch (e: Exception) {
                e.printStackTrace()
                onResult(false)
            } finally {
                _isLoggingOut.value = false
            }
        }
    }
    // 토스트를 보여준 후 반드시 호출해야 함
    fun onToastShown(message : String) {
        toastMessage = message
        toastMessage = null
    }
}