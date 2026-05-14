package com.youngs.dailynet.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youngs.dailynet.data.dao.WeightDao
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

    fun saveInitialProfile(height: Float, weight: Float) {
        viewModelScope.launch {
            weightDao.insertUserProfile(UserProfileEntity(height = height, initialWeight = weight))
            weightDao.insertWeight(WeightEntity(today, weight)) // 첫 기록 저장
            showProfileDialog = false
            _uiState.update { it.copy(weight = weight) }
        }
    }

    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    val allSettlements: StateFlow<List<SettlementModel>> = repository.getAllSettlements()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val categoryList = listOf(
        CategoryInfo("아침", "예: 사과 1개, 닭가슴살", "breakfast"),
        CategoryInfo("점심", "예: 김치찌개, 현미밥 1공기", "lunch"),
        CategoryInfo("저녁", "예: 샐러드, 스테이크, 흰쌀밥 1공기, 고등어 등등", "dinner"),
        CategoryInfo("간식", "예: 아메리카노, 견과류", "snack"),
        CategoryInfo("운동", "예: 스쿼트 100개, 런닝 5km", "exercise"),
        CategoryInfo("비고", "오늘의 특이사항 \n ex)저녁은 회식으로 4명이서 삽겹살 4인분과 소주 3병을 먹고 밥 1공기를 먹음", "noteInput")
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
                "noteInput" -> current.copy(noteInput = text)
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
                val analyzedData = repository.analyzeAndSave(currentState, userHeight)
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