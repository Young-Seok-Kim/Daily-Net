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
import android.content.Context
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.youngs.dailynet.R
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
// 표기 형식이 언어마다 달라서(예: "05월 3주차" / "May, week 3") 문자열 리소스를 받아 채운다.
fun getWeekOfMonthText(context: Context, dateString: String): String {
    return try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateString)
            ?: return dateString
        val calendar = Calendar.getInstance().apply {
            time = date
            firstDayOfWeek = Calendar.MONDAY // 월요일 시작 기준
        }
        val month = calendar.get(Calendar.MONTH) + 1 // 0부터 시작하므로 +1
        val week = calendar.get(Calendar.WEEK_OF_MONTH)

        context.getString(R.string.week_of_month, month, week)
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
 * 월 구분선에 쓰는 연·월 표기. "2026년 8월" / "August 2026".
 *
 * 표기 순서와 조사가 언어마다 달라서 문자열을 직접 조립하지 않는다.
 * getBestDateTimePattern이 기기 언어에 맞는 패턴을 골라준다.
 */
fun getMonthHeaderText(dateString: String): String {
    return try {
        val locale = Locale.getDefault()
        val date = SimpleDateFormat("yyyy-MM-dd", locale).parse(dateString) ?: return ""
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMM")
        SimpleDateFormat(pattern, locale).format(date)
    } catch (e: Exception) {
        ""
    }
}

/**
 * 월·일 표기. "8월 8일" / "Aug 8".
 *
 * [getMonthHeaderText]와 같은 이유로 문자열을 직접 붙이지 않는다.
 * "8일"처럼 조사를 하드코딩하면 다른 언어에서 그대로 새어 나온다.
 */
fun getDayLabelText(dateString: String): String {
    return try {
        val locale = Locale.getDefault()
        val date = SimpleDateFormat("yyyy-MM-dd", locale).parse(dateString) ?: return ""
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
        SimpleDateFormat(pattern, locale).format(date)
    } catch (e: Exception) {
        ""
    }
}

/**
 * 짧은 월 표기. MMM 포맷이라 기기 언어에 따라 "8월" 혹은 "Aug"로 나온다.
 * 여러 달이 이어진 차트에서 달이 바뀌는 지점을 알리는 데 쓴다.
 */
fun getMonthText(dateString: String): String {
    return try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateString)
            ?: return ""
        SimpleDateFormat("MMM", Locale.getDefault()).format(date)
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
    onNavigateToSettings: () -> Unit,
    /** "yyyy-MM"을 받아 그 달 정산 화면을 연다 */
    onNavigateToMonth: (String) -> Unit,
) {
    val dailyRecords by mainViewModel.allDailyRecord.collectAsState()
    val totalCalories by mainViewModel.totalNetCalories.collectAsState()
    val weeklyCaloriesMap by mainViewModel.weeklyCaloriesMap.collectAsState()
    var selectedDateToDelete by remember { mutableStateOf<String?>(null) }
    val userProfile by mainViewModel.userProfile.collectAsState()
    val isPagingLoading by mainViewModel.isPagingLoading.collectAsState()
    val isLastPageReached = mainViewModel.isLastPageReached


    val listState = mainViewModel.mainListState

    // 펼쳐둔 주·달은 mainViewModel이 들고 있다. 상세·월 정산에 갔다 돌아와도 그대로 남도록
    // (여기서 remember로 들고 있으면 화면을 옮길 때 전부 접힌다).
    //
    // 구분선마다 매번 전체 목록을 훑지 않도록 요약은 기록이 바뀔 때 한 번만 계산해둔다.

    // 권장 단백질은 체중 기준이라 체중이 필요하다.
    // 그 기간에 기록된 체중이 없으면 가장 최근 체중(없으면 시작 체중)으로 대신한다.
    val latestWeight = remember(dailyRecords, userProfile) {
        dailyRecords.firstOrNull { it.weight > 0f }?.weight
            ?: userProfile?.initialWeight
            ?: 0f
    }
    val weekSummaries = remember(dailyRecords, latestWeight) {
        dailyRecords.groupBy { getWeekIdentifier(it.date) }
            .mapValues { (_, records) -> buildPeriodSummary(records, latestWeight) }
    }
    // 월 요약. 키는 "yyyy-MM" (date가 고정폭이라 앞 7글자가 곧 그 달이다)
    val monthSummaries = remember(dailyRecords, latestWeight) {
        dailyRecords.groupBy { it.date.take(7) }
            .mapValues { (_, records) -> buildPeriodSummary(records, latestWeight) }
    }

    // 페이징 제거: 첫 로그인 시 checkProfile에서 전체를 한 번에 로드하고 이후엔 캐시를 사용한다.
    // (스크롤 페이징이 먼저 돌아 전체 로드를 건너뛰던 경쟁 상태를 방지)

    if (selectedDateToDelete != null) {
        AlertDialog(
            onDismissRequest = { selectedDateToDelete = null },
            title = { Text(stringResource(R.string.delete_record_title)) },
            text = {
                Text(stringResource(R.string.delete_record_message, selectedDateToDelete.orEmpty()))
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedDateToDelete?.let { date ->
                        mainViewModel.deleteDailyRecord(date)
                    }
                    selectedDateToDelete = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedDateToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 로그아웃 / 회원탈퇴 / 신체 정보 수정은 설정 화면(SettingsScreen)으로 옮겼다.

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

    SystemUiController(false)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("DailyNet") },
                    actions = {
                        // 1. 프로필 아이콘 (사용자 아바타)
                        IconButton(onClick = { showSheet = true }) {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = stringResource(R.string.cd_profile)
                            )
                        }
                        // 2. 설정 (회원탈퇴 등 위험한 항목은 이 안쪽에 둔다)
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings_gear),
                                contentDescription = stringResource(R.string.settings_title)
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onNavigateToInput,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.today_record)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
        ) { padding ->

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
                        latestDate = dailyRecords.firstOrNull()?.date
                            ?: stringResource(R.string.no_records),
                        records = dailyRecords
                    )
                }

                // 최근 추이 미니 바 차트
                if (dailyRecords.isNotEmpty()) {
                    item {
                        RecentTrendChart(
                            records = dailyRecords,
                            onDayClick = onNavigateToDetail,
                            scrollState = mainViewModel.trendChartState,
                            monthsBack = mainViewModel.trendChartMonthsBack,
                            // Room에는 서버에서 받아온 만큼만 쌓여 있다.
                            // 아직 마지막 페이지가 아니면 더 과거가 서버에 남아 있다는 뜻.
                            // (isPagingLoading을 이 화면이 이미 구독하고 있어 로딩이 끝나면 여기도 다시 계산된다)
                            canFetchOlder = !mainViewModel.isLastPageReached,
                            onLoadOlder = mainViewModel::expandTrendChart
                        )
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.record_list_title),
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
                    val weekDisplayText = getWeekOfMonthText(LocalContext.current, item.date)

                    val totalWeeklyNet = weeklyCaloriesMap[currentWeekId] ?: 0

                    // 달이 바뀌는 자리에 월 구분선을 먼저 깐다. 주차 구분선보다 한 단계 위 계층이라
                    // 같은 날 둘 다 나오면 월 → 주 순서로 쌓인다.
                    val currentMonth = item.date.take(7)
                    if (index == 0 || currentMonth != dailyRecords[index - 1].date.take(7)) {
                        val monthSummary = monthSummaries[currentMonth]
                        MonthDivider(
                            headerText = getMonthHeaderText(item.date),
                            // 월 합계는 요약이 이미 들고 있어서 따로 집계하지 않는다
                            monthlyCalories = monthSummary?.netTotal ?: 0,
                            summary = monthSummary,
                            expanded = currentMonth in mainViewModel.expandedMonths,
                            onToggle = {
                                val open = mainViewModel.expandedMonths
                                mainViewModel.expandedMonths = if (currentMonth in open) {
                                    open - currentMonth
                                } else {
                                    // 주차와 마찬가지로 여러 달을 동시에 펼쳐 비교할 수 있게 둔다
                                    open + currentMonth
                                }
                            },
                            onOpenReport = { onNavigateToMonth(currentMonth) }
                        )
                    }

                    if (index == 0 || currentWeekId != getWeekIdentifier(dailyRecords[index - 1].date)) {
                        WeekDivider(
                            headerText = weekDisplayText,
                            weeklyCalories = totalWeeklyNet,
                            summary = weekSummaries[currentWeekId],
                            expanded = currentWeekId in mainViewModel.expandedWeeks,
                            onToggle = {
                                val open = mainViewModel.expandedWeeks
                                mainViewModel.expandedWeeks = if (currentWeekId in open) {
                                    open - currentWeekId
                                } else {
                                    // 여러 주를 동시에 펼칠 수 있게 둔다. 주끼리 비교하는 게 목적이라
                                    // 하나만 열리면 오히려 불편하다.
                                    open + currentWeekId
                                }
                            }
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
                        // 프로필 시트는 "내가 누구인지"만 보여준다.
                        // 로그아웃·회원탈퇴 같은 실제 동작은 설정 화면으로 옮겼다 (오클릭 방지)
                        val firebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val photoUrl = firebaseUser?.photoUrl
                            if (photoUrl != null) {
                                // 구글 계정 프로필 사진
                                AsyncImage(
                                    model = photoUrl,
                                    contentDescription = stringResource(R.string.cd_profile_photo),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape),
                                    // 로딩 중이거나 실패하면 기본 아이콘으로 대체
                                    placeholder = rememberVectorPainter(Icons.Default.AccountCircle),
                                    error = rememberVectorPainter(Icons.Default.AccountCircle)
                                )
                            } else {
                                Icon(
                                    Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = firebaseUser?.displayName
                                        ?: stringResource(R.string.default_user_name),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                firebaseUser?.email?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        userProfile?.let { p ->
                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                HeroStat(
                                    stringResource(R.string.profile_height),
                                    String.format(Locale.getDefault(), "%.1f cm", p.height),
                                    MaterialTheme.colorScheme.onSurface,
                                    Modifier.weight(1f)
                                )
                                HeroStat(
                                    stringResource(R.string.profile_start_weight),
                                    String.format(Locale.getDefault(), "%.1f kg", p.initialWeight),
                                    MaterialTheme.colorScheme.onSurface,
                                    Modifier.weight(1f)
                                )
                                HeroStat(
                                    stringResource(R.string.profile_subscription),
                                    stringResource(
                                        if (p.isSubscribed) R.string.subscription_active
                                        else R.string.subscription_free
                                    ),
                                    MaterialTheme.colorScheme.onSurface,
                                    Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = {
                                showSheet = false
                                onNavigateToSettings()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.open_settings))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

    }
}

/**
 * 주차 구분선. 누르면 그 주 요약이 아래로 펼쳐진다.
 *
 * 새 화면을 만들지 않은 이유는, 사용자가 이미 기록을 훑으며 지나가는 자리이기 때문이다.
 * 다만 구분선은 눌린다는 느낌이 없어서 화살표를 함께 둔다. 이게 없으면 기능이 없는 것과 같다.
 */
@Composable
fun WeekDivider(
    headerText: String,
    weeklyCalories: Int,
    summary: PeriodSummary? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null
) {
    SectionDivider(
        headerText = headerText,
        calories = weeklyCalories,
        emphasized = false,
        expanded = expanded,
        onToggle = onToggle
    ) {
        summary?.let { WeekSummaryPanel(it, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/**
 * 월 구분선. 누르면 그 달 요약이 아래로 펼쳐진다.
 *
 * 주차 구분선 위 계층이라 같은 모양을 두껍고 진하게 쓴다.
 * 굵기·색만 다르고 동작은 같아서, 두 구분선이 따로 놀지 않도록 [SectionDivider]를 함께 쓴다.
 */
@Composable
fun MonthDivider(
    headerText: String,
    monthlyCalories: Int,
    summary: PeriodSummary? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onOpenReport: (() -> Unit)? = null
) {
    SectionDivider(
        headerText = headerText,
        calories = monthlyCalories,
        emphasized = true,
        expanded = expanded,
        onToggle = onToggle
    ) {
        summary?.let {
            MonthSummaryPanel(
                summary = it,
                modifier = Modifier.padding(top = 8.dp),
                onOpen = onOpenReport
            )
        }
    }
}

/**
 * 주·월 구분선의 공통 몸통.
 *
 * @param emphasized 월 구분선처럼 한 단계 위 계층일 때. 선을 두껍게, 제목을 진하게 준다.
 * @param panel 펼쳤을 때 아래에 붙는 요약. 접혀 있으면 아예 호출하지 않는다.
 */
@Composable
private fun SectionDivider(
    headerText: String,
    calories: Int,
    emphasized: Boolean,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    panel: @Composable () -> Unit
) {
    val lineColor = if (emphasized) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val lineThickness = if (emphasized) 2.dp else 1.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
            // 구분선 자체는 얇아서 누를 곳이 좁다. 위아래 여백까지 눌리게 해 손가락으로 맞추기 쉽게 한다.
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 주별 구분선: 두께는 얇게, 대신 일별 테두리보다 진한 색으로 구분
            // 월별은 한 단계 위라 더 두껍고 진하게 (emphasized)
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = lineThickness,
                color = lineColor
            )
            Text(
                text = "$headerText ",
                style = if (emphasized) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.labelMedium,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
                color = if (emphasized) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Text(
                text = "(${calories} kcal)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                color = if (calories <= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
            )

            if (onToggle != null) {
                // 텍스트 화살표(▾)는 너무 작고 흐려서 눈에 안 띄었다.
                // 누를 수 있다는 걸 알려야 하는 자리라 아이콘 + 강조색으로 바꿨다.
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.cd_collapse else R.string.cd_expand
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(22.dp)
                )
            }

            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = lineThickness,
                color = lineColor
            )
        }

        if (expanded) panel()
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
        title = { Text(stringResource(R.string.profile_input_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.profile_input_desc),
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(stringResource(R.string.gender), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedIsMale,
                        onClick = { selectedIsMale = true },
                        label = { Text(stringResource(R.string.gender_male)) }
                    )
                    FilterChip(
                        selected = !selectedIsMale,
                        onClick = { selectedIsMale = false },
                        label = { Text(stringResource(R.string.gender_female)) }
                    )
                }

                OutlinedTextField(
                    value = birthDateText,
                    onValueChange = { /* 읽기 전용으로 만들기 위해 비워둠 */ },
                    label = { Text(stringResource(R.string.birth_date)) },
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
                    label = { Text(stringResource(R.string.height_label)) },
                    placeholder = { Text(stringResource(R.string.height_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )

                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text(stringResource(R.string.start_weight_label)) },
                    placeholder = { Text(stringResource(R.string.start_weight_placeholder)) },
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
                    val googleName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName ?: context.getString(R.string.default_user_name)
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
                Text(stringResource(R.string.save_and_start))
            }
        }
    )
}

/**
 * 가입 때 입력한 신체 정보(키 / 시작 몸무게 / 성별 / 생년월일)를 나중에 고치는 다이얼로그.
 * 초기 입력용 [ProfileSetupDialog]와 달리 기존 값이 채워진 상태로 열리고, 취소할 수 있다.
 */
@Composable
fun ProfileEditDialog(
    initialHeight: Float,
    initialWeight: Float,
    initialIsMale: Boolean,
    initialBirthDate: String,
    onDismiss: () -> Unit,
    onConfirm: (height: Float, weight: Float, isMale: Boolean, birthDate: String) -> Unit
) {
    var birthDateText by remember {
        mutableStateOf(initialBirthDate.ifBlank { "2000-01-01" })
    }
    var heightText by remember {
        mutableStateOf(if (initialHeight > 0f) initialHeight.toString() else "")
    }
    var weightText by remember {
        mutableStateOf(if (initialWeight > 0f) initialWeight.toString() else "")
    }
    var selectedIsMale by remember { mutableStateOf(initialIsMale) }

    val context = LocalContext.current

    fun getCalendarFromText(dateStr: String): Calendar {
        val cal = Calendar.getInstance()
        try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)
            if (date != null) cal.time = date
        } catch (_: Exception) {
            // 파싱 실패 시 오늘 날짜 유지
        }
        return cal
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_edit_body_info)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.gender), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedIsMale,
                        onClick = { selectedIsMale = true },
                        label = { Text(stringResource(R.string.gender_male)) }
                    )
                    FilterChip(
                        selected = !selectedIsMale,
                        onClick = { selectedIsMale = false },
                        label = { Text(stringResource(R.string.gender_female)) }
                    )
                }

                OutlinedTextField(
                    value = birthDateText,
                    onValueChange = { },
                    label = { Text(stringResource(R.string.birth_date)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val currentCal = getCalendarFromText(birthDateText)
                            android.app.DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    birthDateText = String.format(
                                        Locale.getDefault(),
                                        "%d-%02d-%02d", year, month + 1, dayOfMonth
                                    )
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
                    label = { Text(stringResource(R.string.height_label)) },
                    placeholder = { Text(stringResource(R.string.height_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )

                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text(stringResource(R.string.start_weight_label)) },
                    placeholder = { Text(stringResource(R.string.start_weight_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )

                Text(
                    stringResource(R.string.start_weight_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            val h = heightText.toFloatOrNull() ?: 0f
            val w = weightText.toFloatOrNull() ?: 0f
            Button(
                onClick = { onConfirm(h, w, selectedIsMale, birthDateText) },
                enabled = h > 0f && w > 0f && birthDateText.contains("-")
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
                text = stringResource(R.string.total_calories_title),
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
                    text = stringResource(R.string.last_updated, latestDate),
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
                HeroStat(
                    stringResource(R.string.stat_est_weight),
                    weightText,
                    onPrimary,
                    Modifier.weight(1f)
                )
                VerticalDivider(
                    modifier = Modifier.height(30.dp),
                    color = onPrimary.copy(alpha = 0.2f)
                )
                HeroStat(
                    stringResource(R.string.stat_record_days),
                    stringResource(R.string.weight_stat_days_value, recordDays),
                    onPrimary,
                    Modifier.weight(1f)
                )
                VerticalDivider(
                    modifier = Modifier.height(30.dp),
                    color = onPrimary.copy(alpha = 0.2f)
                )
                HeroStat(
                    stringResource(R.string.stat_avg_net),
                    "$avgNet",
                    onPrimary,
                    Modifier.weight(1f)
                )
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
        StatTile(
            Modifier.weight(1f),
            stringResource(R.string.stat_est_weight),
            weightText,
            MaterialTheme.colorScheme.primary
        )
        StatTile(
            Modifier.weight(1f),
            stringResource(R.string.stat_record_days),
            stringResource(R.string.weight_stat_days_value, recordDays),
            MaterialTheme.colorScheme.onSurface
        )
        StatTile(
            Modifier.weight(1f),
            stringResource(R.string.stat_avg_net),
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

/**
 * 최근 순칼로리 미니 바 차트.
 *
 * 처음에는 이번 달만 보여주고, 왼쪽(과거)으로 끝까지 밀 때마다 한 달치씩 이어 붙인다.
 * 불러오는 단위가 "달"이라 어느 달이든 1일부터 말일까지 통째로 들어온다.
 *
 * @param monthsBack 지금 몇 달 전까지 펼쳐져 있는지 (0이면 이번 달만)
 * @param canFetchOlder 로컬에 없더라도 서버에서 더 받아올 기록이 남아 있는지
 * @param onLoadOlder 한 달치를 더 붙여야 할 때 호출
 */
@Composable
fun RecentTrendChart(
    records: List<DailyRecordModel>,
    onDayClick: (String) -> Unit,
    scrollState: LazyListState,
    monthsBack: Int,
    canFetchOlder: Boolean,
    onLoadOlder: () -> Unit
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

    // 이번 달부터 monthsBack 달 전까지를 표시한다. (records는 최신순 DESC)
    // 왼쪽 끝(과거)까지 밀면 아래 LaunchedEffect가 한 달치씩 더 붙여준다.
    //
    // 경계는 "그 달 1일"이라 어느 달을 불러오든 항상 1일부터 말일까지 통째로 들어온다.
    // 날짜가 yyyy-MM-dd 고정폭이라 문자열 비교만으로 날짜 비교가 된다.
    val oldestMonthStart = remember(monthsBack) {
        val c = Calendar.getInstance()
        c.add(Calendar.MONTH, -monthsBack)
        String.format(
            Locale.getDefault(), "%04d-%02d-01",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1
        )
    }
    val shown = remember(records, oldestMonthStart) {
        records.filter { it.date >= oldestMonthStart }
    }
    // records가 DESC라 마지막이 가장 오래된 기록.
    // 그보다 더 과거가 로컬에 남아 있거나, 서버에서 더 받아올 게 있으면 계속 펼칠 수 있다
    val hasOlder = (records.lastOrNull()?.date ?: oldestMonthStart) < oldestMonthStart ||
            canFetchOlder

    // 이번 달 기록이 얼마 없으면(월초 등) 막대 몇 개만 덩그러니 남는다.
    // 그럴 땐 기다리지 말고 7개가 찰 때까지 지난 달을 미리 붙인다.
    LaunchedEffect(shown.size, hasOlder) {
        if (shown.size < 7 && hasOlder) onLoadOlder()
    }

    // 왼쪽(과거) 끝 가까이 밀면 한 달치를 더 붙인다.
    // reverseLayout이라 인덱스가 클수록 과거 → 마지막으로 보이는 항목이 화면 왼쪽 끝이다.
    // 끝에 닿았다는 불린이 아니라 "몇 번째까지 보이는지"를 보는 이유는,
    // 한 번 끝에 닿은 뒤 목록이 늘지 않으면 불린이 true에 머물러 다시 트리거되지 않기 때문이다.
    val lastVisibleIndex by remember {
        derivedStateOf { scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
    }
    LaunchedEffect(lastVisibleIndex, shown.size, hasOlder) {
        if (lastVisibleIndex >= 0 && lastVisibleIndex >= shown.size - 3 && hasOlder) onLoadOlder()
    }

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
                    text = stringResource(R.string.recent_trend),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.cd_collapse else R.string.cd_expand
                    ),
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
                    itemsIndexed(shown, key = { _, r -> r.date }) { index, r ->
                        // 달이 바뀌는 자리(그 달의 가장 이른 기록)에만 월 표기를 붙인다.
                        // 여러 달이 이어 붙으니 지금 보는 게 몇 월인지 알 수 있어야 한다.
                        // reverseLayout이라 index+1이 화면 왼쪽(더 과거)이다.
                        val isMonthHead = index == shown.lastIndex ||
                                shown[index + 1].date.take(7) != r.date.take(7)
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
                            // 월 표기. 해당 없는 날도 빈 줄로 자리를 잡아둬야
                            // 막대들이 같은 높이에서 시작한다(Arrangement.Bottom 기준선 유지)
                            Text(
                                text = if (isMonthHead) getMonthText(r.date) else "",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
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
                    text = stringResource(
                        if (item.hasExercise) R.string.has_exercise else R.string.rest_day
                    ),
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