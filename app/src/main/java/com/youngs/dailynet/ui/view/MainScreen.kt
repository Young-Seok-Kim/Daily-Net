package com.youngs.dailynet.ui.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import androidx.core.content.edit
import com.youngs.dailynet.util.Constants
import com.youngs.dailynet.util.HolidayProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun getWeekIdentifier(dateString: String): Int {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateString) ?: return 0
    val calendar = Calendar.getInstance().apply {
        time = date
        firstDayOfWeek = Calendar.MONDAY // 월요일 시작
        minimalDaysInFirstWeek = 4
    }
    // 일요일이 한 주의 끝이 되도록 계산 (해당 날짜가 속한 연도 + 주차 조합)
    return calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.WEEK_OF_YEAR)
}

// 1. 월별 주차를 계산하는 헬퍼 함수 추가
fun getWeekOfMonthText(dateString: String): String {
    return try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateString)
            ?: return dateString
        val calendar = Calendar.getInstance().apply {
            time = date
            firstDayOfWeek = Calendar.MONDAY // 월요일 시작 기준
        }
        val month = calendar.get(Calendar.MONTH) + 1 // 0부터 시작하므로 +1
        val week = calendar.get(Calendar.WEEK_OF_MONTH)

        // "05월 3주차" 형식으로 반환
        String.format(Locale.getDefault(), "%02d월 %d주차", month, week)
    } catch (e: Exception) {
        dateString // 에러 시 기본 날짜 반환
    }
}

fun getDayOfWeekText(dateString: String): String {
    return try {
        val date =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateString) ?: return ""
        // EEE 포맷은 기기 언어 설정에 따라 "월", "화" 혹은 "Mon", "Tue" 형태로 깔끔하게 반환됩니다.
        val dayOfWeek = SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        "($dayOfWeek)"
    } catch (e: Exception) {
        ""
    }
}

/**
 * 날짜별 표시 색상: 일요일·공휴일=빨강, 토요일=파랑, 평일=null(기본색).
 * 공휴일은 기기 캘린더(구글 '대한민국의 휴일' 등)에서 읽어온 날짜 집합을 사용한다.
 */
fun weekdayColor(dateString: String, calendarHolidays: Set<String>): Color? {
    return try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateString)
            ?: return null
        val cal = Calendar.getInstance().apply { time = date }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        when {
            dow == Calendar.SUNDAY || calendarHolidays.contains(dateString) -> Color(0xFFF44336)
            dow == Calendar.SATURDAY -> Color(0xFF2196F3)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun SystemUiController(showLoading: Boolean) {
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window ?: return

    // 💡 WindowInsetsController를 통해 Immersive 모드 설정
    val controller = WindowCompat.getInsetsController(window, view)

    LaunchedEffect(showLoading) {
        if (showLoading) {
            // 시스템 바를 완전 숨김
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // 스와이프해도 안 나오게 설정 (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE는 스와이프 시 나타남)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // 원래대로 복구
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onNavigateToInput: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    var loadingMessage by remember { mutableStateOf<String?>(null) }
    val dailyRecords by mainViewModel.allDailyRecord.collectAsState()
    val totalCalories by mainViewModel.totalNetCalories.collectAsState()
    val weeklyCaloriesMap by mainViewModel.weeklyCaloriesMap.collectAsState()
    var showLogoutLoading by remember { mutableStateOf(false) }
    var selectedDateToDelete by remember { mutableStateOf<String?>(null) }
    var showWithdrawConfirmDialog by remember { mutableStateOf(false) }
    val isPagingLoading by mainViewModel.isPagingLoading.collectAsState()
    val isLastPageReached = mainViewModel.isLastPageReached


    val listState = mainViewModel.mainListState

    // 페이징 제거: 첫 로그인 시 checkProfile에서 전체를 한 번에 로드하고 이후엔 캐시를 사용한다.
    // (스크롤 페이징이 먼저 돌아 전체 로드를 건너뛰던 경쟁 상태를 방지)

    if (selectedDateToDelete != null) {
        AlertDialog(
            onDismissRequest = { selectedDateToDelete = null },
            title = { Text("기록 삭제") },
            text = { Text("${selectedDateToDelete}의 정산 기록을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    selectedDateToDelete?.let { date ->
                        mainViewModel.deleteDailyRecord(date)
                    }
                    selectedDateToDelete = null
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { selectedDateToDelete = null }) { Text("취소") }
            }
        )
    }

    if (showWithdrawConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWithdrawConfirmDialog = false },
            title = { Text("회원탈퇴 재확인") },
            text = { Text("진짜 회원탈퇴를 진행하시겠습니까?\n이 작업은 절대 되돌릴 수 없으며, 모든 정산 기록 및 신체 정보가 완전히 영구 삭제됩니다.") },
            confirmButton = {
                val context = LocalContext.current
                TextButton(
                    onClick = {
                        showWithdrawConfirmDialog = false
                        loadingMessage = "회원탈퇴 처리 중..."

                        // ViewModel 탈퇴 로직 호출 (로컬 DB 및 Firestore 일괄 삭제 수행)
                        mainViewModel.withdrawAccount(context) { success ->
                            loadingMessage = null
                            if (success) {
                                onNavigateToLogin()
                            }
                        }
                    }
                ) {
                    Text("진짜 탈퇴하기", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWithdrawConfirmDialog = false }) { Text("취소") }
            }
        )
    }

    LaunchedEffect(Unit) {
        mainViewModel.checkProfile()
    }

    // ✨ 프로필(키/체중)이 없을 경우 팝업 표시
    if (mainViewModel.showProfileDialog) {
        ProfileSetupDialog(
            onConfirm = { googleName, height, weight, isMale, birthDate ->
                mainViewModel.saveInitialProfile(googleName, height, weight, isMale, birthDate)
            }
        )
    }

    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    SystemUiController(showLogoutLoading)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("DailyNet") },
                    actions = {
                        // 1. 프로필 아이콘 (사용자 아바타)
                        IconButton(onClick = { showSheet = true }) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "프로필")
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onNavigateToInput,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("오늘의 정산") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
        ) { padding ->

            if (showLogoutLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("로그아웃 중입니다...", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = mainViewModel.mainListState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SummaryHeaderCard(
                        totalCalories = totalCalories,
                        latestDate = dailyRecords.firstOrNull()?.date ?: "기록 없음",
                        records = dailyRecords
                    )
                }

                // 최근 추이 미니 바 차트
                if (dailyRecords.isNotEmpty()) {
                    item {
                        RecentTrendChart(
                            records = dailyRecords,
                            onDayClick = onNavigateToDetail,
                            scrollState = mainViewModel.trendChartState
                        )
                    }
                }

                item {
                    Text(
                        text = "정산 기록",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                itemsIndexed(
                    items = dailyRecords,
                    key = { _, item -> item.date }
                ) { index, item ->
                    val currentWeekId = getWeekIdentifier(item.date)
                    val weekDisplayText = getWeekOfMonthText(item.date)

                    val totalWeeklyNet = weeklyCaloriesMap[currentWeekId] ?: 0

                    if (index == 0 || currentWeekId != getWeekIdentifier(dailyRecords[index - 1].date)) {
                        WeekDivider(
                            headerText = weekDisplayText,
                            weeklyCalories = totalWeeklyNet
                        )
                    }

                    DailyRecordHistoryItem(
                        item = item,
                        onClick = { onNavigateToDetail(item.date) },
                        onLongClick = { selectedDateToDelete = item.date }
                    )
                }

                if (isPagingLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }

            if (showSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showSheet = false },
                    sheetState = sheetState
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .navigationBarsPadding()
                    ) {
                        val context = LocalContext.current // 💡 추가
                        TextButton(
                            onClick = {
                                // ViewModel의 로그아웃 함수 실행
                                showSheet = false
                                showLogoutLoading = true
                                mainViewModel.logout(context) { success ->
                                    if (success) {
                                        showSheet = false
                                        onNavigateToLogin()
                                    } else {
                                        showLogoutLoading = false // 실패 시 로딩 해제
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Text("로그아웃")
                            }
                        }

                        // 회원탈퇴 버튼
                        TextButton(
                            onClick = {
                                showSheet = false // 바텀시트를 먼저 닫아 UI 유연성 확보
                                showWithdrawConfirmDialog = true
                            },
                        ) {
                            Text("회원탈퇴", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        if (showLogoutLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .clickable(enabled = false) {}, // 뒷배경 클릭 방지
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("로그아웃 중입니다...", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
fun WeekDivider(headerText: String, weeklyCalories: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 주별 구분선: 두께는 얇게, 대신 일별 테두리보다 진한 색으로 구분
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$headerText ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Text(
                text = "(${weeklyCalories} kcal)",
                style = MaterialTheme.typography.labelMedium,
                color = if (weeklyCalories <= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
            )

            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 신체 정보(키, 시작 체중)를 최초 1회 입력받는 다이얼로그
 */
@Composable
fun ProfileSetupDialog(
    onConfirm: (googleName: String,
                height: Float,
                weight: Float,
                isMale: Boolean,
                birthDate: String
            ) -> Unit
) {
    var birthDateText by remember { mutableStateOf("2000-01-01") } // 기본값
    var heightText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var selectedIsMale by remember { mutableStateOf(true) } // 👈 성별 상태 추가 (기본값 남성)

    val context = LocalContext.current
    val calendar = Calendar.getInstance()

    fun getCalendarFromText(dateStr: String): Calendar {
        val cal = Calendar.getInstance()
        try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)
            if (date != null) {
                cal.time = date
            }
        } catch (e: Exception) {
            // 파싱 실패 시 현재 날짜(오늘) 유지
        }
        return cal
    }

    AlertDialog(
        onDismissRequest = { /* 필수 입력 사항이므로 닫기 방지 */ },
        title = { Text("신체 정보 입력") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "더 정확한 AI 영양 분석을 위해\n정보를 입력해 주세요.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text("성별", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedIsMale,
                        onClick = { selectedIsMale = true },
                        label = { Text("남성") }
                    )
                    FilterChip(
                        selected = !selectedIsMale,
                        onClick = { selectedIsMale = false },
                        label = { Text("여성") }
                    )
                }

                OutlinedTextField(
                    value = birthDateText,
                    onValueChange = { /* 읽기 전용으로 만들기 위해 비워둠 */ },
                    label = { Text("생년월일") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val currentCal = getCalendarFromText(birthDateText)

                            android.app.DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    birthDateText =
                                        String.format(Locale.getDefault(), "%d-%02d-%02d", year, month + 1, dayOfMonth)
                                },
                                currentCal.get(Calendar.YEAR),
                                currentCal.get(Calendar.MONTH),
                                currentCal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                    enabled = false,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    label = { Text("키 (cm)") },
                    placeholder = { Text("예: 175.5") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )

                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text("시작 몸무게 (kg)") },
                    placeholder = { Text("예: 70.0") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = heightText.toFloatOrNull() ?: 0f
                    val w = weightText.toFloatOrNull() ?: 0f
                    val googleName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName ?: "사용자"
                    // 날짜 형식이 최소한의 형태를 갖췄는지 체크
                    if (h > 0f && w > 0f && birthDateText.contains("-")) {
                        onConfirm(googleName, h, w, selectedIsMale, birthDateText)
                    }
                },
                // 모든 필드가 채워졌을 때만 버튼 활성화
                enabled = heightText.isNotBlank() &&
                        weightText.isNotBlank() &&
                        birthDateText.isNotBlank()
            ) {
                Text("저장 및 시작")
            }
        }
    )
}

@Composable
fun SummaryHeaderCard(totalCalories: Int, latestDate: String, records: List<DailyRecordModel>) {
    // 7700kcal 기준 예상 체중 변동량 (적자면 감소)
    val expectedWeightChange = -totalCalories / 7700f
    val weightText = if (expectedWeightChange <= 0) {
        String.format(Locale.getDefault(), "%.1f kg", expectedWeightChange)
    } else {
        String.format(Locale.getDefault(), "-%.1f kg", expectedWeightChange)
    }
    val recordDays = records.size
    val avgNet = if (records.isNotEmpty()) records.sumOf { it.netCalories } / records.size else 0

    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "누적 칼로리 결산",
                style = MaterialTheme.typography.labelLarge,
                color = onPrimary.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "$totalCalories",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = onPrimary
            )
            Text(
                text = "kcal",
                style = MaterialTheme.typography.labelLarge,
                color = onPrimary.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = onPrimary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = "최근 업데이트 · $latestDate",
                    style = MaterialTheme.typography.labelMedium,
                    color = onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = onPrimary.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(14.dp))
            // 카드 하단에 3개 지표
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeroStat("예상 체중", weightText, onPrimary, Modifier.weight(1f))
                VerticalDivider(
                    modifier = Modifier.height(30.dp),
                    color = onPrimary.copy(alpha = 0.2f)
                )
                HeroStat("기록 일수", "${recordDays}일", onPrimary, Modifier.weight(1f))
                VerticalDivider(
                    modifier = Modifier.height(30.dp),
                    color = onPrimary.copy(alpha = 0.2f)
                )
                HeroStat("평균 순칼로리", "$avgNet", onPrimary, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.75f)
        )
    }
}

@Composable
fun StatTilesRow(records: List<DailyRecordModel>, totalCalories: Int) {
    val expectedWeightChange = -totalCalories / 7700f
    val weightText = if (expectedWeightChange <= 0)
        String.format(Locale.getDefault(), "%.1f kg", expectedWeightChange)
    else
        String.format(Locale.getDefault(), "-%.1f kg", expectedWeightChange)
    val recordDays = records.size
    val avgNet = if (records.isNotEmpty()) records.sumOf { it.netCalories } / records.size else 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatTile(Modifier.weight(1f), "예상 체중", weightText, MaterialTheme.colorScheme.primary)
        StatTile(Modifier.weight(1f), "기록 일수", "${recordDays}일", MaterialTheme.colorScheme.onSurface)
        StatTile(
            Modifier.weight(1f),
            "평균 순칼로리",
            "$avgNet",
            if (avgNet <= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
        )
    }
}

@Composable
fun StatTile(modifier: Modifier, label: String, value: String, valueColor: Color) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RecentTrendChart(
    records: List<DailyRecordModel>,
    onDayClick: (String) -> Unit,
    scrollState: LazyListState
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    // 열림/닫힘 상태를 저장해 앱 재실행 시에도 유지
    var expanded by remember { mutableStateOf(prefs.getBoolean(Constants.KEY_TREND_EXPANDED, true)) }

    // 기기 캘린더(구글 '대한민국의 휴일' 등)에서 공휴일 로드 → 색상에 반영
    var calendarHolidays by remember { mutableStateOf<Set<String>>(emptySet()) }
    var calPermTry by remember { mutableIntStateOf(0) }
    val calendarPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) calPermTry++ }
    LaunchedEffect(calPermTry) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
            return@LaunchedEffect
        }
        calendarHolidays = withContext(Dispatchers.IO) {
            val c = Calendar.getInstance()
            c.add(Calendar.YEAR, -1)
            val start = c.timeInMillis
            c.add(Calendar.YEAR, 2)
            val end = c.timeInMillis
            HolidayProvider(context).getHolidays(start, end)
        }
    }

    // 이번 달 기록을 표시. 이번 달이 7개 미만이면 최근 7개로 대체. (records는 최신순 DESC)
    val cal = Calendar.getInstance()
    val thisMonth = String.format(
        Locale.getDefault(), "%04d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1
    )
    val monthRecords = records.filter { it.date.startsWith(thisMonth) }
    val shown = if (monthRecords.size >= 7) monthRecords else records.take(7)

    // 이상치로 막대가 뭉개지지 않도록 90퍼센타일 기준으로 정규화
    val absList = shown.map { kotlin.math.abs(it.netCalories) }.sorted()
    val maxAbs = (
        if (absList.isEmpty()) 1
        else absList[(absList.size * 9 / 10).coerceAtMost(absList.size - 1)]
    ).coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 헤더: 클릭하면 열기/닫기 (상태는 저장됨)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        expanded = !expanded
                        prefs.edit { putBoolean(Constants.KEY_TREND_EXPANDED, expanded) }
                    }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "최근 추이",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "접기" else "펼치기",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(14.dp))
                // 가로 스크롤: 최신이 오른쪽, 왼쪽으로 드래그하면 과거 날짜까지 확인 (보이는 막대만 렌더 → 가벼움)
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(165.dp),
                    state = scrollState,
                    reverseLayout = true,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    items(shown, key = { it.date }) { r ->
                        val frac = kotlin.math.abs(r.netCalories).toFloat() / maxAbs
                        val barHeight = (frac * 80).dp.coerceAtLeast(6.dp)
                        val barColor = if (r.netCalories <= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                        Column(
                            modifier = Modifier
                                .width(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onDayClick(r.date) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            // 막대 위 값 숫자
                            Text(
                                text = "${r.netCalories}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = barColor,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // 얇은 고정폭 막대
                            Box(
                                modifier = Modifier
                                    .width(14.dp)
                                    .height(barHeight)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(barColor)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            // 일요일·공휴일=빨강, 토요일=파랑, 평일=기본색
                            val dayColor = weekdayColor(r.date, calendarHolidays)
                            Text(
                                text = r.date.takeLast(2),
                                style = MaterialTheme.typography.labelSmall,
                                color = dayColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 요일 표시 (예: 월, 화)
                            Text(
                                text = getDayOfWeekText(r.date).trim('(', ')'),
                                style = MaterialTheme.typography.labelSmall,
                                color = dayColor
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DailyRecordHistoryItem(
    item: DailyRecordModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {

    val dayOfWeekText = getDayOfWeekText(item.date)
    val dayNum = item.date.takeLast(2)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 날짜(일) 원형 뱃지
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dayNum,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${item.date} $dayOfWeekText",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (item.hasExercise) "운동 기록 있음 💪" else "휴식 😴",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "${item.netCalories} kcal",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (item.netCalories <= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
        }
    }


}