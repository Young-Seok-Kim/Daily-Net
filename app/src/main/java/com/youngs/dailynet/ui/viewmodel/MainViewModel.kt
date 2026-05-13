package com.youngs.dailynet.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.data.dao.SettlementDao
import com.youngs.dailynet.data.model.SettlementModel
import com.youngs.dailynet.data.repository.SettlementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val settlementDao: SettlementDao,
    private val repository: SettlementRepository
) : ViewModel() {

    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    // 🚀 Gemini 모델 설정 (BuildConfig에서 API 키 참조)
    private val generativeModel = GenerativeModel(
        modelName = Constants.MODEL_NAME,
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    val categoryList = listOf(
        CategoryInfo("아침", "예: 사과 1개, 닭가슴살", "breakfast"),
        CategoryInfo("점심", "예: 김치찌개, 현미밥 1공기", "lunch"),
        CategoryInfo("저녁", "예: 샐러드, 스테이크", "dinner"),
        CategoryInfo("간식", "예: 아메리카노, 견과류", "snack"),
        CategoryInfo("운동", "예: 스쿼트 100개, 런닝 5km", "exerciseInput"),
        CategoryInfo("비고", "오늘의 특이사항 (회식, 데이트 등)", "noteInput")
    )

    // UI에 필요한 부가 상태값들
    private val _uiState = MutableStateFlow(SettlementModel(date = today))
    val uiState = _uiState.asStateFlow()

    // ✨ UI 알림을 위한 상태
    var toastMessage by mutableStateOf<String?>(null)
        private set

    fun onToastShown() { toastMessage = null }

    init {
        loadTodayDraft()
    }

    private fun loadTodayDraft() = viewModelScope.launch {
        settlementDao.getSettlementByDate(today)?.let { savedDraft ->
            _uiState.value = savedDraft
        }
    }

    fun updateField(fieldName: String, text: String) {
        _uiState.update { current ->
            when (fieldName) {
                "breakfast" -> current.copy(breakfast = text)
                "lunch" -> current.copy(lunch = text)
                "dinner" -> current.copy(dinner = text)
                "snack" -> current.copy(snack = text)
                "exerciseInput" -> current.copy(exerciseInput = text)
                "noteInput" -> current.copy(noteInput = text)
                else -> current
            }
        }
    }

    fun saveTemporarily() = viewModelScope.launch {
        try {
            val draft = _uiState.value.copy(isFinalized = false)
            settlementDao.insertOrUpdate(draft)
            toastMessage = "임시 저장이 완료되었습니다."
        } catch (e: Exception) {
            toastMessage = "저장 실패: ${e.message}"
        }
    }

    fun analyzeWithGemini() {
        val current = _uiState.value

        // 분석할 텍스트 조합
        val prompt = categoryList.joinToString("\n") { info ->
            val value = when (info.fieldName) {
                "breakfast" -> current.breakfast
                "lunch" -> current.lunch
                "dinner" -> current.dinner
                "snack" -> current.snack
                "exerciseInput" -> current.exerciseInput
                "noteInput" -> current.noteInput
                else -> ""
            }
            "${info.label}: $value"
        } + "\n\n위 식단과 운동 내역을 바탕으로 영양 성분 평가와 조언을 짧고 친절하게 해줘."

        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true) }
            try {
                val response = generativeModel.generateContent(prompt)
                val resultText = response.text ?: "분석 결과를 가져올 수 없습니다."

                _uiState.update { it.copy(
                    analysisResult = resultText,
                    isAnalyzing = false
                ) }
                toastMessage = "AI 분석이 완료되었습니다."
            } catch (e: Exception) {
                _uiState.update { it.copy(isAnalyzing = false) }
                toastMessage = "분석 중 오류 발생: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    fun finalizeSettlement() = viewModelScope.launch {
        val finalizedData = _uiState.value.copy(
            date = today,
            isFinalized = true
        )
        try {
            settlementDao.insertOrUpdate(finalizedData)
            repository.saveSettlement(finalizedData)
            toastMessage = "오늘의 정산이 완료되었습니다."
        } catch (e: Exception) {
            toastMessage = "완료 처리 실패: ${e.message}"
        }
    }
}