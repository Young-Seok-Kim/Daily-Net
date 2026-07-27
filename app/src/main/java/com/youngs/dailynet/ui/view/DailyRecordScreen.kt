package com.youngs.dailynet.ui.view

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.compose.ui.res.painterResource
import com.youngs.dailynet.R
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import com.youngs.dailynet.util.HealthStepReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val justFinishedAnalysis by mainViewModel.shouldStreamResult.collectAsState()
    val recordLoadToken by mainViewModel.recordLoadToken.collectAsState()
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val healthReader = remember { HealthStepReader(context) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // 권한 결과 후 재실행 트리거
    var stepRetryKey by remember { mutableStateOf(0) }
    // 권한 팝업은 화면당 한 번만 (토큰 변화로 이펙트가 재실행돼도 중복으로 뜨지 않게)
    var permissionRequested by remember { mutableStateOf(false) }
    // 과거 데이터 읽기 권한도 화면당 한 번만 요청 (거부하면 그냥 읽어보고 넘어간다)
    var historyRequested by remember { mutableStateOf(false) }

    // Health Connect 권한 런처
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { _ -> stepRetryKey++ }

    // 화면에 열려 있는 날짜의 걸음 수를 Health Connect에서 읽어 반영한다.
    // 오늘이면 최신값으로 갱신, 과거 날짜면 비어 있을 때만 채운다 (판단은 ViewModel이 함)
    //
    // 💡 recordLoadToken을 key에 넣는 이유:
    //    prepareDailyRecordData()가 uiState를 통째로 갈아끼우기 때문에, 그보다 먼저 걸음수를
    //    읽어 넣으면 0으로 덮여버린다. 로드가 끝난 뒤 한 번 더 읽어서 반영한다.
    //    (이전에는 "오늘 저장된 기록이 있을 때만" 걸음수가 보이던 원인)
    LaunchedEffect(uiState.date, stepRetryKey, recordLoadToken) {
        if (isReadOnly) return@LaunchedEffect
        if (uiState.date.isEmpty()) return@LaunchedEffect
        if (!healthReader.sdkAvailable()) return@LaunchedEffect

        val targetDate = uiState.date

        if (!healthReader.hasPermission()) {
            if (!permissionRequested) {
                permissionRequested = true
                // 기기가 지원하면 과거 데이터 읽기 권한까지 한 번에 요청한다
                healthPermissionLauncher.launch(healthReader.permissionsToRequest())
            }
            return@LaunchedEffect
        }

        // 30일보다 오래된 날짜는 과거 데이터 읽기 권한이 있어야 조회된다.
        // 아직 안 물어봤다면 요청하고, 결과가 오면 stepRetryKey가 바뀌어 이 이펙트가 다시 돈다.
        // (거부당한 경우엔 historyRequested가 true라 그냥 읽어보고 넘어간다)
        if (!historyRequested &&
            healthReader.isBeyondDefaultWindow(targetDate) &&
            healthReader.historySupported() &&
            !healthReader.hasHistoryPermission()
        ) {
            historyRequested = true
            healthPermissionLauncher.launch(setOf(healthReader.historyPermission))
            return@LaunchedEffect
        }

        healthReader.getStepsForDate(targetDate)?.let {
            mainViewModel.applyAutoSteps(targetDate, it.toInt())
        }
    }

    /** 걸음수 새로고침 버튼: 권한이 없으면 요청하고, 있으면 그날 값으로 강제 갱신 */
    fun refreshSteps() {
        val targetDate = uiState.date
        scope.launch {
            if (!healthReader.sdkAvailable()) {
                Toast.makeText(context, "이 기기에서는 Health Connect를 쓸 수 없어요", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!healthReader.hasPermission()) {
                healthPermissionLauncher.launch(healthReader.permissionsToRequest())
                return@launch
            }
            // 오래된 날짜인데 과거 데이터 권한이 없으면 먼저 요청한다 (버튼을 눌렀으니 다시 물어봐도 된다)
            if (healthReader.isBeyondDefaultWindow(targetDate) &&
                healthReader.historySupported() &&
                !healthReader.hasHistoryPermission()
            ) {
                healthPermissionLauncher.launch(setOf(healthReader.historyPermission))
                return@launch
            }
            val steps = healthReader.getStepsForDate(targetDate)
            if (steps == null) {
                Toast.makeText(context, "걸음 수를 불러오지 못했어요", Toast.LENGTH_SHORT).show()
            } else {
                mainViewModel.applyAutoSteps(targetDate, steps.toInt(), force = true)
                Toast.makeText(context, "걸음 수를 새로 불러왔어요 (${steps}보)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = true) {
        if (uiState.analyzing) {
            // 분석 중 이탈하면 결과를 못 보므로 붙잡아 둔다
            Toast.makeText(context, "분석 중이에요. 잠시만 기다려주세요", Toast.LENGTH_SHORT).show()
        } else {
            mainViewModel.clearTodayDraft()
            onBack()
        }
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

    // ── 그날의 칼로리 요약 ──
    // 순칼로리는 지금까지 AI 레포트 '본문 글' 안에만 있어서 한눈에 안 들어왔다.
    // 숫자만 따로 뽑아 상단바와 결과 카드 맨 위에 크게 보여준다.
    val hasCalorieResult = uiState.finalized || uiState.analysisResult.isNotEmpty()
    val calorieColor = if (uiState.netCalories <= 0) Color(0xFF4CAF50) else Color(0xFFF44336)

    // ── 분석 완료 연출 ──
    val scrollState = rememberScrollState()
    var resultCardY by remember { mutableStateOf(0) }
    var showJustDoneBadge by remember { mutableStateOf(false) }

    LaunchedEffect(justFinishedAnalysis) {
        if (!justFinishedAnalysis) return@LaunchedEffect
        // 1) 진동으로 "끝났다"를 몸으로 알림
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        // 2) 결과 카드에 "방금 완료" 배지 표시
        showJustDoneBadge = true
        // 3) 카드 등장 애니메이션(500ms)이 끝나야 위치와 스크롤 범위가 확정된다.
        //    그 전에 스크롤하면 maxValue가 아직 작아서 도중에 멈춘다.
        delay(600)
        // 위치를 못 쟀으면 맨 아래로 (결과 카드가 화면 마지막 요소라 어차피 보인다)
        scrollState.animateScrollTo(if (resultCardY > 0) resultCardY else scrollState.maxValue)
        delay(6000)
        showJustDoneBadge = false
        mainViewModel.onResultStreamed()
    }

    Box(modifier = Modifier.fillMaxSize()) {

        LaunchedEffect(mainViewModel.toastMessage) {
            mainViewModel.toastMessage?.let { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                mainViewModel.onToastShown()
                // 💡 분석 결과를 볼 수 있도록 자동으로 뒤로가지 않는다.
                //    (기록은 이미 저장되어 있으며, 사용자가 결과를 읽은 뒤 직접 뒤로가기)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("${uiState.date} ${getDayOfWeekText(uiState.date)}")
                            // 💡 스크롤 위치와 상관없이 그날의 순칼로리가 항상 보이도록 상단바에 함께 표시
                            if (hasCalorieResult) {
                                Text(
                                    text = "순칼로리 ${uiState.netCalories} kcal",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = calorieColor
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (uiState.analyzing) {
                                Toast.makeText(context, "분석 중이에요. 잠시만 기다려주세요", Toast.LENGTH_SHORT).show()
                            } else {
                                mainViewModel.clearTodayDraft()
                                onBack()
                            }
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
                    .verticalScroll(scrollState)
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
                        val isToday = uiState.date == mainViewModel.todayDate
                        OutlinedTextField(
                            value = stepsText,
                            onValueChange = { text ->
                                if (text.isEmpty() || text.all { it.isDigit() }) {
                                    mainViewModel.updateField("steps", text)
                                }
                            },
                            label = { Text("걸음수 👣") },
                            placeholder = { Text("자동으로 채워져요") },
                            supportingText = {
                                Text(
                                    if (isToday) "오늘 걸음 수가 자동 반영됩니다 · 안 보이면 새로고침을 눌러주세요"
                                    else "비어 있으면 그날 걸음 수가 자동으로 채워집니다"
                                )
                            },
                            trailingIcon = {
                                if (!isReadOnly) {
                                    IconButton(onClick = { refreshSteps() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "걸음수 새로고침")
                                    }
                                }
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
                                if (uiState.analysisResult.isNotEmpty()) "다시 분석하기" else "오늘의 정산 분석하기",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // AI 분석 결과 카드 (상세 보기에서도 표시)
                AnimatedVisibility(
                    visible = uiState.analysisResult.isNotEmpty(),
                    enter = fadeIn(animationSpec = tween(500)) +
                            expandVertically(animationSpec = tween(500)),
                    // 💡 스크롤 대상 위치는 여기서 재야 한다.
                    //    AnimatedVisibility 안쪽에 붙이면 positionInParent()가
                    //    스크롤 Column이 아니라 AnimatedVisibility 내부 기준(≈0)이 되어
                    //    자동 스크롤이 맨 위로 가버린다.
                    modifier = Modifier.onGloballyPositioned {
                        resultCardY = it.positionInParent().y.toInt()
                    }
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(24.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (showJustDoneBadge) 8.dp else 3.dp
                            ),
                            // 방금 분석이 끝난 결과는 테두리로 눈에 띄게 강조
                            border = if (showJustDoneBadge) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else null
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "🤖 AI 분석 레포트",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (showJustDoneBadge) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        JustCompletedBadge()
                                    }
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                )
                                Text(
                                    text = uiState.analysisResult,
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

        // 분석 중에는 화면 전체를 덮는 오버레이로 진행 상황을 확실히 보여준다
        if (uiState.analyzing) {
            AnalyzingOverlay()
        }
    }
}

/** "✅ 방금 완료!" 배지 — 결과가 새로 도착했음을 알린다 */
@Composable
private fun JustCompletedBadge() {
    val transition = rememberInfiniteTransition(label = "badge")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "badgeAlpha"
    )
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    ) {
        Text(
            text = "✅ 방금 완료",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 분석 중 전체 화면 오버레이.
 * 버튼 안의 작은 스피너만으로는 진행 여부를 알기 어려워서,
 * 경과 시간 + 단계별 문구로 "지금 뭘 하고 있는지"를 보여준다.
 */
@Composable
private fun AnalyzingOverlay() {
    val phases = listOf(
        "식단 내용을 읽고 있어요",
        "칼로리를 계산하고 있어요",
        "운동과 걸음 수를 반영하고 있어요",
        "맞춤 피드백을 정리하고 있어요"
    )

    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsed++
        }
    }
    val phase = phases[(elapsed / 4).coerceAtMost(phases.lastIndex)]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // 오버레이가 떠 있는 동안 뒤쪽 입력창이 눌리지 않도록 터치를 모두 소비한다
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(52.dp),
                    strokeWidth = 4.dp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "🤖 AI가 분석 중이에요",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Crossfade(targetState = phase, label = "phase") { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "${elapsed}초 경과 · 보통 10~30초 걸려요",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "끝나면 결과 카드로 자동 이동해요",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
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
