package com.youngs.dailynet.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.local.entity.dao.DailyRecordDao
import com.youngs.dailynet.data.local.entity.dao.UserProfileDao
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.network.BillingManager.Companion.PRODUCT_ID_MONTHLY
import com.youngs.dailynet.data.repository.DailyRecordRepository
import com.youngs.dailynet.ui.view.getWeekIdentifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class CategoryInfo(
    val label: String,
    val hint: String,
    val fieldName: String
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: DailyRecordRepository,
    private val dailyRecordDao: DailyRecordDao,
    private val userProfileDao: UserProfileDao,
    val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    val billingManager: com.youngs.dailynet.data.network.BillingManager // 👑 결제 매니저 주입
) : BaseViewModel() {

    private val UNINITIALIZED = -1 // 오늘 횟수 가져오는데 실패하면 _todayCount를 -1로 셋팅해서 다시 시도하도록 유도함

    init {
        billingManager.onPurchaseSuccess = { purchase ->
            updateSubscriptionStatus()
        }
    }

    val mainListState = LazyListState()

    private val _isPagingLoading = MutableStateFlow(false)
    val isPagingLoading = _isPagingLoading.asStateFlow()


    val isLastPageReached: Boolean
        get() = repository.isLastPageReached

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

    fun saveInitialProfile(
        googleName: String,
        height: Float,
        weight: Float,
        isMale: Boolean,
        birthDate: String
    ) {
        val uid = currentUserId ?: return

        viewModelScope.launch {
            try {
                val timestamp = System.currentTimeMillis()

                val serverProfile = mapOf(
                    "googleName" to googleName,
                    "height" to height,
                    "initialWeight" to weight,
                    "isMale" to isMale,
                    "birthDate" to birthDate,
                    "todayAnalysisCount" to 0,
                    "lastAnalyzedDate" to "",
                    "createdAt" to timestamp,
                    "isSubscribed" to false
                )
                firestore.collection("users").document(uid).set(serverProfile).await()

                userProfileDao.insertProfile(
                    UserProfileEntity(
                        height = height,
                        initialWeight = weight,
                        isMale = isMale,
                        birthDate = birthDate,
                        createdAt = timestamp,
                        isSubscribed = false
                    )
                )

                _isMale.value = isMale
                showProfileDialog = false
                _uiState.update { it.copy(weight = weight) }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("프로필 저장 실패: ${e.message}")

            }
        }
    }

    fun checkProfile() {
        val uid = currentUserId ?: return

        viewModelScope.launch {
            try {
                // 1. Firestore에서 최신 정보 먼저 땡겨오기 (로그인 후 최초 기기 연동이나 데이터 동기화를 위해)
                val document = firestore.collection("users").document(uid).get().await()

                if (document.exists() && document.getDouble("height") != null) {
                    val serverHeight = document.getDouble("height")!!.toFloat()
                    val initialWeight = (document.getDouble("initialWeight") ?: 0.0).toFloat()
                    val isMale = document.getBoolean("isMale") ?: true
                    val birthDate = document.getString("birthDate") ?: ""
                    val todayAnalysisCount = (document.getLong("todayAnalysisCount") ?: 0L).toInt()
                    val createdAt = document.getLong("createdAt") ?: System.currentTimeMillis()
                    // 💡 서버에 저장되어 있던 마지막 분석일 획득
                    val lastAnalyzedDate = document.getString("lastAnalyzedDate") ?: ""
                    val isSubscribed = document.getBoolean("isSubscribed") == true

                    // 2. 서버 데이터로 로컬 DB 갱신 (마지막 분석일 포함)
                    userProfileDao.insertProfile(
                        UserProfileEntity(
                            height = serverHeight,
                            initialWeight = initialWeight,
                            isMale = isMale,
                            birthDate = birthDate,
                            todayAnalysisCount = todayAnalysisCount,
                            createdAt = createdAt,
                            lastAnalyzedDate = lastAnalyzedDate,
                            isSubscribed = isSubscribed
                        )
                    )

                    _isMale.value = isMale
                    showProfileDialog = false

                    repository.resetPagingState()
                    repository.fetchAndSyncFromFirebase()
                } else {
                    // 서버에 데이터가 아예 없으면 프로필 설정 팝업 띄우기
                    showProfileDialog = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // 네트워크 에러 등으로 실패 시, 차선책으로 로컬에 있는 기존 프로필이라도 로드
                val localProfile = userProfileDao.getProfile()
                if (localProfile != null) {
                    showProfileDialog = false
                    _isMale.value = localProfile.isMale
                    repository.resetPagingState()
                    repository.fetchAndSyncFromFirebase()
                } else {
                    showProfileDialog = true
                }
            }
        }
    }

    fun loadMoreDailyRecords() {
        // 💡 1. 진입하자마자 '동기적'으로 즉시 상태를 검사하고 리턴합니다.
        if (repository.isLastPageReached || _isPagingLoading.value) return

        // 💡 2. 코루틴을 열기 전에 '즉시' 로딩 상태를 true로 잠가서 중복 연타를 원천 차단합니다.
        _isPagingLoading.value = true

        viewModelScope.launch {
            try {
                repository.fetchAndSyncFromFirebase()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 💡 3. 성공하든 에러가 나든 마지막엔 확실하게 락을 해제합니다.
                _isPagingLoading.value = false
            }
        }
    }

    val allDailyRecord: StateFlow<List<DailyRecordModel>> = repository.getAllDailyRecordRoom()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val totalNetCalories: StateFlow<Int> = allDailyRecord
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
        CategoryInfo(
            "비고",
            "오늘의 특이사항 \n ex)삼겹살 먹을 때 비계 떼고 살코기 위주로 먹었고, 쌈을 많이 싸먹었어요.\n도보를 6000보 걸었어요.",
            "remark"
        )
    )

    private val _uiState = MutableStateFlow(DailyRecordModel(date = today))
    val uiState = _uiState.asStateFlow()

    val weeklyCaloriesMap: StateFlow<Map<Int, Int>> = allDailyRecord
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


    fun prepareDailyRecordData(targetDate: String) = viewModelScope.launch {
        try {
            if (_uiState.value.analyzing) return@launch

            val savedDraft = repository.getDailyRecordByDate(targetDate)
            val lastRecordedWeight = repository.getLatestWeight(targetDate)

            if (savedDraft != null) {
                if (savedDraft.weight > 0f) {
                    _uiState.value = savedDraft
                    cachedWeight = savedDraft.weight
                } else {
                    // 💡 [수정] 이전 몸무게를 캐시에 넣음과 동시에, 화면 상태(uiState)에도 카피해서 넣어줍니다!
                    cachedWeight = lastRecordedWeight
                    _uiState.value = savedDraft.copy(weight = lastRecordedWeight)
                }
            } else {
                if (lastRecordedWeight > 0f) {
                    cachedWeight = lastRecordedWeight
                }

                _uiState.value = DailyRecordModel(
                    date = targetDate,
                    weight = cachedWeight,
                    breakfast = "",
                    lunch = "",
                    dinner = "",
                    snack = "",
                    exercise = "",
                    remark = "",
                    analysisResult = "",
                    netCalories = 0,
                    hasExercise = false,
                    finalized = false,
                    analyzing = false
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
                "weight" -> {
                    // 💡 [백스페이스 버그 해결] 사용자가 다 지웠을 때(" ")는 0f로 변환하되,
                    // UI 단에서 좀비처럼 살아나지 않도록 상태 필드 분리 바인딩의 기반을 마련합니다.
                    val weightVal = text.toFloatOrNull() ?: 0f
                    current.copy(weight = weightVal)
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

    fun deleteDailyRecord(date: String) = viewModelScope.launch {
        try {
            // 1. Room 삭제
            dailyRecordDao.deleteByDate(date)

            // 2. Firebase 삭제 (Firestore 경로에 맞게 작성)
            val userId = auth.currentUser?.uid ?: return@launch
            firestore.collection("users").document(userId)
                .collection("settlements").document(date)
                .delete()
                .await()
            showToast("정산 기록이 삭제 되었습니다.")
        } catch (e: Exception) {
            showToast("삭제 실패: ${e.message}")
        }
    }


    fun withdrawAccount(context: android.content.Context, onResult: (Boolean) -> Unit) =
        viewModelScope.launch {
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
                    dailyRecordDao.clearAllDailyRecord()
                    userProfileDao.clearProfile()

                    // 3. Firebase Authentication 유저 계정 탈퇴 처리
                    currentUser.delete().await()

                    showToast("회원탈퇴가 정상적으로 처리되었습니다.")
                    onResult(true)
                } else {
                    showToast("인증 정보가 만료되었습니다. 다시 로그인 해주세요.")
                    onResult(false)
                }
            } catch (e: Exception) {
                // Firebase 보안 규칙 상, 로그인한 지 오래된 유저는 계정 삭제 시 '상대적 최근 인증(Requires Recent Login)' 에러가 발생할 수 있습니다.
                if (e.message?.contains("CREDENTIAL_TOO_OLD_LOGIN_AGAIN") == true) {
                    showToast("보안을 위해 재인증이 필요합니다. 로그아웃 후 다시 로그인하여 시도해 주세요.")
                } else {
                    showToast("회원탈퇴 실패: ${e.localizedMessage}")
                }
                onResult(false)
            }
        }


    fun checkAndAnalyze(activity: android.app.Activity) {
        viewModelScope.launch {
            try {
                val profile = userProfileDao.getProfile()
                // 1. 구독 상태 확인 (관리자 포함)
                billingManager.checkSubscriptionStatus(auth.currentUser?.email) { isSubscribed ->
                    viewModelScope.launch {

                        if (isSubscribed) {
                            analyzeAndFinalize(onSuccess = {
                                showToast("분석 및 저장이 완료되었습니다.")
                            }, onFailure = {})
                        } else {

                            if (profile.lastAnalyzedDate == today && profile.todayAnalysisCount >= 3) {
                                showToast("오늘 분석 횟수(3회)를 초과했습니다.")
                                startSubscription(activity)
                                return@launch
                            }

                            analyzeAndFinalize(
                                onSuccess = { count -> showToast("분석 완료! 오늘 $count/3회 분석했습니다.") },
                                onFailure = { showToast("분석 실패: $it") }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                showToast("데이터 확인 실패: ${e.message}")
            }
        }
    }

    fun analyzeAndFinalize(
        onSuccess: ( newCount: Int ) -> Unit,
        onFailure: (String) -> Unit
    ) {
        viewModelScope.launch {
            val uid = currentUserId ?: return@launch
            val currentState = _uiState.value
            _uiState.update { it.copy(analyzing = true) }

            try {
                val latestProfile = userProfileDao.getProfile()

                val newCount = if (latestProfile.lastAnalyzedDate == today) {
                    (latestProfile.todayAnalysisCount + 1)
                } else {
                    1
                }

                val analyzedData = repository.analyzeAndSave(
                    currentState.copy(isMale = _isMale.value),
                    latestProfile
                )


                // 1. 로컬 DB 갱신 (마지막 분석일 + 횟수)
                val refreshedProfile = userProfileDao.updateAndGetLatest(newCount, today)
                Log.d("DB_DEBUG", "방금 DB에 저장된 최신 값: ${refreshedProfile?.todayAnalysisCount}")

                // 💡 [성공 시 2] 파이어베이스 Firestore 유저 문서에도 오늘 날짜 업데이트
                firestore.collection("users").document(uid).update(
                    mapOf("todayAnalysisCount" to newCount, "lastAnalyzedDate" to today)
                ).await()

                _uiState.update { analyzedData.copy(analyzing = false) }
                val savedWeight = _uiState.value.weight
                cachedWeight = savedWeight

                val checkProfile = userProfileDao.getProfile()
                Log.d("DB_DEBUG", "수정 직후 강제 재조회 값: ${checkProfile?.todayAnalysisCount}")

                onSuccess(newCount)
            } catch (e: Exception) {
                _uiState.update { it.copy(analyzing = false) }

                showToast("오류 발생: ${e.message}")
                // 💡 실패 시 화면 이동 없이 현재 데이터 유지(onFailure 호출)
                onFailure(e.message ?: "알 수 없는 오류")
            }
        }
    }


    fun clearTodayDraft() {
        // 오늘의 정산을 새로 입력할때
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        _uiState.value = DailyRecordModel(
            date = today,
            weight = cachedWeight, // 기존에 저장해 둔 최근 몸무게만 유지
            breakfast = "",
            lunch = "",
            dinner = "",
            snack = "",
            exercise = "",
            remark = "",
            analysisResult = "",
            netCalories = 0,
            hasExercise = false,
            finalized = false,
            analyzing = false
        )
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

    // 👑 유저가 화면에서 구독하기 버튼을 누를 때 호출할 함수
    fun startSubscription(activity: android.app.Activity) {
        billingManager.launchBillingFlow(activity, PRODUCT_ID_MONTHLY)
    }

    // 👑 결제 성공 시 Firestore 서버와 로컬 DB를 동기화하는 함수
    private fun updateSubscriptionStatus() = viewModelScope.launch {
        val uid = currentUserId ?: return@launch
        try {
            // 1. 원격 Firestore 업데이트
            firestore.collection("users").document(uid).update("isSubscribed", true).await()

            // 2. 로컬 Room DB 동기화
            val profile = userProfileDao.getProfile()
            if (profile != null) {
                userProfileDao.insertProfile(profile.copy(isSubscribed = true))
            }
            showToast("👑 프리미엄 멤버십이 활성화되었습니다!")
        } catch (e: Exception) {
            showToast("구독 갱신 실패: ${e.message}")
        }
    }

    fun syncSubscriptionStatus() = viewModelScope.launch {
        val user = auth.currentUser ?: return@launch
        val uid = user.uid
        val email = user.email

        // 1. 구글 결제 서버에 진짜 구독 중인지 확인
        billingManager.checkSubscriptionStatus(email) { isSubscribed ->
            viewModelScope.launch {
                // 2. 파이어베이스와 로컬 DB 업데이트
                firestore.collection("users").document(uid).update("isSubscribed", isSubscribed)
                    .await()

                val profile = userProfileDao.getProfile()
                if (profile != null) {
                    userProfileDao.insertProfile(profile.copy(isSubscribed = isSubscribed))
                }

                if (!isSubscribed) {
                    // 구독이 만료된 경우 로그 출력 혹은 알림
                    println("👑 구독 정보가 만료되었습니다. 로컬 DB 동기화 완료.")
                }
            }
        }
    }
}