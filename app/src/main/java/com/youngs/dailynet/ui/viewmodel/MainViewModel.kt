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

    fun setGender(isMale: Boolean) {
        _isMale.value = isMale
    }

    var showProfileDialog by mutableStateOf(false)
        private set

    private val currentUserId: String?
        get() = auth.currentUser?.uid

    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    fun saveInitialProfile(height: Float, weight: Float, isMale: Boolean, birthDate: String) {
        val uid = currentUserId ?: return

        viewModelScope.launch {
            try {
                val timestamp = System.currentTimeMillis()

                val serverProfile = mapOf(
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
                toastMessage = "프로필 저장 실패: ${e.message}"
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

                    loadTodayDraft()
                } else {
                    showProfileDialog = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showProfileDialog = true
            }
        }
    }

    // 💡 [구조 수정] 만약 룸 DB가 완벽히 연동되게 하려면 repository 내부에서 룸 Flow를 반환하도록 고쳐야 합니다.
    // 임시로 우선 기존 파이프라인을 유지하되, 전체 정산 리스트 동기화 로직(Sync)이 리포지토리에 구현되어 있어야 양쪽 개수가 맞습니다.
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

    // MainViewModel.kt
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
        loadTodayDraft()
    }

    /**
     * [로직 보정] 최초 진입 시점에만 가장 최근 몸무게를 깔끔하게 자동 주입합니다.
     */
    fun loadTodayDraft() = viewModelScope.launch {
        val savedDraft = repository.getSettlementByDate(today)

        val latestWeight = settlementDao.getLatestWeight()
        val profile = userProfileDao.getProfile()
        val defaultWeight = latestWeight ?: profile?.initialWeight ?: 0f

        if (savedDraft != null) {
            _uiState.value = savedDraft
            // 기존 데이터는 있으나 현재 체중 기록만 0인 경우 방어 코드
            if (savedDraft.currentWeight == 0f && defaultWeight > 0f) {
                _uiState.update { it.copy(currentWeight = defaultWeight, weight = defaultWeight) }
            }
        } else {
            // 💡 오늘 자 새로운 정산 작성이면 최초 진입할 때 룸의 최근 몸무게를 currentWeight에 미리 꽂아줍니다.
            _uiState.update { it.copy(weight = defaultWeight, currentWeight = defaultWeight) }
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

    fun analyzeAndFinalize(
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        viewModelScope.launch {
            val currentState = _uiState.value
            _uiState.update { it.copy(analyzing = true) }

            try {
                val profile = userProfileDao.getProfile()
                val userHeight = profile?.height ?: 0f

                val analyzedData = repository.analyzeAndSave(
                    currentState.copy(isMale = _isMale.value),
                    userHeight
                )
                _uiState.update { analyzedData.copy(analyzing = false) }
                toastMessage = "분석 및 저장이 완료되었습니다."

                // 💡 여기서만 화면 이동(onSuccess) 실행
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(analyzing = false) }
                toastMessage = "오류 발생: ${e.message}"

                // 💡 실패 시 화면 이동 없이 현재 데이터 유지(onFailure 호출)
                onFailure(e.message ?: "알 수 없는 오류")
            }
        }
    }
    private val _isLoggingOut = MutableStateFlow(false)
    val isLoggingOut = _isLoggingOut.asStateFlow() // 외부에서는 읽기만 가능
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
}