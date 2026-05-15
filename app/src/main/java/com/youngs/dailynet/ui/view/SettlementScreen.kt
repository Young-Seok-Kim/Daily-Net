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
    isReadOnly: Boolean = false, // 💡 1. 읽기 전용 모드 파라미터 추가
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val uiState by mainViewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(mainViewModel.toastMessage) {
        mainViewModel.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            mainViewModel.onToastShown()

            if (message.contains("완료")) {
                onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // 💡 2. 제목 동적 변경
                title = { Text(if (isReadOnly) "${uiState.date} 정산 상세" else "오늘의 정산") },
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
            OutlinedTextField(
                value = if (uiState.currentWeight == 0f) "" else uiState.currentWeight.toString(),
                onValueChange = { mainViewModel.updateField("currentWeight", it) },
                label = { Text("현재 체중 (kg)") },
                placeholder = { Text("예: 75.5") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !isReadOnly // 💡 3. 읽기 전용일 때 비활성화
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            mainViewModel.categoryList.forEach { category ->
                OutlinedTextField(
                    value = when (category.fieldName) {
                        "breakfast" -> uiState.breakfast
                        "lunch" -> uiState.lunch
                        "dinner" -> uiState.dinner
                        "snack" -> uiState.snack
                        "exercise" -> uiState.exercise
                        "remark" -> uiState.remark
                        else -> ""
                    },
                    onValueChange = { mainViewModel.updateField(category.fieldName, it) },
                    label = { Text(category.label) },
                    placeholder = { Text(category.hint) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    minLines = if (category.fieldName == "remark") 3 else 1,
                    enabled = !isReadOnly // 💡 4. 읽기 전용일 때 비활성화
                )
            }

            // 💡 5. 읽기 전용 모드에서는 분석 버튼 숨기기
            if (!isReadOnly) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { mainViewModel.analyzeAndFinalize() },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.analyzing
                    ) {
                        if (uiState.analyzing) {
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
            }

            // AI 분석 결과 카드 (이건 상세 보기에서도 보여야 하므로 유지)
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