package com.youngs.dailynet.ui.view

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.youngs.dailynet.R
import com.youngs.dailynet.util.MealPhoto
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import com.youngs.dailynet.util.HealthStepReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * "yyyy-MM-dd" 날짜를 [days]일만큼 옮긴다. 형식이 깨져 있으면 null.
 * 상세 화면에서 좌우 스와이프로 전날·다음날을 열 때 쓴다.
 */
private fun shiftDate(date: String, days: Int): String? {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val parsed = runCatching { fmt.parse(date) }.getOrNull() ?: return null
    val cal = Calendar.getInstance().apply {
        time = parsed
        add(Calendar.DAY_OF_MONTH, days)
    }
    return fmt.format(cal.time)
}

/** 사진으로 메뉴를 채울 수 있는 항목. 운동·비고는 사진으로 알아낼 수 없어 제외한다. */
private val MEAL_PHOTO_FIELDS = setOf("breakfast", "lunch", "dinner", "snack")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyRecordScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToWeightTrend: (String) -> Unit = {},
    isReadOnly: Boolean = false,
    /**
     * 좌우로 스와이프했을 때 열 날짜를 넘겨준다. 왼쪽으로 밀면 다음날, 오른쪽으로 밀면 전날.
     * null이면 스와이프를 받지 않는다 (오늘의 정산 입력 화면처럼 날짜가 고정된 경우).
     * 오늘보다 뒤의 날짜로는 넘어가지 않는다.
     */
    onSwipeDate: ((String) -> Unit)? = null,
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

    // ── 음식 사진으로 메뉴 채우기 ─────────────────────────────────────
    // 어느 항목에서 카메라를 열었는지 기억해 두고, 촬영이 끝나면 그 항목에 결과를 넣는다.
    val photoProcessingField by mainViewModel.photoProcessingField.collectAsState()
    var pendingPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingPhotoField by remember { mutableStateOf<String?>(null) }

    // CAMERA 권한을 매니페스트에 선언하지 않았으므로 런타임 권한 요청이 필요 없다.
    // (선언하면 그때부터 권한을 물어봐야 한다)
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingPhotoUri
        val field = pendingPhotoField
        if (success && uri != null && field != null) {
            mainViewModel.extractMealFromPhoto(uri, field)
        }
        pendingPhotoField = null
    }

    // 갤러리 선택. 사진 선택 도구는 권한이 필요 없고, 고른 사진 한 장만 앱에 넘어온다.
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val field = pendingPhotoField
        if (uri != null && field != null) {
            mainViewModel.extractMealFromPhoto(uri, field)
        }
        pendingPhotoField = null
    }

    // 📷을 누르면 카메라/갤러리 중 무엇으로 가져올지 먼저 묻는다
    var photoSourceField by remember { mutableStateOf<String?>(null) }

    photoSourceField?.let { field ->
        AlertDialog(
            onDismissRequest = { photoSourceField = null },
            title = { Text(stringResource(R.string.photo_source_title)) },
            text = { Text(stringResource(R.string.photo_source_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    photoSourceField = null
                    pendingPhotoField = field
                    val uri = MealPhoto.createTempImageUri(context)
                    pendingPhotoUri = uri
                    cameraLauncher.launch(uri)
                }) { Text(stringResource(R.string.photo_source_camera)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    photoSourceField = null
                    pendingPhotoField = field
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) { Text(stringResource(R.string.photo_source_gallery)) }
            }
        )
    }

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
                Toast.makeText(
                    context,
                    context.getString(R.string.steps_health_connect_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
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
                Toast.makeText(
                    context,
                    context.getString(R.string.steps_load_failed),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                mainViewModel.applyAutoSteps(targetDate, steps.toInt(), force = true)
                Toast.makeText(
                    context,
                    context.getString(R.string.steps_reloaded, steps),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    BackHandler(enabled = true) {
        if (uiState.analyzing) {
            // 분석 중 이탈하면 결과를 못 보므로 붙잡아 둔다
            Toast.makeText(
                                    context,
                                    context.getString(R.string.analyzing_wait),
                                    Toast.LENGTH_SHORT
                                ).show()
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

    // ── 좌우 스와이프로 전날·다음날 이동 ──
    // 손가락을 따라 본문이 옆으로 밀리고, 폭의 1/4 넘게 밀면 그 방향의 날짜로 넘어간다.
    // 넘어갈 때는 본문이 화면 밖으로 빠졌다가 반대쪽에서 새 날짜가 들어오는 것처럼 보이게 한다.
    val swipeOffset = remember { Animatable(0f) }
    var contentWidth by remember { mutableStateOf(0) }
    val swipeEnabled = onSwipeDate != null && !uiState.analyzing && uiState.date.isNotEmpty()

    fun finishSwipe() {
        val width = contentWidth.toFloat()
        val offset = swipeOffset.value
        val threshold = width / 4f
        // 왼쪽으로 밀면(offset < 0) 다음날, 오른쪽으로 밀면 전날
        val step = when {
            offset < -threshold -> 1
            offset > threshold -> -1
            else -> 0
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val target = if (step == 0) null else shiftDate(uiState.date, step)
        // 오늘 이후로는 기록이 없으므로 넘어가지 않고 제자리로 돌려놓는다
        if (target == null || target > today || onSwipeDate == null) {
            scope.launch { swipeOffset.animateTo(0f, tween(250)) }
            return
        }
        scope.launch {
            // 현재 본문을 민 방향으로 마저 내보낸다
            swipeOffset.animateTo(-step * width, tween(180))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSwipeDate(target)
            // 새 날짜 본문은 반대쪽에서 들어온다
            swipeOffset.snapTo(step * width)
            swipeOffset.animateTo(0f, tween(250))
        }
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
                                    text = stringResource(
                                        R.string.net_calories_value,
                                        uiState.netCalories
                                    ),
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
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.analyzing_wait),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                mainViewModel.clearTodayDraft()
                                onBack()
                            }
                        }) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    },
                    actions = {
                        // 이 날짜를 기준으로 몸무게 추이 그래프를 연다
                        IconButton(onClick = { onNavigateToWeightTrend(uiState.date) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_weight_chart),
                                contentDescription = stringResource(R.string.weight_trend_title)
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
                    .onSizeChanged { contentWidth = it.width }
                    // 가로 드래그만 받는다. 세로 스크롤은 아래 verticalScroll이 그대로 가져간다.
                    .pointerInput(swipeEnabled) {
                        if (!swipeEnabled) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragEnd = { finishSwipe() },
                            onDragCancel = { scope.launch { swipeOffset.animateTo(0f, tween(250)) } }
                        ) { change, dragAmount ->
                            change.consume()
                            scope.launch { swipeOffset.snapTo(swipeOffset.value + dragAmount) }
                        }
                    }
                    .graphicsLayer {
                        translationX = swipeOffset.value
                        // 멀리 밀수록 옅어져서 "넘어간다"는 느낌을 준다
                        val w = contentWidth.toFloat().coerceAtLeast(1f)
                        alpha = 1f - (abs(swipeOffset.value) / w) * 0.6f
                    }
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
                    label = { Text(stringResource(R.string.weight_label)) },
                    placeholder = { Text(stringResource(R.string.weight_placeholder)) },
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
                        label = { Text(stringResource(category.labelRes)) },
                        placeholder = { Text(stringResource(category.hintRes)) },
                        // 식사 항목에만 카메라를 붙인다. 운동·비고는 사진으로 알아낼 수 없다.
                        trailingIcon = {
                            if (!isReadOnly && category.fieldName in MEAL_PHOTO_FIELDS) {
                                if (photoProcessingField == category.fieldName) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    IconButton(onClick = {
                                        photoSourceField = category.fieldName
                                    }) {
                                        Text("📷")
                                    }
                                }
                            }
                        },
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
                            label = { Text(stringResource(R.string.steps_label)) },
                            placeholder = { Text(stringResource(R.string.steps_placeholder)) },
                            supportingText = {
                                Text(
                                    stringResource(
                                        if (isToday) R.string.steps_support_today
                                        else R.string.steps_support_past
                                    )
                                )
                            },
                            trailingIcon = {
                                if (!isReadOnly) {
                                    IconButton(onClick = { refreshSteps() }) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = stringResource(R.string.cd_refresh_steps)
                                        )
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
                                stringResource(
                                    if (uiState.analysisResult.isNotEmpty()) R.string.analyze_again
                                    else R.string.analyze_today
                                ),
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
                                        text = stringResource(R.string.ai_report_title),
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
            text = stringResource(R.string.badge_just_done),
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
        stringResource(R.string.analyzing_step_1),
        stringResource(R.string.analyzing_step_2),
        stringResource(R.string.analyzing_step_3),
        stringResource(R.string.analyzing_step_4)
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
                    text = stringResource(R.string.analyzing_title),
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
                    text = stringResource(R.string.analyzing_elapsed, elapsed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.analyzing_auto_move),
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
        text = stringResource(R.string.analyzing_inline) + dots,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
}
