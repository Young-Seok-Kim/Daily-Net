package com.youngs.dailynet.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youngs.dailynet.data.local.entity.dao.WeightDao
import com.youngs.dailynet.data.local.entity.UserProfileEntity
import com.youngs.dailynet.data.local.entity.WeightEntity
import com.youngs.dailynet.data.model.SettlementModel
import com.youngs.dailynet.data.repository.SettlementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    private val weightDao: WeightDao // 👈 생성자 주입 추가
) : ViewModel() {

    private val _isMale = MutableStateFlow(true)
    val isMale = _isMale.asStateFlow()

    fun setGender(isMale: Boolean) {
        _isMale.value = isMale
    }

    // [수정 포인트 2] 초기 프로필 저장 시 성별 반영 (Room에 안 넣더라도 세션 동안 유지)
    fun saveInitialProfile(height: Float, weight: Float, isMale: Boolean, birthDate: String) {
        viewModelScope.launch {
            weightDao.insertUserProfile(
                UserProfileEntity(height = height, initialWeight = weight, birthDate = birthDate)
            )
            weightDao.insertWeight(WeightEntity(today, weight))
            _isMale.value = isMale // 성별 상태 업데이트
            showProfileDialog = false
            _uiState.update { it.copy(weight = weight) }
        }
    }

    var showProfileDialog by mutableStateOf(false)
        private set

    fun checkProfile() {
        viewModelScope.launch {
            val profile = weightDao.getUserProfile()
            if (profile == null) {
                showProfileDialog = true
            }
        }
    }

    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    val allSettlements: StateFlow<List<SettlementModel>> = repository.getAllSettlements()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val totalNetCalories: StateFlow<Int> = allSettlements
        .map { list -> list.sumOf { it.netCalories } } // 리스트의 모든 netCalories를 더함
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

    fun onToastShown() { toastMessage = null }

    init {
        loadTodayDraft()
    }

    fun loadTodayDraft() = viewModelScope.launch {
        // 1. 오늘의 정산 기록 가져오기
        val savedDraft = repository.getSettlementByDate(today)

        // 2. 몸무게 기본값 결정 로직 실행
        val latestWeight = weightDao.getLatestWeight()
        val profile = weightDao.getUserProfile()
        val defaultWeight = latestWeight ?: profile?.initialWeight ?: 0f

        if (savedDraft != null) {
            _uiState.value = savedDraft
        } else {
            // 저장된 기록이 없으면 몸무게만 기본값으로 세팅
            _uiState.update { it.copy(weight = defaultWeight) }
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
                    val weightVal = text.toFloatOrNull() ?: 0f
                    // 💡 weight와 currentWeight 둘 다 업데이트하여 혼선 방지
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

    // AI 분석 및 최종 저장 (체중 포함)
    fun analyzeAndFinalize() {
        viewModelScope.launch {
            val currentState = _uiState.value
            _uiState.update { it.copy(analyzing = true) }

            try {
                val profile = weightDao.getUserProfile()
                val userHeight = profile?.height ?: 0f // 키가 없으면 0 (프롬프트에서 처리)

                // 1. Repository를 통해 서버+로컬 동시 저장
                val analyzedData = repository.analyzeAndSave(
                    currentState.copy(isMale = _isMale.value), // 데이터 모델에 isMale 추가 전제
                    userHeight
                )
                _uiState.update { analyzedData }

                // 2. 별도의 체중 히스토리(WeightEntity)도 업데이트
                weightDao.insertWeight(WeightEntity(today, currentState.currentWeight))

                toastMessage = "분석 및 저장이 완료되었습니다."
            } catch (e: Exception) {
                _uiState.update { it.copy(analyzing = false) }
                toastMessage = "오류 발생: ${e.message}"
            }
        }
    }
}