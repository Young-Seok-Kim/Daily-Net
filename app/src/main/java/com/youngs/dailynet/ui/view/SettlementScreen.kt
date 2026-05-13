package com.youngs.dailynet.ui.view

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettlementScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val uiState by mainViewModel.uiState.collectAsState()
    val context = LocalContext.current

    // ✨ 토스트 메시지 처리 및 자동 화면 닫기 로직
    LaunchedEffect(mainViewModel.toastMessage) {
        mainViewModel.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            mainViewModel.onToastShown()

            // 분석 및 저장이 완료되었다면 목록으로 복귀
            if (message.contains("완료")) {
                onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("오늘의 정산") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로가기")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 🏋️ 체중 입력 필드 (숫자 키패드 적용)
            OutlinedTextField(
                value = if (uiState.currentWeight == 0f) "" else uiState.currentWeight.toString(),
                onValueChange = { mainViewModel.updateField("currentWeight", it) },
                label = { Text("현재 체중 (kg)") },
                placeholder = { Text("예: 75.5") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 식단 및 운동 입력 필드 루프
            mainViewModel.categoryList.forEach { category ->
                OutlinedTextField(
                    value = when (category.fieldName) {
                        "breakfast" -> uiState.breakfast
                        "lunch" -> uiState.lunch
                        "dinner" -> uiState.dinner
                        "snack" -> uiState.snack
                        "exercise" -> uiState.exercise
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
                OutlinedButton(
                    onClick = { mainViewModel.saveTemporarily() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("임시 저장")
                }

                Button(
                    onClick = { mainViewModel.analyzeAndFinalize() }, // 👈 함수명 수정 완료
                    modifier = Modifier.weight(1f),
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

            // AI 분석 결과 카드
            if (uiState.analysisResult.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}