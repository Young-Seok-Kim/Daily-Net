package com.youngs.dailynet.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.local.entity.dao.DailyRecordDao
import com.youngs.dailynet.data.local.entity.dao.UserProfileDao
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.data.network.AnalysisLimitException
import com.youngs.dailynet.data.network.BillingManager.Companion.PRODUCT_ID_MONTHLY
import com.youngs.dailynet.data.network.GeminiManager
import com.youngs.dailynet.data.network.PhotoLimitException
import com.youngs.dailynet.data.repository.DailyRecordRepository
import com.youngs.dailynet.util.DailyReminder
import com.youngs.dailynet.util.MealPhoto
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
import androidx.annotation.StringRes
import com.youngs.dailynet.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 입력 화면의 항목 한 줄.
 * 라벨과 힌트는 언어별로 달라지므로 문자열이 아니라 리소스 ID를 들고 있는다.
 */
data class CategoryInfo(
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
    val fieldName: String
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context, // 토스트 문구를 언어에 맞게 꺼내기 위해 필요
    private val repository: DailyRecordRepository,
    private val dailyRecordDao: DailyRecordDao,
    private val userProfileDao: UserProfileDao,
    val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    val billingManager: com.youngs.dailynet.data.network.BillingManager, // 👑 결제 매니저 주입
    private val adminManager: com.youngs.dailynet.util.AdminManager, // 무제한 사용자 판별
    private val geminiManager: GeminiManager // 음식 사진 → 메뉴 텍스트 변환
) : BaseViewModel() {

    private val UNINITIALIZED = -1 // 오늘 횟수 가져오는데 실패하면 _todayCount를 -1로 셋팅해서 다시 시도하도록 유도함

    /** 무료 사용자의 하루 분석 횟수. 최종 판단은 서버(quota.js)가 하며 여기는 사전 확인용이다. */
    private val DAILY_FREE_ANALYSIS_LIMIT = 3

    /**
     * 서버 기준으로 오늘 무료 분석을 다 썼는지 확인하고, 로컬 값도 함께 맞춘다.
     *
     * 확인에 실패하면 **막지 않는다.** 진짜 초과라면 어차피 서버가 429로 거절하므로,
     * 통신이 안 될 때 사용자를 미리 막아버리는 쪽이 더 나쁜 실패다.
     */
    private suspend fun isOverDailyLimitOnServer(): Boolean {
        val uid = currentUserId ?: return false
        return try {
            val doc = firestore.collection("users").document(uid).get().await()
            val count = (doc.getLong("todayAnalysisCount") ?: 0L).toInt()
            val date = doc.getString("lastAnalyzedDate").orEmpty()
            userProfileDao.updateAndGetLatest(count, date)
            date == today && count >= DAILY_FREE_ANALYSIS_LIMIT
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 기기 언어에 맞는 문구로 토스트를 띄운다. */
    private fun toast(@StringRes resId: Int, vararg args: Any) {
        showToast(context.getString(resId, *args))
    }

    /** 사진에서 메뉴를 읽는 중인 항목명. 해당 입력창에만 진행 표시를 띄우려고 둔다. */
    private val _photoProcessingField = MutableStateFlow<String?>(null)
    val photoProcessingField = _photoProcessingField.asStateFlow()

    /**
     * 찍은 음식 사진을 서버로 보내 메뉴명을 받아 입력창에 채운다.
     *
     * 결과를 확정으로 다루지 않고 **입력창 초안**으로만 넣는다. AI의 양 추정은 정확하지 않아
     * 사용자가 고칠 수 있어야 하고, 이미 적어둔 내용도 지우지 않고 뒤에 덧붙인다.
     *
     * 사진은 서버에도 기기에도 남기지 않는다. 텍스트로 바꾼 뒤 임시 파일을 지운다.
     */
    fun extractMealFromPhoto(uri: Uri, fieldName: String) {
        viewModelScope.launch {
            _photoProcessingField.value = fieldName
            try {
                val encoded = withContext(Dispatchers.IO) {
                    MealPhoto.encodeToBase64(context, uri)
                }
                if (encoded == null) {
                    toast(R.string.photo_read_failed)
                    return@launch
                }

                val extracted = try {
                    geminiManager.extractMealFromPhoto(encoded)
                } catch (e: PhotoLimitException) {
                    // 다시 찍어도 안 되는 상황이므로 "못 알아봤다"와 다르게 안내한다.
                    // 무료 사용자에게는 여기가 이탈 지점이 아니라 구독을 권할 자리다.
                    val subscribed = userProfileDao.getProfile()?.isSubscribed == true
                    toast(
                        if (subscribed) R.string.photo_limit_reached
                        else R.string.photo_limit_free
                    )
                    return@launch
                }

                if (extracted.isNullOrBlank()) {
                    toast(R.string.photo_no_food)
                    return@launch
                }

                val current = currentFieldValue(fieldName)
                updateField(
                    fieldName,
                    if (current.isBlank()) extracted else "$current, $extracted"
                )
                toast(R.string.photo_filled)
            } finally {
                _photoProcessingField.value = null
                MealPhoto.deleteTemp(context)
            }
        }
    }

    private fun currentFieldValue(fieldName: String): String = with(_uiState.value) {
        when (fieldName) {
            "breakfast" -> breakfast
            "lunch" -> lunch
            "dinner" -> dinner
            "snack" -> snack
            else -> ""
        }
    }

    init {
        billingManager.onPurchaseSuccess = { purchase ->
            updateSubscriptionStatus()
        }
    }

    val mainListState = LazyListState()

    // 최근 추이 차트의 가로 스크롤 위치. 상세 화면에 갔다 돌아와도 보던 구간이 유지되도록 ViewModel에 보관
    val trendChartState = LazyListState()

    // 몸무게 추이 차트의 가로 스크롤 위치 (위와 같은 이유).
    // 그래프 → 정산 상세 → 뒤로가기로 돌아와도 보던 구간이 그대로 남는다.
    val weightChartState = LazyListState()

    // 위 스크롤을 어느 기준 날짜에 맞춰뒀는지. 기준일이 바뀔 때만 다시 맞춘다.
    // (돌아올 때마다 다시 맞추면 사용자가 옮겨둔 위치가 날아간다)
    var weightChartFocusedDate: String? = null

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

    /** 화면에서 "오늘 날짜인지" 판별할 수 있도록 노출 */
    val todayDate: String get() = today

    // 방금 분석을 마친 결과인지 (화면에서 완료 연출 트리거로 사용)
    private val _shouldStreamResult = MutableStateFlow(false)
    val shouldStreamResult = _shouldStreamResult.asStateFlow()
    fun onResultStreamed() { _shouldStreamResult.value = false }

    /**
     * Health Connect에서 읽어온 날짜별 걸음 수 캐시 (yyyy-MM-dd → 걸음 수).
     * prepareDailyRecordData()가 uiState를 통째로 갈아끼우면서 자동 기입된 값을 지워버리는 문제가 있어,
     * 읽어둔 값을 여기에 들고 있다가 데이터 로드가 끝난 뒤 다시 반영한다.
     */
    private val autoStepsByDate = mutableMapOf<String, Int>()

    /**
     * 정산 데이터 로드가 끝날 때마다 증가한다.
     * 화면(DailyRecordScreen)은 이 값을 key로 걸음수를 다시 읽어 반영한다.
     */
    private val _recordLoadToken = MutableStateFlow(0)
    val recordLoadToken = _recordLoadToken.asStateFlow()

    /**
     * Health Connect에서 읽은 걸음 수를 화면에 반영한다.
     *
     * 날짜별로 규칙이 다르다.
     * - 오늘: 하루 종일 값이 늘어나므로 열 때마다 최신 걸음으로 갱신
     * - 과거: 이미 끝난 날이라 저장된 값이 정답. **비어 있을 때만** 채운다
     * - 공통: 사용자가 직접 입력한 값(stepsManual=true)은 건드리지 않는다
     *
     * @param force 새로고침 버튼으로 명시적으로 요청한 경우. 위 조건을 모두 무시하고 덮어쓴다.
     */
    fun applyAutoSteps(date: String, steps: Int, force: Boolean = false) {
        autoStepsByDate[date] = steps

        val current = _uiState.value
        // 화면이 이미 다른 날짜로 넘어갔거나 분석 중이면 반영하지 않는다
        if (current.date != date || current.analyzing) return

        when {
            // 새로고침 버튼: 손으로 넣은 값도 실제 걸음 수로 되돌린다
            force -> _uiState.update { it.copy(steps = steps, stepsManual = false) }
            current.stepsManual -> return
            date == today -> _uiState.update { it.copy(steps = steps) }
            // 과거 날짜는 비어 있을 때만 채운다 (저장된 값을 덮어쓰지 않도록)
            current.steps == 0 -> _uiState.update { it.copy(steps = steps) }
        }
    }

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
                    "email" to auth.currentUser?.email.orEmpty(),
                    "height" to height,
                    "initialWeight" to weight,
                    "isMale" to isMale,
                    "birthDate" to birthDate,
                    "todayAnalysisCount" to 0,
                    "lastAnalyzedDate" to "",
                    "isSubscribed" to false,
                    "profileCompleted" to true
                )
                // createdAt은 markSignedUp이 최초 로그인 시각으로 이미 넣어뒀다.
                // 여기서 다시 쓰면 "가입일시"가 "입력 완료 시각"으로 밀리므로 merge로 보존한다.
                firestore.collection("users").document(uid)
                    .set(serverProfile, SetOptions.merge())
                    .await()

                userProfileDao.insertProfile(
                    UserProfileEntity(
                        email = auth.currentUser?.email.orEmpty(),
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
                toast(R.string.toast_profile_save_failed, e.message.orEmpty())

            }
        }
    }

    /** 프로필 수정 화면에서 현재 값을 보여주기 위한 관찰용 스트림 */
    val userProfile = userProfileDao.getProfileFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 가입 때 입력한 신체 정보(키 / 시작 몸무게 / 성별 / 생년월일)를 수정한다.
     *
     * 서버에는 set() 대신 update()를 쓴다. set()으로 덮으면 구독 여부(isSubscribed)나
     * 오늘 분석 횟수(todayAnalysisCount) 같은 다른 필드가 함께 날아가기 때문.
     */
    fun updateProfile(
        height: Float,
        initialWeight: Float,
        isMale: Boolean,
        birthDate: String,
        onDone: (Boolean) -> Unit
    ) {
        val uid = currentUserId
        if (uid == null) {
            onDone(false)
            return
        }

        viewModelScope.launch {
            try {
                firestore.collection("users").document(uid).update(
                    mapOf(
                        "height" to height,
                        "initialWeight" to initialWeight,
                        "isMale" to isMale,
                        "birthDate" to birthDate
                    )
                ).await()

                // 로컬은 나머지 필드(구독 상태 등)를 유지한 채 갈아끼운다
                val current = userProfileDao.getProfile()
                userProfileDao.insertProfile(
                    current.copy(
                        height = height,
                        initialWeight = initialWeight,
                        isMale = isMale,
                        birthDate = birthDate
                    )
                )

                _isMale.value = isMale
                toast(R.string.toast_profile_updated)
                onDone(true)
            } catch (e: Exception) {
                e.printStackTrace()
                toast(R.string.toast_profile_update_failed, e.message.orEmpty())
                onDone(false)
            }
        }
    }

    /**
     * 로그인은 했지만 아직 신체 정보를 입력하지 않은 사용자를 기록해 둔다.
     *
     * 목적은 온보딩 이탈을 콘솔에서 볼 수 있게 하는 것뿐이라, 실패해도 로그인 흐름을 막지 않는다.
     * [profileCompleted]가 false인 문서 = 가입은 했는데 입력 화면에서 나간 사람.
     *
     * @param alreadyExists 문서가 이미 있으면 가입일시를 덮어쓰지 않는다.
     *                      앱을 켤 때마다 갱신되면 "언제 가입했는지"의 의미가 사라진다.
     */
    private suspend fun markSignedUp(uid: String, alreadyExists: Boolean) {
        try {
            val stub = mutableMapOf<String, Any>(
                "email" to auth.currentUser?.email.orEmpty(),
                "googleName" to auth.currentUser?.displayName.orEmpty(),
                "profileCompleted" to false
            )
            if (!alreadyExists) {
                stub["createdAt"] = Timestamp.now()
            }
            firestore.collection("users").document(uid)
                .set(stub, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            e.printStackTrace()
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
                    // 콘솔에서 날짜로 보이도록 Timestamp로 저장한다.
                    // 예전 문서는 밀리초 숫자로 들어 있어서 둘 다 받아준다.
                    val createdAtRaw = document.get("createdAt")
                    val createdAt = when (createdAtRaw) {
                        is Timestamp -> createdAtRaw.toDate().time
                        is Number -> createdAtRaw.toLong()
                        else -> System.currentTimeMillis()
                    }
                    // 💡 서버에 저장되어 있던 마지막 분석일 획득
                    val lastAnalyzedDate = document.getString("lastAnalyzedDate") ?: ""
                    val isSubscribed = document.getBoolean("isSubscribed") == true
                    // 서버 값이 아직 없으면(기존 사용자) 로그인 계정 이메일을 쓴다
                    val profileEmail = document.getString("email")
                        ?: auth.currentUser?.email.orEmpty()

                    // 2. 서버 데이터로 로컬 DB 갱신 (마지막 분석일 포함)
                    userProfileDao.insertProfile(
                        UserProfileEntity(
                            email = profileEmail,
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

                    // 💡 기존 사용자 문서를 조용히 보정한다. (앱을 켜기만 하면 적용되고 재로그인 불필요)
                    //    쓰기는 한 번으로 묶고, 실패해도 로그인 흐름을 막지 않는다.
                    val patch = mutableMapOf<String, Any>()

                    // 예전 문서에는 email 필드가 없다
                    val authEmail = auth.currentUser?.email.orEmpty()
                    if (authEmail.isNotEmpty() && document.getString("email") != authEmail) {
                        patch["email"] = authEmail
                    }
                    // 예전 문서의 createdAt은 밀리초 숫자라 콘솔에서 읽을 수 없다 → Timestamp로 변환
                    if (createdAtRaw is Number) {
                        patch["createdAt"] = Timestamp(Date(createdAt))
                    }
                    // 가입 기록만 남기고 이탈한 문서(markSignedUp)와 구분되도록 완료 표시를 채운다.
                    // b24 이전에 가입한 사용자의 문서에는 이 필드 자체가 없다.
                    if (document.getBoolean("profileCompleted") != true) {
                        patch["profileCompleted"] = true
                    }
                    // 스텁 쓰기가 실패했던 문서는 가입일시가 비어 있을 수 있다
                    if (createdAtRaw == null) {
                        patch["createdAt"] = Timestamp(Date(createdAt))
                    }

                    if (patch.isNotEmpty()) {
                        try {
                            firestore.collection("users").document(uid)
                                .set(patch, SetOptions.merge())
                                .await()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    _isMale.value = isMale
                    showProfileDialog = false

                    // 캐시가 비었을 때(첫 로그인/최초)만 전체를 가져오고, 이후엔 캐시 사용
                    if (repository.getCachedRecordCount() == 0) {
                        repository.fetchAllFromFirebase()
                    }
                } else {
                    // 신체 정보가 아직 없는 사용자.
                    //
                    // 예전에는 [저장 및 시작]을 눌러야만 문서가 생겨서, 로그인만 하고
                    // 입력 화면에서 이탈한 사람은 Firestore에 아무 흔적도 남지 않았다.
                    // (Authentication에는 계정이 있는데 users 문서가 없는 상태)
                    // 그래서 가입자 수도, 어디서 이탈했는지도 알 수 없었다.
                    // 최소 정보만이라도 먼저 남겨 온보딩 이탈이 보이게 한다.
                    markSignedUp(uid, alreadyExists = document.exists())
                    showProfileDialog = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // 네트워크 에러 등으로 실패 시, 차선책으로 로컬에 있는 기존 프로필이라도 로드
                val localProfile = userProfileDao.getProfile()
                if (localProfile != null) {
                    showProfileDialog = false
                    _isMale.value = localProfile.isMale
                    // 캐시가 비었을 때(첫 로그인/최초)만 전체를 가져오고, 이후엔 캐시 사용
                    if (repository.getCachedRecordCount() == 0) {
                        repository.fetchAllFromFirebase()
                    }
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
        CategoryInfo(R.string.category_breakfast, R.string.category_breakfast_hint, "breakfast"),
        CategoryInfo(R.string.category_lunch, R.string.category_lunch_hint, "lunch"),
        CategoryInfo(R.string.category_dinner, R.string.category_dinner_hint, "dinner"),
        CategoryInfo(R.string.category_snack, R.string.category_snack_hint, "snack"),
        CategoryInfo(R.string.category_exercise, R.string.category_exercise_hint, "exercise"),
        CategoryInfo(R.string.category_remark, R.string.category_remark_hint, "remark")
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
            // 저장된 기록을 불러올 때는 스트리밍하지 않고 즉시 표시
            _shouldStreamResult.value = false

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

            // 💡 uiState를 통째로 교체한 뒤이므로, 이미 읽어둔 그날 걸음 수를 다시 반영한다.
            //    (기존에는 Health Connect 조회가 이 로드보다 먼저 끝나면 걸음수가 0으로 덮여버렸다)
            autoStepsByDate[targetDate]?.let { applyAutoSteps(targetDate, it) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // 로드가 끝났음을 화면에 알려 걸음수를 (재)조회하게 한다
            _recordLoadToken.update { it + 1 }
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

                // 사용자가 직접 입력하면 stepsManual = true → 이후 자동 갱신이 덮어쓰지 않음
                "steps" -> current.copy(
                    steps = text.filter { it.isDigit() }.toIntOrNull() ?: 0,
                    stepsManual = true
                )

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
            toast(R.string.toast_record_deleted)
        } catch (e: Exception) {
            toast(R.string.toast_delete_failed, e.message.orEmpty())
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

                    toast(R.string.toast_withdraw_success)
                    onResult(true)
                } else {
                    toast(R.string.toast_auth_expired)
                    onResult(false)
                }
            } catch (e: Exception) {
                // Firebase 보안 규칙 상, 로그인한 지 오래된 유저는 계정 삭제 시 '상대적 최근 인증(Requires Recent Login)' 에러가 발생할 수 있습니다.
                if (e.message?.contains("CREDENTIAL_TOO_OLD_LOGIN_AGAIN") == true) {
                    toast(R.string.toast_reauth_required)
                } else {
                    toast(R.string.toast_withdraw_failed, e.localizedMessage.orEmpty())
                }
                onResult(false)
            }
        }


    fun checkAndAnalyze(activity: android.app.Activity) {
        viewModelScope.launch {
            try {
                val profile = userProfileDao.getProfile()

                // 0. Firestore에 등록된 무제한 사용자면 횟수 제한 없이 바로 분석
                if (adminManager.isUnlimited(auth.currentUser?.email)) {
                    analyzeAndFinalize(
                        onSuccess = { toast(R.string.toast_analysis_saved) },
                        onFailure = {}
                    )
                    return@launch
                }

                // 1. 구독 상태 확인
                billingManager.checkSubscriptionStatus { isSubscribed ->
                    viewModelScope.launch {

                        if (isSubscribed) {
                            analyzeAndFinalize(onSuccess = {
                                toast(R.string.toast_analysis_saved)
                            }, onFailure = {})
                        } else {

                            // 로컬 값이 실제보다 클 수 있다. (실패한 분석을 서버가 환불했거나,
                            // 다른 기기에서 쓴 값이 아직 반영되기 전이거나)
                            // 그 상태로 막으면 남은 횟수가 있는데도 구독 창을 보게 되므로,
                            // 막기 직전에 서버 값을 한 번 확인한다.
                            if (profile.lastAnalyzedDate == today &&
                                profile.todayAnalysisCount >= DAILY_FREE_ANALYSIS_LIMIT &&
                                isOverDailyLimitOnServer()
                            ) {
                                toast(R.string.toast_analysis_limit)
                                startSubscription(activity)
                                return@launch
                            }

                            analyzeAndFinalize(
                                onSuccess = { count -> toast(R.string.toast_analysis_done, count) },
                                onFailure = { toast(R.string.toast_analysis_failed, it) }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                toast(R.string.toast_data_check_failed, e.message.orEmpty())
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

                val outcome = repository.analyzeAndSave(
                    currentState.copy(isMale = _isMale.value),
                    latestProfile
                )
                val analyzedData = outcome.record

                // 횟수는 서버가 트랜잭션으로 센 값을 그대로 따른다.
                // 서버가 세지 못한 경우(구버전 경로)에만 예전처럼 앱이 직접 계산한다.
                val newCount = outcome.usage?.count
                    ?: if (latestProfile.lastAnalyzedDate == today) {
                        latestProfile.todayAnalysisCount + 1
                    } else {
                        1
                    }

                // 1. 로컬 DB 갱신 (마지막 분석일 + 횟수)
                val refreshedProfile = userProfileDao.updateAndGetLatest(newCount, today)
                Log.d("DB_DEBUG", "방금 DB에 저장된 최신 값: ${refreshedProfile?.todayAnalysisCount}")

                // 💡 [성공 시 2] Firestore 유저 문서 갱신.
                //    서버가 이미 같은 트랜잭션 안에서 기록했으면 다시 쓰지 않는다.
                //    여기서 덮어쓰면 서버가 센 값이 앱의 계산으로 밀려 우회 여지가 생긴다.
                if (outcome.usage == null) {
                    firestore.collection("users").document(uid).update(
                        mapOf("todayAnalysisCount" to newCount, "lastAnalyzedDate" to today)
                    ).await()
                }

                // 방금 분석한 결과이므로 한 줄씩 스트리밍하도록 표시
                _shouldStreamResult.value = true
                _uiState.update { analyzedData.copy(analyzing = false) }
                val savedWeight = _uiState.value.weight
                cachedWeight = savedWeight

                val checkProfile = userProfileDao.getProfile()
                Log.d("DB_DEBUG", "수정 직후 강제 재조회 값: ${checkProfile?.todayAnalysisCount}")

                // 오늘 정산을 끝냈으니 저녁 리마인더는 울리지 않아야 한다
                DailyReminder.markRecorded(context, analyzedData.date)

                onSuccess(newCount)
            } catch (e: AnalysisLimitException) {
                // 서버가 한도 초과로 거절한 경우. 일반 실패와 안내가 달라야 한다.
                _uiState.update { it.copy(analyzing = false) }
                // 서버가 알려준 실제 횟수로 로컬을 맞춰, 다음 사전 확인이 틀리지 않게 한다
                e.usage?.let { userProfileDao.updateAndGetLatest(it.count, today) }
                toast(R.string.toast_analysis_limit)
                onFailure(context.getString(R.string.toast_analysis_limit))
            } catch (e: Exception) {
                _uiState.update { it.copy(analyzing = false) }

                toast(R.string.toast_error, e.message.orEmpty())
                // 💡 실패 시 화면 이동 없이 현재 데이터 유지(onFailure 호출)
                onFailure(e.message ?: context.getString(R.string.error_unknown))
            }
        }
    }


    fun clearTodayDraft() {
        // 오늘의 정산을 새로 입력할때
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        _uiState.value = DailyRecordModel(
            date = today,
            weight = cachedWeight, // 기존에 저장해 둔 최근 몸무게만 유지
            steps = autoStepsByDate[today] ?: 0, // 이미 읽어둔 오늘 걸음 수는 유지
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
            toast(R.string.toast_premium_activated)
        } catch (e: Exception) {
            toast(R.string.toast_subscription_failed, e.message.orEmpty())
        }
    }

    fun syncSubscriptionStatus() = viewModelScope.launch {
        val user = auth.currentUser ?: return@launch
        val uid = user.uid
        val email = user.email

        // 무제한 사용자는 결제 여부와 무관하게 구독 상태로 취급한다 (프로필 표시용)
        if (adminManager.isUnlimited(email)) {
            firestore.collection("users").document(uid).update("isSubscribed", true).await()
            userProfileDao.getProfile()?.let {
                userProfileDao.insertProfile(it.copy(isSubscribed = true))
            }
            return@launch
        }

        // 1. 구글 결제 서버에 진짜 구독 중인지 확인
        billingManager.checkSubscriptionStatus { isSubscribed ->
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