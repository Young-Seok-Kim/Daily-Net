package com.youngs.dailynet.ui.view

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.ui.viewmodel.MainViewModel

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by mainViewModel.uiState.collectAsState()
    val context = LocalContext.current

    // ✨ ViewModel의 toastMessage 상태를 관찰하여 토스트를 띄움
    // MainViewModel에 toastMessage(String?)와 onToastShown()이 정의되어 있어야 합니다.
    LaunchedEffect(mainViewModel.toastMessage) {
        mainViewModel.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            mainViewModel.onToastShown()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()) // 중첩 스크롤 해결: 여기서 한 번만 사용
            .padding(16.dp)
    ) {
        Text(
            text = "오늘의 정산 입력",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 입력 필드 루프
        mainViewModel.categoryList.forEach { category ->
            OutlinedTextField(
                value = when (category.fieldName) {
                    "breakfast" -> uiState.breakfast
                    "lunch" -> uiState.lunch
                    "dinner" -> uiState.dinner
                    "snack" -> uiState.snack
                    "exerciseInput" -> uiState.exerciseInput
                    "noteInput" -> uiState.noteInput
                    else -> ""
                },
                onValueChange = { mainViewModel.updateField(category.fieldName, it) },
                label = { Text(category.label) },
                placeholder = { Text(category.hint) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                minLines = if (category.fieldName == "noteInput") 3 else 1
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 버튼 영역
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 💾 임시 저장 버튼
            OutlinedButton(
                onClick = { mainViewModel.saveTemporarily() },
                modifier = Modifier.weight(1f)
            ) {
                Text("임시 저장")
            }

            // 🚀 제미나이 분석 버튼
            Button(
                onClick = { mainViewModel.analyzeWithGemini() },
                modifier = Modifier.weight(1f),
                // 분석 중일 때는 버튼을 비활성화하여 중복 클릭 방지
                enabled = !uiState.isAnalyzing
            ) {
                if (uiState.isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("제미나이 분석")
                }
            }
        }

        // ✨ 제미나이 분석 결과 표시 영역 (결과가 있을 때만 노출)
        if (uiState.analysisResult.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🤖 AI 분석 레포트",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.analysisResult,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 결과 하단 여백 추가 (스크롤 끝까지 되도록)
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}