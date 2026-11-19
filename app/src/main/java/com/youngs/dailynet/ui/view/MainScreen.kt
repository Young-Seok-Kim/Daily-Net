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
import androidx.compose.material.icons.filled.Delete
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
import com.youngs.dailynet.ui.theme.DnDimens
import com.youngs.dailynet.ui.theme.DnTheme
import com.youngs.dailynet.ui.theme.NetColors
import com.youngs.dailynet.ui.theme.tabular
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.sp
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
import com.youngs.dailynet.data.model.AiScreenTypes
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
 * 날짜별 표시 색상: 일요일·공휴일=흑자색(붉은 계열), 토요일=파랑, 평일=null(기본색).
 * 공휴일은 기기 캘린더(구글 '대한민국의 휴일' 등)에서 읽어온 날짜 집합을 사용한다.
 * 색은 테마(NetColors)에서 받아 라이트·다크에 맞춘다.
 */
fun weekdayColor(dateString: String, calendarHolidays: Set<String>, net: NetColors): Color? {
    return try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateString)
            ?: return null
        val cal = Calendar.getInstance().apply { time = date }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        when {
            dow == Calendar.SUNDAY || calendarHolidays.contains(dateString) -> net.sunday
            dow == Calendar.SATURDAY -> net.saturday
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
    /** 주("202646")·연("2026") 정산 상세를 연다. AI 평가 전문은 거기서 본다 */
    onNavigateToPeriod: (PeriodKind, String) -> Unit = { _, _ -> },
    /** 운동 이용권 화면 */
    onNavigateToPasses: () -> Unit = {},
) {
    val passUsages by mainViewModel.passUsages.collectAsState()
    // 표시 기간 설정이 켜져 있으면 그 안의 기록만 온다 (MainViewModel.visibleRecords)
    val dailyRecords by mainViewModel.visibleRecords.collectAsState()
    val dateRange by mainViewModel.dateRange.collectAsState()
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
    // 연 요약. 키는 "yyyy". 연 구분선 패널과 AI 평가가 쓴다
    val yearRecords = remember(dailyRecords) { dailyRecords.groupBy { it.date.take(4) } }
    val yearSummaries = remember(yearRecords, latestWeight) {
        yearRecords.mapValues { (_, records) -> buildPeriodSummary(records, latestWeight) }
    }
    val weekRecords = remember(dailyRecords) { dailyRecords.groupBy { getWeekIdentifier(it.date) } }
    val monthRecords = remember(dailyRecords) { dailyRecords.groupBy { it.date.take(7) } }

    // 설정에서 끈 구분선은 그리지 않는다. 접힌 상태가 아니라 줄 자체가 없다
    val showWeek = mainViewModel.showWeekDivider
    val showMonth = mainViewModel.showMonthDivider
    val showYear = mainViewModel.showYearDivider

    // 페이징 제거: 첫 로그인 시 checkProfile에서 전체를 한 번에 로드하고 이후엔 캐시를 사용한다.
    // (스크롤 페이징이 먼저 돌아 전체 로드를 건너뛰던 경쟁 상태를 방지)

    selectedDateToDelete?.let { date ->
        // 되돌릴 수 없는 동작이라 날짜와 순칼로리를 대상 박스로 보여 눈으로 대조하게 한다
        val net = dailyRecords.firstOrNull { it.date == date }?.netCalories
        DnDialog(
            title = stringResource(R.string.delete_record_title),
            body = stringResource(R.string.delete_record_message),
            tone = DnDialogTone.Destructive,
            icon = Icons.Default.Delete,
            targetLabel = stringResource(R.string.delete_target),
            targetValue = listOfNotNull(
                "$date ${getDayOfWeekText(date)}".trim(),
                net?.let { String.format(Locale.getDefault(), "%+,d kcal", it) }
            ).joinToString(" · "),
            primaryLabel = stringResource(R.string.delete),
            onPrimary = {
                mainViewModel.deleteDailyRecord(date)
                selectedDateToDelete = null
            },
            onDismiss = { selectedDateToDelete = null }
        )
    }

    // 로그아웃 / 회원탈퇴 / 신체 정보 수정은 설정 화면(SettingsScreen)으로 옮겼다.

    LaunchedEffect(Unit) {
        mainViewModel.checkProfile()
    }

    // ✨ 프로필(키/체중)이 없을 경우 팝업 표시
    //
    // 가입은 두 단계다. 1단계(신체 정보)를 저장하면 뷰모델이 1단계를 닫고 2단계를 켠다
    // (saveInitialProfile → showAiContextSetupDialog). 둘을 when으로 이어 두면
    // 두 시트가 겹쳐 뜨지 않고, 2단계를 건너뛰면 아무것도 남지 않는다.
    when {
        mainViewModel.showProfileDialog -> ProfileSetupDialog(
            onConfirm = { googleName, height, weight, isMale, birthDate ->
                mainViewModel.saveInitialProfile(googleName, height, weight, isMale, birthDate)
            }
        )
        // 2단계 — AI 분석 참고 정보(선택). 건너뛰면 저장 없이 닫힌다
        mainViewModel.showAiContextSetupDialog -> AiContextOnboardingSheet(
            onSkip = { mainViewModel.skipAiContextSetup() },
            onSave = { goal, activity, note ->
                mainViewModel.saveInitialAiContext(goal, activity, note)
            }
        )
    }

    var showSheet by remember { mutableStateOf(false) }
    // 부분 확장으로 열리면 아래 칸이 잘린 채 멈춘다. 내용이 짧으니 바로 다 펼친다
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 프로필 시트의 로그아웃. 설정 화면과 같은 확인 창·같은 진행 표시를 쓴다.
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val isLoggingOut by mainViewModel.isLoggingOut.collectAsState()
    val context = LocalContext.current
    if (showLogoutConfirm) {
        LogoutConfirmDialog(
            onConfirm = {
                showLogoutConfirm = false
                mainViewModel.logout(context) { success ->
                    if (success) onNavigateToLogin()
                }
            },
            onDismiss = { showLogoutConfirm = false }
        )
    }

    SystemUiController(false)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "DailyNet",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    actions = {
                        // 1. 프로필 아이콘 (사용자 아바타)
                        RoundIconButton(onClick = { showSheet = true }) {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = stringResource(R.string.cd_profile)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // 2. 설정 (회원탈퇴 등 위험한 항목은 이 안쪽에 둔다)
                        RoundIconButton(onClick = onNavigateToSettings) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings_gear),
                                contentDescription = stringResource(R.string.settings_title)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                )
            },
            floatingActionButton = {
                // 행동 버튼은 화면에서 유일하게 코랄을 쓴다. 다른 어디에도 같은 색이 없어야 눈에 띈다
                // 그 위에는 AI 버튼을 쌓는다 — 두 버튼이 겹치지 않고, 자리 계산도 필요 없다
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AiAskFab(
                        mainViewModel = mainViewModel,
                        screenType = AiScreenTypes.DAILY_LIST,
                        screenTitle = stringResource(R.string.ai_screen_daily_list),
                        contextData = remember(dailyRecords, passUsages) { AiAskContext.dailyList(dailyRecords, passUsages) }
                    )
                    ExtendedFloatingActionButton(
                        onClick = onNavigateToInput,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = {
                            Text(
                                text = stringResource(R.string.today_record),
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        shape = RoundedCornerShape(DnDimens.RadiusHero),
                        containerColor = DnTheme.net.coral,
                        contentColor = Color.White
                    )
                }
            }
        ) { padding ->

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = mainViewModel.mainListState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                item {
                    SummaryHeaderCard(
                        totalCalories = totalCalories,
                        latestDate = dailyRecords.firstOrNull()?.date
                            ?: stringResource(R.string.no_records),
                        records = dailyRecords,
                        today = mainViewModel.todayDate
                    )
                    // 쓸 수 있는 운동 이용권이 있으면 남은 양을 한 줄로. 없으면 아무것도 안 그린다
                    PassSummaryRow(
                        usages = passUsages,
                        onClick = onNavigateToPasses,
                        modifier = Modifier.padding(top = DnDimens.SpaceMd)
                    )
                    Spacer(modifier = Modifier.height(DnDimens.SpaceMd))
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
                            onLoadOlder = mainViewModel::expandTrendChart,
                            today = mainViewModel.todayDate
                        )
                        Spacer(modifier = Modifier.height(DnDimens.SpaceMd))
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.record_list_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 10.dp, start = 4.dp)
                    )
                    // 표시 기간이 켜져 있으면 어디부터 어디까지 보고 있는지 제목 밑에 적는다.
                    // 이게 없으면 기록이 사라진 줄 알고 당황한다.
                    if (dateRange.active) {
                        Text(
                            text = stringResource(
                                R.string.record_list_range,
                                dateRange.start.ifBlank { mainViewModel.defaultStartDate().ifBlank { "…" } },
                                dateRange.end.ifBlank { stringResource(R.string.range_today) }
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        )
                    }
                }

                itemsIndexed(
                    items = dailyRecords,
                    key = { _, item -> item.date }
                ) { index, item ->
                    val currentWeekId = getWeekIdentifier(item.date)
                    val weekDisplayText = getWeekOfMonthText(LocalContext.current, item.date)

                    val totalWeeklyNet = weeklyCaloriesMap[currentWeekId] ?: 0

                    // 해가 바뀌는 자리에 연 구분선을 맨 먼저 깐다. 월 구분선보다 한 단계 위 계층이라
                    // 같은 날 셋 다 나오면 연 → 월 → 주 순서로 쌓인다.
                    // 올해 첫 기록 위에도 나온다. 올해를 평가하는 자리가 거기뿐이기 때문이다.
                    // (설정의 "연 구분선"을 끄면 사라진다)
                    val currentYear = item.date.take(4)
                    val isYearHead = index == 0 || currentYear != dailyRecords[index - 1].date.take(4)
                    if (showYear && isYearHead) {
                        val yearSummary = yearSummaries[currentYear]
                        val yearTitle = getYearHeaderText(item.date)
                        YearDivider(
                            headerText = yearTitle,
                            yearlyCalories = yearSummary?.netTotal ?: 0,
                            summary = yearSummary,
                            expanded = currentYear in mainViewModel.expandedYears,
                            onToggle = {
                                val open = mainViewModel.expandedYears
                                mainViewModel.expandedYears =
                                    if (currentYear in open) open - currentYear else open + currentYear
                            },
                            evaluation = {
                                PeriodEvaluationRow(
                                    mainViewModel = mainViewModel,
                                    kind = PeriodKind.YEAR,
                                    label = currentYear,
                                    records = yearRecords[currentYear].orEmpty(),
                                    onOpen = { onNavigateToPeriod(PeriodKind.YEAR, currentYear) }
                                )
                            }
                        )
                    }

                    // 달이 바뀌는 자리에 월 구분선을 깐다. 주차 구분선보다 한 단계 위 계층이라
                    // 같은 날 둘 다 나오면 월 → 주 순서로 쌓인다.
                    val currentMonth = item.date.take(7)
                    val isMonthHead = index == 0 || currentMonth != dailyRecords[index - 1].date.take(7)
                    if (showMonth && isMonthHead) {
                        val monthSummary = monthSummaries[currentMonth]
                        val monthTitle = getMonthHeaderText(item.date)
                        MonthDivider(
                            headerText = monthTitle,
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
                            onOpenReport = { onNavigateToMonth(currentMonth) },
                            // 월의 전문은 월 정산 화면에 있다. 줄을 누르면 거기로 간다
                            evaluation = {
                                PeriodEvaluationRow(
                                    mainViewModel = mainViewModel,
                                    kind = PeriodKind.MONTH,
                                    label = currentMonth,
                                    records = monthRecords[currentMonth].orEmpty(),
                                    onOpen = { onNavigateToMonth(currentMonth) }
                                )
                            }
                        )
                    }

                    val isWeekHead = index == 0 || currentWeekId != getWeekIdentifier(dailyRecords[index - 1].date)
                    if (showWeek && isWeekHead) {
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
                            },
                            evaluation = {
                                PeriodEvaluationRow(
                                    mainViewModel = mainViewModel,
                                    kind = PeriodKind.WEEK,
                                    label = currentWeekId.toString(),
                                    records = weekRecords[currentWeekId].orEmpty(),
                                    onOpen = { onNavigateToPeriod(PeriodKind.WEEK, currentWeekId.toString()) }
                                )
                            }
                        )
                    }

                    // 구분선 바로 아래 첫 줄에는 위 선을 긋지 않는다 (구분선과 겹쳐 두 줄이 된다).
                    // 설정에서 끈 구분선은 없는 것이므로 그 자리엔 선을 긋는다.
                    val underDivider = (showWeek && isWeekHead) || (showMonth && isMonthHead) || (showYear && isYearHead)
                    DailyRecordHistoryItem(
                        item = item,
                        showTopLine = !underDivider,
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
                    // AI 버튼(60) + '+' 버튼(56) + 사이 여백이 마지막 기록을 가리지 않게 띄운다
                    Spacer(modifier = Modifier.height(148.dp))
                }
            }

            if (showSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showSheet = false },
                    sheetState = sheetState,
                    shape = RoundedCornerShape(topStart = DnDimens.RadiusHero, topEnd = DnDimens.RadiusHero),
                    containerColor = MaterialTheme.colorScheme.surface
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
                                StatCell(
                                    label = stringResource(R.string.profile_height),
                                    value = String.format(Locale.getDefault(), "%.1f cm", p.height),
                                    modifier = Modifier.weight(1f)
                                )
                                // 기준이 되는 시작 몸무게. 표시 기간이 켜져 있으면 가입 때 적은 값이 아니라
                                // 그 기간에 처음 적은 체중이다 — 사용자에게는 시작일부터 다시 시작한 것처럼 보여야 한다.
                                // (dailyRecords는 최신순이라 lastOrNull이 가장 이른 날이다)
                                val baselineWeight = if (dateRange.active) {
                                    dailyRecords.lastOrNull { it.weight > 0f }?.weight ?: p.initialWeight
                                } else {
                                    p.initialWeight
                                }
                                StatCell(
                                    label = stringResource(R.string.profile_start_weight),
                                    value = String.format(Locale.getDefault(), "%.1f kg", baselineWeight),
                                    modifier = Modifier.weight(1f)
                                )
                                // 시작 몸무게가 "기준점"이려면 지금 어디까지 왔는지가 옆에 있어야 한다.
                                // 정산에 적은 가장 최근 체중과 시작 대비 차이. 체중 기록이 없으면 자리를 비운다.
                                dailyRecords.firstOrNull { it.weight > 0f }?.weight?.let { current ->
                                    StatCell(
                                        label = stringResource(
                                            R.string.profile_current_weight,
                                            String.format(Locale.getDefault(), "%+.1f", current - baselineWeight)
                                        ),
                                        value = String.format(Locale.getDefault(), "%.1f kg", current),
                                        valueColor = DnTheme.net.of(current - baselineWeight),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        // 로그아웃은 설정 화면에도 있지만, 프로필을 열어 "내가 누구인지" 본 자리에서
                        // 바로 나갈 수 있어야 한다. 실수로 눌러도 확인 창이 한 번 더 막는다.
                        FilledTonalButton(
                            onClick = {
                                showSheet = false
                                showLogoutConfirm = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.logout))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // 로그아웃 진행 표시. 로컬 기록을 지우는 동안 뒤를 누르지 못하게 덮는다 (설정 화면과 같은 부품)
        if (isLoggingOut) {
            DnBlockingOverlay(stringResource(R.string.logout_in_progress))
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
    onToggle: (() -> Unit)? = null,
    /** 패널 아래에 붙는 AI 평가 줄. 펼쳤을 때만 그린다 */
    evaluation: (@Composable () -> Unit)? = null
) {
    SectionDivider(
        headerText = headerText,
        calories = weeklyCalories,
        level = DividerLevel.WEEK,
        expanded = expanded,
        onToggle = onToggle
    ) {
        summary?.let { WeekSummaryPanel(it, modifier = Modifier.padding(top = 8.dp), extra = evaluation) }
    }
}

/**
 * 연 구분선. 월 구분선 위 계층이라 민트 바탕으로 한 단계 더 띄운다.
 *
 * 패널은 월 패널과 같은 구성(합계·체중 변화·일수·평균·탄단지)이다. 연 정산 화면은 따로 없어서
 * 열기 줄은 없고, 대신 AI 평가 카드가 이 패널 안에 들어간다.
 */
@Composable
fun YearDivider(
    headerText: String,
    yearlyCalories: Int,
    summary: PeriodSummary? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    evaluation: (@Composable () -> Unit)? = null
) {
    SectionDivider(
        headerText = headerText,
        calories = yearlyCalories,
        level = DividerLevel.YEAR,
        expanded = expanded,
        onToggle = onToggle
    ) {
        summary?.let {
            MonthSummaryPanel(
                summary = it,
                modifier = Modifier.padding(top = 8.dp),
                onOpen = null,
                totalLabel = stringResource(R.string.year_summary_total),
                extra = evaluation
            )
        }
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
    onOpenReport: (() -> Unit)? = null,
    evaluation: (@Composable () -> Unit)? = null
) {
    SectionDivider(
        headerText = headerText,
        calories = monthlyCalories,
        level = DividerLevel.MONTH,
        expanded = expanded,
        onToggle = onToggle
    ) {
        summary?.let {
            MonthSummaryPanel(
                summary = it,
                modifier = Modifier.padding(top = 8.dp),
                onOpen = onOpenReport,
                extra = evaluation
            )
        }
    }
}

/** 구분선의 계층. 위로 갈수록 두껍고 진하다 */
private enum class DividerLevel { WEEK, MONTH, YEAR }

/**
 * 주·월·연 구분선의 공통 몸통.
 *
 * 월은 surfaceVariant 띠(좌측 제목 · 우측 합계 · 셰브론)로, 주는 띠 없이 작은 글자 한 줄로 그린다.
 * 연은 월과 같은 모양에 민트 바탕(primaryContainer)이라 한 눈에 위 계층으로 보인다.
 * 예전의 "선-글자-선" 모양은 카드 테두리·펼침 패널과 겹쳐 한 화면에 선이 네 겹으로 쌓였다.
 *
 * @param level 구분선 계층. 주 < 월 < 연 순으로 두껍고 진하다.
 * @param panel 펼쳤을 때 아래에 붙는 요약. 접혀 있으면 아예 호출하지 않는다.
 */
@Composable
private fun SectionDivider(
    headerText: String,
    calories: Int,
    level: DividerLevel,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    panel: @Composable () -> Unit
) {
    val emphasized = level != DividerLevel.WEEK
    val shape = RoundedCornerShape(DnDimens.RadiusTile)
    val band = when (level) {
        DividerLevel.YEAR -> MaterialTheme.colorScheme.primaryContainer
        DividerLevel.MONTH -> MaterialTheme.colorScheme.surfaceVariant
        // 주에 아무 바탕도 없으면 기록 줄과 구분이 안 됐다
        DividerLevel.WEEK -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val titleColor = when (level) {
        DividerLevel.YEAR -> MaterialTheme.colorScheme.onPrimaryContainer
        DividerLevel.MONTH -> MaterialTheme.colorScheme.onSurface
        DividerLevel.WEEK -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (emphasized) 10.dp else 6.dp, bottom = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(band)
                .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
                .padding(
                    horizontal = if (emphasized) 16.dp else 14.dp,
                    vertical = if (emphasized) 14.dp else 10.dp
                )
        ) {
            Text(
                text = headerText,
                style = if (emphasized) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.labelLarge,
                color = titleColor,
                modifier = Modifier.weight(1f)
            )
            KcalValue(
                value = calories,
                style = if (emphasized) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.labelMedium,
                withUnit = emphasized,
                unitStyle = MaterialTheme.typography.labelMedium
            )

            if (onToggle != null) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.cd_collapse else R.string.cd_expand
                    ),
                    tint = if (level == DividerLevel.YEAR) titleColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(20.dp)
                )
            }
        }

        if (expanded) {
            Box(modifier = Modifier.padding(horizontal = if (emphasized) 8.dp else 0.dp)) { panel() }
        }
    }
}

/**
 * 신체 정보 입력 칸 묶음. 최초 입력([ProfileSetupDialog])과 수정([ProfileEditDialog])이 같은 칸을 쓴다.
 * 생년월일은 누르면 앱 날짜 선택([DnDatePickerDialog])이 뜬다. 비어 있으면 오늘부터 고르게 둔다
 * (예전엔 2000-01-01이 기본값이었다).
 */
@Composable
private fun BodyInfoFields(
    isMale: Boolean,
    onGender: (Boolean) -> Unit,
    birthDate: String,
    onBirthDate: (String) -> Unit,
    heightText: String,
    onHeight: (String) -> Unit,
    weightText: String,
    onWeight: (String) -> Unit
) {
    var pickingBirthDate by remember { mutableStateOf(false) }
    Text(stringResource(R.string.gender), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = isMale,
            onClick = { onGender(true) },
            label = { Text(stringResource(R.string.gender_male)) }
        )
        FilterChip(
            selected = !isMale,
            onClick = { onGender(false) },
            label = { Text(stringResource(R.string.gender_female)) }
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = birthDate,
            onValueChange = {},
            label = { Text(stringResource(R.string.birth_date)) },
            placeholder = { Text(stringResource(R.string.birth_date_pick)) },
            enabled = false,
            singleLine = true,
            shape = RoundedCornerShape(DnDimens.RadiusChip),
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
        // 비활성 입력칸은 터치를 먹지 않아 위에 투명 판을 깔아 누르게 한다
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { pickingBirthDate = true }
        )
    }

    if (pickingBirthDate) {
        DnDatePickerDialog(
            initial = birthDate,
            onPick = {
                onBirthDate(it)
                pickingBirthDate = false
            },
            onDismiss = { pickingBirthDate = false }
        )
    }

    OutlinedTextField(
        value = heightText,
        onValueChange = onHeight,
        label = { Text(stringResource(R.string.height_label)) },
        placeholder = { Text(stringResource(R.string.height_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DnDimens.RadiusChip),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )

    OutlinedTextField(
        value = weightText,
        onValueChange = onWeight,
        label = { Text(stringResource(R.string.start_weight_label)) },
        placeholder = { Text(stringResource(R.string.start_weight_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DnDimens.RadiusChip),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
    Text(
        stringResource(R.string.start_weight_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 신체 정보(키, 시작 체중, 성별, 생년월일)를 최초 1회 입력받는 시트.
 * 필수 단계라 닫을 수 없다 (바깥 터치·스와이프·뒤로가기 모두 막힘, "닫기" 없음).
 *
 * 버튼은 '다음'이다 — 누르면 이 값을 저장하고 바로 2단계(AI 분석 참고 정보, 선택)로 넘어간다.
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
    var birthDateText by remember { mutableStateOf("") }
    var heightText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var selectedIsMale by remember { mutableStateOf(true) }
    val context = LocalContext.current

    val h = heightText.toFloatOrNull() ?: 0f
    val w = weightText.toFloatOrNull() ?: 0f
    val valid = isProfileInputValid(heightText, weightText, birthDateText)

    DnFormSheet(
        title = stringResource(R.string.profile_input_title),
        subtitle = stringResource(R.string.profile_input_desc),
        dismissible = false,
        onDismiss = {},
        primaryLabel = stringResource(R.string.profile_setup_next),
        primaryEnabled = valid,
        onPrimary = {
            val googleName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName
                ?: context.getString(R.string.default_user_name)
            onConfirm(googleName, h, w, selectedIsMale, birthDateText)
        }
    ) {
        BodyInfoFields(
            isMale = selectedIsMale, onGender = { selectedIsMale = it },
            birthDate = birthDateText, onBirthDate = { birthDateText = it },
            heightText = heightText, onHeight = { heightText = it },
            weightText = weightText, onWeight = { weightText = it }
        )
    }
}

/**
 * 가입 때 입력한 신체 정보(키 / 시작 몸무게 / 성별 / 생년월일)를 나중에 고치는 시트.
 * 초기 입력용 [ProfileSetupDialog]와 달리 기존 값이 채워진 상태로 열리고, 닫을 수 있다.
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
    var birthDateText by remember { mutableStateOf(initialBirthDate) }
    var heightText by remember { mutableStateOf(if (initialHeight > 0f) initialHeight.toString() else "") }
    var weightText by remember { mutableStateOf(if (initialWeight > 0f) initialWeight.toString() else "") }
    var selectedIsMale by remember { mutableStateOf(initialIsMale) }

    val h = heightText.toFloatOrNull() ?: 0f
    val w = weightText.toFloatOrNull() ?: 0f

    DnFormSheet(
        title = stringResource(R.string.settings_edit_body_info),
        onDismiss = onDismiss,
        primaryLabel = stringResource(R.string.save),
        primaryEnabled = isProfileInputValid(heightText, weightText, birthDateText),
        onPrimary = { onConfirm(h, w, selectedIsMale, birthDateText) }
    ) {
        BodyInfoFields(
            isMale = selectedIsMale, onGender = { selectedIsMale = it },
            birthDate = birthDateText, onBirthDate = { birthDateText = it },
            heightText = heightText, onHeight = { heightText = it },
            weightText = weightText, onWeight = { weightText = it }
        )
    }
}

/**
 * 맨 위 히어로 카드. 짙은 바탕(딥틸)에 누적 결산을 크게, 우측 상단에 최근 하루 배지, 아래 3지표.
 * 색은 기기 벽지와 무관하게 고정이다 (동적 색을 쓰지 않는다).
 */
@Composable
fun SummaryHeaderCard(
    totalCalories: Int,
    latestDate: String,
    records: List<DailyRecordModel>,
    today: String
) {
    // 7700kcal 기준 예상 체중 변동량 (적자면 감소)
    val expectedWeightChange = -totalCalories / 7700f
    val weightText = if (expectedWeightChange <= 0) {
        String.format(Locale.getDefault(), "%.1f kg", expectedWeightChange)
    } else {
        String.format(Locale.getDefault(), "-%.1f kg", expectedWeightChange)
    }
    val recordDays = records.size
    val avgNet = if (records.isNotEmpty()) records.sumOf { it.netCalories } / records.size else 0

    val net = DnTheme.net
    val onHero = net.onHero
    val latest = records.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DnDimens.RadiusHero),
        colors = CardDefaults.cardColors(containerColor = net.heroBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.total_calories_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = net.mint,
                    modifier = Modifier.weight(1f)
                )
                latest?.let { r ->
                    val label = when (r.date) {
                        today -> stringResource(R.string.hero_badge_today, r.netCalories)
                        shiftDate(today, -1) -> stringResource(R.string.hero_badge_yesterday, r.netCalories)
                        else -> "${getDayLabelText(r.date)} ${String.format(Locale.getDefault(), "%,d", r.netCalories)}"
                    }
                    NetDeltaBadge(text = label, onHero = true)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            KcalValue(
                value = totalCalories,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 46.sp),
                color = onHero,
                withUnit = true,
                unitStyle = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.last_updated, latestDate),
                style = MaterialTheme.typography.labelMedium,
                color = onHero.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(22.dp))
            // 카드 하단에 3개 지표. 세로 구분선 없이 간격으로만 나눈다
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                StatCell(
                    label = stringResource(R.string.stat_est_weight),
                    value = weightText,
                    labelColor = net.mint.copy(alpha = 0.8f),
                    valueColor = onHero,
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = stringResource(R.string.stat_record_days),
                    value = stringResource(R.string.weight_stat_days_value, recordDays),
                    labelColor = net.mint.copy(alpha = 0.8f),
                    valueColor = onHero,
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = stringResource(R.string.stat_avg_net),
                    value = String.format(Locale.getDefault(), "%,d", avgNet),
                    labelColor = net.mint.copy(alpha = 0.8f),
                    valueColor = onHero,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 상단바의 동그란 아이콘 버튼 (옅은 바탕) */
@Composable
private fun RoundIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        content = content
    )
}

/** "yyyy-MM-dd" 에서 days 만큼 더한 날짜. 파싱이 안 되면 그대로 돌려준다 */
private fun shiftDate(date: String, days: Int): String {
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance().apply { time = fmt.parse(date) ?: return date }
        cal.add(Calendar.DAY_OF_MONTH, days)
        fmt.format(cal.time)
    } catch (e: Exception) {
        date
    }
}

/** 추이 차트 막대 영역 높이. 목표선·막대·툴팁이 이 안에서 같은 기준을 본다 */
private val TREND_BAR_AREA = 110.dp
/** 툴팁(숫자) 자리 높이 */
private val TREND_TIP_AREA = 24.dp
/** 날짜 라벨 자리 높이 */
private val TREND_LABEL_AREA = 22.dp
/** 하루 목표 순칼로리. 선 하나로만 쓰여서 상수로 둔다 */
private const val DAILY_GOAL_KCAL = 500

/**
 * 최근 순칼로리 미니 바 차트.
 *
 * 처음에는 이번 달만 보여주고, 왼쪽(과거)으로 끝까지 밀 때마다 한 달치씩 이어 붙인다.
 * 불러오는 단위가 "달"이라 어느 달이든 1일부터 말일까지 통째로 들어온다.
 *
 * 막대 위에 그날 순칼로리를 항상 적는다. 누르면 그날 정산이 열린다.
 * 달이 바뀌는 첫 칸은 요일 대신 "11월 1" 처럼 월을 적어 어느 달인지 보이게 한다.
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
    onLoadOlder: () -> Unit,
    today: String
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

    // 이상치로 막대가 뭉개지지 않도록 90퍼센타일 기준으로 정규화.
    // 목표선(-500)이 항상 영역 안에 들어오도록 최소값도 목표로 잡는다
    val absList = shown.map { kotlin.math.abs(it.netCalories) }.sorted()
    val maxAbs = (
        if (absList.isEmpty()) 1
        else absList[(absList.size * 9 / 10).coerceAtMost(absList.size - 1)]
    ).coerceAtLeast(DAILY_GOAL_KCAL)

    // 최근 7일 평균 (기록이 있는 날 기준)
    val weekAvg = remember(records) {
        val last7 = records.take(7)
        if (last7.isEmpty()) 0 else last7.sumOf { it.netCalories } / last7.size
    }

    val net = DnTheme.net
    val goalLineColor = MaterialTheme.colorScheme.outline
    val monthLineColor = MaterialTheme.colorScheme.outlineVariant
    val chartHeight = TREND_TIP_AREA + TREND_BAR_AREA + TREND_LABEL_AREA

    DnCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        // 헤더: 클릭하면 열기/닫기 (상태는 저장됨)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DnDimens.RadiusChip))
                .clickable {
                    expanded = !expanded
                    prefs.edit { putBoolean(Constants.KEY_TREND_EXPANDED, expanded) }
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.recent_trend),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.trend_week_avg, weekAvg),
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.cd_collapse else R.string.cd_expand
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(10.dp))
            // 목표선 라벨. 선은 아래 Canvas 가 막대 뒤에 긋는다
            Text(
                text = stringResource(R.string.trend_goal, -DAILY_GOAL_KCAL),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))

            val goalFrac = DAILY_GOAL_KCAL.toFloat() / maxAbs
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // 목표선: 막대 영역의 아래에서 goalFrac 만큼 올라온 자리
                    val areaBottom = (TREND_TIP_AREA + TREND_BAR_AREA).toPx()
                    val y = areaBottom - goalFrac * TREND_BAR_AREA.toPx()
                    drawLine(
                        color = goalLineColor.copy(alpha = 0.6f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    )
                }

                // 가로 스크롤: 최신이 오른쪽, 왼쪽으로 드래그하면 과거 날짜까지 확인 (보이는 막대만 렌더 → 가벼움)
                LazyRow(
                    modifier = Modifier.fillMaxSize(),
                    state = scrollState,
                    reverseLayout = true,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    itemsIndexed(shown, key = { _, r -> r.date }) { index, r ->
                        // 달이 바뀌는 자리(그 달의 가장 이른 기록)에만 월 표기를 붙인다.
                        // reverseLayout이라 index+1이 화면 왼쪽(더 과거)이다.
                        val isMonthHead = index == shown.lastIndex ||
                                shown[index + 1].date.take(7) != r.date.take(7)
                        val frac = (kotlin.math.abs(r.netCalories).toFloat() / maxAbs).coerceAtMost(1f)
                        val barHeight = (TREND_BAR_AREA * frac).coerceAtLeast(10.dp)
                        val barColor = net.of(r.netCalories)
                        val isToday = r.date == today
                        val dayColor = weekdayColor(r.date, calendarHolidays, net)

                        Row(verticalAlignment = Alignment.Bottom) {
                            // 달이 바뀌는 지점: 세로선 (왼쪽, 즉 과거 쪽 경계). 가장 오래된 달에는 긋지 않는다
                            if (isMonthHead && index != shown.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(chartHeight)
                                        .background(monthLineColor)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                            }
                            Column(
                                modifier = Modifier
                                    .width(40.dp)
                                    .clip(RoundedCornerShape(DnDimens.RadiusChip))
                                    .clickable { onDayClick(r.date) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                // 막대 위 숫자. 칸(40dp)보다 길어도 잘리지 않게 unbounded 로 그린다.
                                // (처음엔 고른 날에만 말풍선으로 띄웠는데 칸 폭에 잘려 "-1,677" 이 안 보였다)
                                Box(
                                    modifier = Modifier.height(TREND_TIP_AREA),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    Text(
                                        text = String.format(Locale.getDefault(), "%,d", r.netCalories),
                                        style = MaterialTheme.typography.labelSmall.tabular(),
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                                        color = barColor,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .wrapContentWidth(unbounded = true)
                                            .padding(bottom = 3.dp)
                                    )
                                }
                                // 막대. 오늘은 민트 외곽선(바깥 캡슐)으로 표시
                                Box(
                                    modifier = Modifier.height(TREND_BAR_AREA),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    if (isToday) {
                                        Box(
                                            modifier = Modifier
                                                .width(16.dp)
                                                .height(barHeight + 6.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(net.mint),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(10.dp)
                                                    .height(barHeight)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(barColor)
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .width(10.dp)
                                                .height(barHeight)
                                                .clip(RoundedCornerShape(50))
                                                .background(barColor)
                                        )
                                    }
                                }
                                // "28 화" 한 줄. 일요일·공휴일/토요일은 색으로 구분
                                Box(
                                    modifier = Modifier.height(TREND_LABEL_AREA),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    Text(
                                        text = if (isMonthHead) {
                                            getMonthText(r.date) + " " + r.date.takeLast(2).trimStart('0')
                                        } else {
                                            r.date.takeLast(2) + " " + getDayOfWeekText(r.date).trim('(', ')')
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isToday || isMonthHead) FontWeight.Bold else FontWeight.Medium,
                                        color = dayColor
                                            ?: if (isToday) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.wrapContentWidth(unbounded = true)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 정산 기록 한 줄. 카드 대신 행이다 — 좌측 4dp 색 바(적자/흑자), 날짜, 운동 칩, 우측 kcal.
 * 행 사이는 1dp 선으로만 나눈다. 카드 안에 카드가 들어가던 "겹침"을 없애기 위함이다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DailyRecordHistoryItem(
    item: DailyRecordModel,
    showTopLine: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dayOfWeekText = getDayOfWeekText(item.date)
    val net = DnTheme.net
    val color = net.of(item.netCalories)

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showTopLine) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DnDimens.RadiusChip))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 4.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "${monthDayText(item.date)} $dayOfWeekText",
                style = MaterialTheme.typography.titleMedium.tabular(),
                modifier = Modifier.weight(1f)
            )
            if (item.hasExercise) {
                DnChip(text = stringResource(R.string.exercise_chip))
                Spacer(modifier = Modifier.width(12.dp))
            }
            KcalValue(
                value = item.netCalories,
                style = MaterialTheme.typography.titleMedium,
                color = color
            )
        }
    }
}

/** "8. 3" 식 월.일 표기. 목록에서 연도는 월 띠가 이미 말해주므로 뺀다 */
private fun monthDayText(dateString: String): String {
    val parts = dateString.split("-")
    if (parts.size != 3) return dateString
    val m = parts[1].toIntOrNull() ?: return dateString
    val d = parts[2].toIntOrNull() ?: return dateString
    return "$m. $d"
}
