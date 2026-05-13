//package com.youngs.dailynet.ui.viewmodel
//
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.setValue
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.viewModelScope
//import com.youngs.dailynet.data.model.SettlementModel
//import com.youngs.dailynet.data.repository.SettlementRepository
//import dagger.hilt.android.lifecycle.HiltViewModel
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.SharingStarted
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.flow.asStateFlow
//import kotlinx.coroutines.flow.stateIn
//import kotlinx.coroutines.flow.update
//import kotlinx.coroutines.launch
//import java.text.SimpleDateFormat
//import java.util.*
//import javax.inject.Inject
//
//data class CategoryInfo(
//    val label: String,
//    val hint: String,
//    val fieldName: String
//)
//
//@HiltViewModel
//class SettlementModel @Inject constructor(
//    private val repository: SettlementRepository
//) : ViewModel() {
//
//    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
//
//    // ✅ 1. 에러의 원인: UI에서 구독할 히스토리 목록 추가
//    // repository.getAllSettlements()는 Flow<List<SettlementModel>>를 반환해야 합니다.
//    val allSettlements: StateFlow<List<SettlementModel>> = repository.getAllSettlements()
//        .stateIn(
//            scope = viewModelScope,
//            started = SharingStarted.WhileSubscribed(5000),
//            initialValue = emptyList()
//        )
//
//    val categoryList = listOf(
//        CategoryInfo("아침", "예: 사과 1개, 닭가슴살", "breakfast"),
//        CategoryInfo("점심", "예: 김치찌개, 현미밥 1공기", "lunch"),
//        CategoryInfo("저녁", "예: 샐러드, 스테이크", "dinner"),
//        CategoryInfo("간식", "예: 아메리카노, 견과류", "snack"),
//        CategoryInfo("운동", "예: 스쿼트 100개, 런닝 5km", "exercise"),
//        CategoryInfo("비고", "오늘의 특이사항 (회식, 데이트 등)", "noteInput")
//    )
//
//    private val _uiState = MutableStateFlow(SettlementModel(date = today))
//    val uiState = _uiState.asStateFlow()
//
//    var toastMessage by mutableStateOf<String?>(null)
//        private set
//
//    fun onToastShown() { toastMessage = null }
//
//    init {
//        loadTodayDraft()
//    }
//
//    // 오늘 날짜의 데이터를 Firestore 혹은 로컬에서 가져옴
//    private fun loadTodayDraft() = viewModelScope.launch {
//        repository.getSettlementByDate(today)?.let { savedDraft ->
//            _uiState.value = savedDraft
//        }
//    }
//
//    fun updateField(fieldName: String, text: String) {
//        _uiState.update { current ->
//            when (fieldName) {
//                "breakfast" -> current.copy(breakfast = text)
//                "lunch" -> current.copy(lunch = text)
//                "dinner" -> current.copy(dinner = text)
//                "snack" -> current.copy(snack = text)
//                "exercise" -> current.copy(exerciseInput = text)
//                "noteInput" -> current.copy(noteInput = text)
//                else -> current
//            }
//        }
//    }
//
//    // ✅ 2. 분석 로직을 Repository(GeminiManager)로 이관
//    fun analyzeWithGemini() {
//        viewModelScope.launch {
//            _uiState.update { it.copy(isAnalyzing = true) }
//            try {
//                // Repository 내부에서 GeminiManager를 통해 분석하고 결과를 받아옴
//                val analyzedData = repository.analyzeAndSave(_uiState.value)
//
//                _uiState.update { analyzedData }
//                toastMessage = "AI 분석 및 저장이 완료되었습니다."
//            } catch (e: Exception) {
//                _uiState.update { it.copy(isAnalyzing = false) }
//                toastMessage = "분석 중 오류 발생: ${e.message}"
//            }
//        }
//    }
//
//    fun saveTemporarily() = viewModelScope.launch {
//        try {
//            repository.insertOrUpdate(_uiState.value.copy(isFinalizing = false))
//            toastMessage = "임시 저장이 완료되었습니다."
//        } catch (e: Exception) {
//            toastMessage = "저장 실패: ${e.message}"
//        }
//    }
//}