package com.youngs.dailynet.ui.view

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.compose.ui.res.painterResource
import com.youngs.dailynet.R
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import com.youngs.dailynet.util.HealthStepReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyRecordScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToWeightTrend: (String) -> Unit = {},
    isReadOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    val uiState by mainViewModel.uiState.collectAsState()
    val shouldStream by mainViewModel.shouldStreamResult.collectAsState()
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val healthReader = remember { HealthStepReader(context) }

    // 권한 결과 후 재실행 트리거
    var stepRetryKey by remember { mutableStateOf(0) }
    // 이 날짜에 대해 걸음수를 이미 반영했는지 (날짜 바뀌면 리셋)
    var stepsApplied by remember(uiState.date) { mutableStateOf(false) }

    // Health Connect 권한 런처
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { _ -> stepRetryKey++ }

    // 오늘 날짜의 정산 화면 → Health Connect에서 오늘 총 걸음 수 자동 반영
    LaunchedEffect(uiState.date, stepRetryKey) {
        if (isReadOnly) return@LaunchedEffect
        if (uiState.date != mainViewModel.todayDate) return@LaunchedEffect
        if (stepsApplied) return@LaunchedEffect
        if (!healthReader.sdkAvailable()) return@LaunchedEffect

        if (healthReader.hasPermission()) {
            val hcSteps = healthReader.getTodaySteps()
            if (hcSteps != null) {
                mainViewModel.applyAutoSteps(hcSteps.toInt())
                stepsApplied = true
            }
        } else {
            healthPermissionLauncher.launch(healthReader.permissions)
        }
    }

    BackHandler(enabled = true) {
        if (!uiState.analyzing) {
            mainViewModel.clearTodayDraft()
        }
        onBack()
    }

    var weightInput by remember { mutableStateOf("") }

    // 걸음수 입력창 표시값 (0이면 빈칸)
    val stepsText = if (uiState.steps > 0) uiState.steps.toString() else ""

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

    // ── 분석 결과: 스트리밍 없이 한 번에 전체 표시 ──
    val streamedResult = uiState.analysisResult
    val isStreaming = false
    LaunchedEffect(uiState.analysisResult, shouldStream) {
        // 방금 분석한 결과 플래그만 초기화 (표시는 즉시 전체)
        if (shouldStream) mainViewModel.onResultStreamed()
    }

    Box(modifier = Modifier.fillMaxSize()) {

        LaunchedEffect(mainViewModel.toastMessage) {
            mainViewModel.toastMessage?.let { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                mainViewModel.onToastShown()
                // 💡 분석 결과 스트리밍을 볼 수 있도록 자동으로 뒤로가지 않는다.
                //    (기록은 이미 저장되어 있으며, 사용자가 결과를 읽은 뒤 직접 뒤로가기)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("${uiState.date} ${getDayOfWeekText(uiState.date)}") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!uiState.analyzing) mainViewModel.clearTodayDraft()
                            onBack()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "뒤로가기")
                        }
                    },
                    actions = {
                        // 이 날짜를 기준으로 몸무게 추이 그래프를 연다
                        IconButton(onClick = { onNavigateToWeightTrend(uiState.date) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_weight_chart),
                                contentDescription = "몸무게 추이"
                            )
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
                    value = weightInput,
                    onValueChange = { text ->
                        if (text.isEmpty() || text.matches(Regex("""^\d*\.?\d*$"""))) {
                            weightInput = text
                            mainViewModel.updateField("weight", text)
                        }
                    },
                    label = { Text("현재 체중 (kg)") },
                    placeholder = { Text("예: 75.5") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
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
                        shape = RoundedCornerShape(12.dp),
                        minLines = if (category.fieldName == "remark") 3 else 1,
                        enabled = !isReadOnly
                    )

                    // 👣 걸음수 필드는 '운동' 바로 아래에 배치 (오늘이면 자동 기입, 직접 수정도 가능)
                    if (category.fieldName == "exercise") {
                        OutlinedTextField(
                            value = stepsText,
                            onValueChange = { text ->
                                if (text.isEmpty() || text.all { it.isDigit() }) {
                                    mainViewModel.updateField("steps", text)
                                }
                            },
                            label = { Text("걸음수 👣") },
                            placeholder = { Text("오늘 날짜면 자동으로 채워져요") },
                            supportingText = {
                                if (uiState.date == mainViewModel.todayDate)
                                    Text("오늘 걸음 수가 자동 반영됩니다")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !isReadOnly
                        )
                    }
                }

                // 💡 읽기 전용 모드에서는 분석 버튼 숨기기
                if (!isReadOnly) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            activity?.let { mainViewModel.checkAndAnalyze(it) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !uiState.analyzing
                    ) {
                        if (uiState.analyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            AnimatedAnalyzingText()
                        } else {
                            Text(
                                "오늘의 정산 분석하기",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // AI 분석 결과 카드 (상세 보기에서도 표시)
                if (uiState.analysisResult.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "🤖 AI 분석 레포트",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (isStreaming) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "분석 결과 작성 중",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            )
                            // 타자기처럼 한 줄씩 나타나는 본문 + 커서
                            Text(
                                text = streamedResult + if (isStreaming) " ▍" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 22.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

/** "AI가 분석 중" + 점(...)이 순차적으로 깜빡이는 텍스트 */
@Composable
private fun AnimatedAnalyzingText() {
    val transition = rememberInfiniteTransition(label = "analyzing")
    val dotCount by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3.99f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots"
    )
    val dots = ".".repeat(dotCount.toInt())
    Text(
        text = "AI가 분석 중이에요$dots",
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
}
