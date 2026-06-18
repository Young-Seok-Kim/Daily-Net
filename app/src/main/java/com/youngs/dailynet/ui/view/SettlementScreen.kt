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
import com.youngs.dailynet.ui.viewmodel.BaseViewModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettlementScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    isReadOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = true) {
        mainViewModel.clearTodayDraft()
        onBack()
    }

    val uiState by mainViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    BackHandler(enabled = true) {
        if (!uiState.analyzing) {
            mainViewModel.clearTodayDraft()
        }
        onBack()
    }

    var weightInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState.weight) {
        // 사용자가 타이핑 중인 게 아니고, DB 등에서 가져온 값이 0이 아닐 때만 텍스트 입력창 초기화
        if (uiState.weight > 0f) {
            weightInput = if (uiState.weight % 1f == 0f) {
                uiState.weight.toInt().toString()
            } else {
                uiState.weight.toString()
            }
        } else if (uiState.weight == 0f) {
            weightInput = ""
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        LaunchedEffect(mainViewModel.toastMessage) {
            mainViewModel.toastMessage?.let { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                mainViewModel.onToastShown()

                if (message.contains("완료")) {
                    mainViewModel.clearTodayDraft()
                    onBack()
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
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
                        value = weightInput, // 👈 임시 변수를 꽂아 유저가 타이핑한 흐름을 그대로 유지시킵니다.
                        onValueChange = { text ->
                            // 숫자와 소수점 하나만 입력 가능하도록 필터링 (잘못된 입력 방지)
                            if (text.isEmpty() || text.matches(Regex("""^\d*\.?\d*$"""))) {
                                weightInput = text
                                mainViewModel.updateField("weight", text) // ViewModel에는 실시간 파싱 전달
                            }
                        },
                        label = { Text("현재 체중 (kg)") },
                        placeholder = { Text("예: 75.5") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = !isReadOnly
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
                        Button(
                            onClick = {

                                activity?.let {
                                    // 👑 통합 정책 함수 호출
                                    mainViewModel.checkAndAnalyze(it)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            enabled = !uiState.analyzing // 분석 중엔 버튼 클릭 방지
                        ) {
                            // 분석 중일 때 로딩바 + 텍스트, 아니면 텍스트만 표시
                            if (uiState.analyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("AI 분석 중...")
                            } else {
                                Text("오늘의 정산 분석하기")
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
    }
}