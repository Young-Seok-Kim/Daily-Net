package com.youngs.dailynet.ui.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import com.youngs.dailynet.ui.theme.DnDimens
import com.youngs.dailynet.ui.theme.DnTheme
import com.youngs.dailynet.ui.theme.tabular
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.drawText
import androidx.compose.material3.FilledTonalButton
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * 일별 차트 치수. 눈금 숫자를 막대와 같은 높이에 맞춰야 해서
 * 캔버스와 라벨이 같은 값을 봐야 한다. 한쪽만 고치면 숫자가 선에서 어긋난다.
 */
private val CHART_HEIGHT = 160.dp

/** 눈금선 바깥 여유. 눈금을 넘는 날은 선 위로 삐져나와 "이 정도 넘었다"가 보인다. */
private val CHART_OVERSHOOT = 18.dp

/**
 * 왼쪽 눈금 숫자 자리.
 *
 * 이만큼이 막대에서 빠진다. 하루 칸이 31개라 한 칸이 8dp 남짓으로 줄어드는데,
 * 크기를 못 읽는 막대는 있으나 마나라 눈금 쪽을 택했다.
 */
private val AXIS_LABEL_WIDTH = 44.dp

/**
 * 한 달 정산을 한 화면에 펼쳐 보는 화면.
 *
 * 목록의 월 구분선을 펼친 뒤 [MonthSummaryPanel] 하단 줄을 눌러 들어온다.
 * 인라인 패널과 역할을 나눠 갖는다.
 * - 패널: 스크롤하다 흘깃 보는 핵심 숫자 몇 개
 * - 이 화면: 목록 사이에 낀 패널이 구조적으로 담을 수 없는 것 (그래프, 주차 비교, 하이라이트)
 *
 * 좌우로 넘기면(또는 상단 화살표) 이전·다음 달로 간다. 목록으로 돌아가 다른 달 구분선을
 * 찾아 들어오는 것보다 지난달과 견주기가 훨씬 빠르다. 넘길 수 있는 범위는
 * [monthRangeOf] 참고. 기록이 없는 달은 비어 있다고만 보여준다.
 *
 * @param yearMonth 처음 열 달. "yyyy-MM"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthReportScreen(
    mainViewModel: MainViewModel,
    yearMonth: String,
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit
) {
    val allRecords by mainViewModel.allDailyRecord.collectAsState()

    // 넘길 수 있는 달 목록 (오름차순). 기록이 바뀌어 달이 늘면 아래 key()로 페이저를 다시 만든다.
    val months = remember(allRecords, yearMonth) { monthRangeOf(allRecords, yearMonth) }

    // 목록에서 새로 들어온 것인지, 상세에 갔다 돌아온 것인지.
    //
    // 돌아온 것이면 들어올 때의 달이 아니라 **넘겨서 보던 달**과 그 스크롤 위치를 되살려야 한다.
    // 9월에서 열어 8월로 넘긴 뒤 어떤 날을 눌러 상세에 갔다 오면 8월이 그대로 있어야지,
    // 9월로 튕기면 다시 넘겨야 한다.
    // 새로 들어온 것이면(다른 달 구분선에서 열었으면) 그 달을 맨 위부터 보여준다.
    val freshEntry = mainViewModel.monthReportEntryMonth != yearMonth
    if (freshEntry) {
        mainViewModel.monthReportEntryMonth = yearMonth
        mainViewModel.monthReportShownMonth = yearMonth
        mainViewModel.monthReportScrollStates.clear()
    }
    val shownMonth = mainViewModel.monthReportShownMonth ?: yearMonth

    BackHandler(enabled = true) { onBack() }

    // 달 목록이 바뀌면(기록 로딩·새 달 생김) 페이지 번호가 다른 달을 가리키게 되므로
    // 페이저를 통째로 다시 만들어 보던 달의 새 번호에서 시작한다.
    // 드문 일이라 다시 만드는 비용은 신경 쓰지 않는다. 스크롤 위치는 ViewModel에 있어 살아남는다.
    key(months) {
        val pagerState = rememberPagerState(
            initialPage = months.indexOf(shownMonth).coerceAtLeast(0)
        ) { months.size }
        val scope = rememberCoroutineScope()

        // 넘기기가 끝나 멈춘 달을 기억해 둔다. 제목도 이 값이 아니라 currentPage를 따라
        // 손가락을 따라 바뀌게 둔다 (멈추기 전에 제목이 바뀌어야 어디로 가는지 보인다).
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { page ->
                months.getOrNull(page)?.let { mainViewModel.monthReportShownMonth = it }
            }
        }

        val currentMonth = months.getOrNull(pagerState.currentPage) ?: shownMonth
        val canGoPrev = pagerState.currentPage > 0
        val canGoNext = pagerState.currentPage < months.lastIndex

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = monthTitleOf(currentMonth),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    },
                    actions = {
                        // 손가락으로 넘길 수 있다는 걸 모르는 사용자를 위한 버튼.
                        // 끝 달에서는 흐리게 두어 더 없다는 걸 알린다.
                        IconButton(
                            enabled = canGoPrev,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.cd_prev_month)
                            )
                        }
                        IconButton(
                            enabled = canGoNext,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.cd_next_month)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                // 옆 달을 미리 그려 두면 넘길 때 빈 화면이 잠깐 비치는 일이 없다
                beyondViewportPageCount = 1
            ) { page ->
                val month = months[page]
                MonthReportPage(
                    mainViewModel = mainViewModel,
                    yearMonth = month,
                    allRecords = allRecords,
                    today = mainViewModel.todayDate,
                    // 달마다 스크롤 위치를 따로 둔다. 옆 달로 갔다 돌아와도 보던 자리가 남는다.
                    scrollState = mainViewModel.monthReportScrollStates.getOrPut(month) { ScrollState(0) },
                    onNavigateToDetail = onNavigateToDetail
                )
            }
        }
    }
}

/**
 * 좌우로 넘길 수 있는 달의 범위. "yyyy-MM" 오름차순, 빈 달도 빠뜨리지 않고 이어진다.
 *
 * 가장 오래된 기록이 있는 달부터 이번 달까지다. 이번 달까지 두는 이유는 기록이 한 건도 없는
 * 달 초에도 "이번 달"로 넘어가 비어 있음을 볼 수 있게 하기 위해서다. 미래 달은 볼 게 없어 뺀다.
 * [yearMonth]는 어느 쪽이든 범위에 꼭 들어가야 한다. 안 그러면 처음 연 달이 페이저에 없다.
 */
internal fun monthRangeOf(records: List<DailyRecordModel>, yearMonth: String): List<String> {
    val recordMonths = records.map { it.date.take(7) }.filter { it.length == 7 }
    val thisMonth = Calendar.getInstance().let {
        String.format(Locale.US, "%04d-%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1)
    }
    val first = (recordMonths + yearMonth).min()
    val last = (recordMonths + yearMonth + thisMonth).max()

    val result = mutableListOf<String>()
    var cursor = first
    while (cursor <= last && result.size < 600) { // 50년 상한. 날짜가 깨져도 무한히 돌지 않게
        result += cursor
        cursor = nextMonth(cursor)
    }
    return result
}

/** "yyyy-MM"의 다음 달 */
private fun nextMonth(yearMonth: String): String {
    val year = yearMonth.substring(0, 4).toInt()
    val month = yearMonth.substring(5, 7).toInt()
    return if (month == 12) String.format(Locale.US, "%04d-01", year + 1)
    else String.format(Locale.US, "%04d-%02d", year, month + 1)
}

/**
 * 한 달 분량의 본문. 페이저의 한 페이지다.
 *
 * 선택한 날(selectedDate)은 페이지 안에 둔다. 옆 달로 넘어가면 그 달의 선택은 의미가 없다.
 */
@Composable
private fun MonthReportPage(
    mainViewModel: MainViewModel,
    yearMonth: String,
    allRecords: List<DailyRecordModel>,
    today: String,
    scrollState: ScrollState,
    onNavigateToDetail: (String) -> Unit
) {
    val userProfile by mainViewModel.userProfile.collectAsState()

    // date가 고정폭이라 앞 7글자가 곧 그 달이다. (최신순 DESC 유지)
    val records = remember(allRecords, yearMonth) {
        allRecords.filter { it.date.take(7) == yearMonth }
    }
    val summary = remember(records, allRecords, userProfile) {
        val fallbackWeight = allRecords.firstOrNull { it.weight > 0f }?.weight
            ?: userProfile?.initialWeight
            ?: 0f
        buildPeriodSummary(records, fallbackWeight)
    }

    // 한 번 누르면 선택(위에 정보 표시), 같은 날을 한 번 더 누르면 그날 정산으로 이동.
    // 하루 칸이 10dp 남짓이라 바로 이동시키면 옆 날짜를 잘못 열기 쉽다.
    // 몸무게 추이 화면과 같은 방식이라 사용자에게도 낯설지 않다.
    var selectedDate by remember { mutableStateOf<String?>(null) }

    if (records.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.month_report_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 이번 달이 비어 있으면 바로 기록하러 갈 수 있게 한다. 지난 달은 채울 수 없으니 글만 둔다
                if (today.take(7) == yearMonth) {
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(onClick = { onNavigateToDetail(today) }) {
                        Text(stringResource(R.string.month_report_go_record))
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        MonthHeadlineCard(summary)

        // 목록의 월 패널과 같은 평가(같은 저장 키)다. 여기서 받으면 목록에서도 바로 보인다.
        PeriodEvaluationSection(
            mainViewModel = mainViewModel,
            kind = PeriodKind.MONTH,
            label = yearMonth,
            title = monthTitleOf(yearMonth),
            records = records
        )

        DailyNetChartCard(
            yearMonth = yearMonth,
            records = records,
            selectedDate = selectedDate,
            onDayClick = { date ->
                if (selectedDate == date) onNavigateToDetail(date) else selectedDate = date
            }
        )

        WeeklyCompareCard(records)

        MonthWeightCard(records)

        HighlightCard(records = records, summary = summary, onOpenDay = onNavigateToDetail)

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 화면 제목 "2026년 8월". 그 달 1일을 만들어 기존 표기 함수를 그대로 쓴다. */
private fun monthTitleOf(yearMonth: String): String = getMonthHeaderText("$yearMonth-01")

/**
 * 맨 위 큰 숫자 카드: 합계 / 체중 변화 / 기록·운동 일수·평균.
 * 주·연 정산 상세(PeriodReportScreen)도 같은 카드를 쓴다. 다른 건 합계 라벨뿐이다.
 */
@Composable
internal fun MonthHeadlineCard(
    summary: PeriodSummary,
    totalLabel: String = stringResource(R.string.month_summary_total)
) {
    val net = DnTheme.net
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DnDimens.RadiusHero),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = totalLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    KcalValue(
                        value = summary.netTotal,
                        style = MaterialTheme.typography.displaySmall,
                        withUnit = true,
                        unitStyle = MaterialTheme.typography.titleMedium
                    )
                }
                summary.weightDiff?.let { diff ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = String.format(Locale.getDefault(), "%+.1f kg", diff),
                            style = MaterialTheme.typography.titleLarge.tabular(),
                            color = net.of(diff)
                        )
                        Text(
                            text = stringResource(R.string.month_summary_weight),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatCell(
                    label = stringResource(R.string.stat_record_days),
                    value = stringResource(R.string.weight_stat_days_value, summary.recordDays),
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = stringResource(R.string.month_report_exercise_days),
                    value = stringResource(R.string.weight_stat_days_value, summary.exerciseDays),
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = stringResource(R.string.stat_avg_net),
                    value = String.format(Locale.getDefault(), "%,d", summary.netAverage),
                    valueColor = net.of(summary.netAverage),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 1일부터 말일까지 자리를 고정한 일별 막대.
 *
 * 메인 화면의 추이 차트는 "기록이 있는 날만 이어 붙인 흐름"이라 빠진 날이 보이지 않는다.
 * 여기서는 날짜마다 자리를 미리 잡아두기 때문에 **기록하지 않은 날이 빈칸으로 남는다.**
 * 그게 이 화면에서 가장 보고 싶은 것이라 가로 스크롤 없이 한 화면에 다 넣었다.
 *
 * 0을 기준선으로 두고 적자는 아래, 흑자는 위로 그린다.
 * 30개 막대에 숫자를 다 적을 수는 없어서, 위아래 방향이 곧 그 달의 균형을 읽는 수단이 된다.
 */
@Composable
private fun DailyNetChartCard(
    yearMonth: String,
    records: List<DailyRecordModel>,
    selectedDate: String?,
    onDayClick: (String) -> Unit
) {
    // 이번 달이면 오늘까지만 그린다.
    // 아직 오지 않은 날까지 빈칸으로 두면 "기록을 빠뜨린 날"처럼 보인다.
    val days = remember(yearMonth, records) { daysToDraw(yearMonth) }
    val byDay = remember(records) { records.associateBy { it.date } }

    // 눈금 기준값.
    // 이상치 하나가 나머지를 다 눕히지 않도록 90퍼센타일을 잡고(메인 차트와 같은 규칙),
    // 거기서 딱 떨어지는 수로 올린다. 눈금에 "1,000" 대신 "1,137"이 적히면 읽을 수가 없다.
    val axisMax = remember(records) {
        val sorted = records.map { kotlin.math.abs(it.netCalories) }.sorted()
        val p90 = if (sorted.isEmpty()) 0
        else sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]
        niceCeil(p90)
    }

    val net = DnTheme.net
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.onSurface
    val emptyColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val highlight = MaterialTheme.colorScheme.primary
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    val deficitDays = records.count { it.netCalories <= 0 }
    val surplusDays = records.size - deficitDays

    DnCard {
        SectionHeader(title = stringResource(R.string.month_report_daily_title)) {
            Text(
                text = stringResource(R.string.month_report_balance_days, deficitDays, surplusDays),
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 선택한 날 툴팁. 아무것도 안 골랐을 때도 높이가 변하지 않도록 안내 문구로 자리를 채운다.
        SelectedDayBar(record = selectedDate?.let { byDay[it] }, selectedDate = selectedDate)

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            // 왼쪽 눈금 숫자. 캔버스가 선을 그리는 높이와 같은 dp를 써서 맞춘다.
            Box(
                modifier = Modifier
                    .width(AXIS_LABEL_WIDTH)
                    .height(CHART_HEIGHT)
            ) {
                AxisLabel(
                    text = String.format(Locale.getDefault(), "%,+d", axisMax),
                    y = CHART_OVERSHOOT,
                    color = axisTextColor
                )
                AxisLabel(text = "0", y = CHART_HEIGHT / 2, color = axisTextColor)
                AxisLabel(
                    text = String.format(Locale.getDefault(), "%,d", -axisMax),
                    y = CHART_HEIGHT - CHART_OVERSHOOT,
                    color = axisTextColor
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(CHART_HEIGHT)
            ) {
                // 눈금선은 막대 뒤에 한 번만 그린다. 날짜 칸마다 그리면 선이 끊겨 보인다.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val zeroY = size.height / 2f
                    val half = zeroY - CHART_OVERSHOOT.toPx()
                    listOf(zeroY - half, zeroY + half).forEach { y ->
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                        )
                    }
                    // 0선은 기준이라 굵고 또렷하게
                    drawLine(
                        color = zeroColor,
                        start = Offset(0f, zeroY),
                        end = Offset(size.width, zeroY),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    days.forEachIndexed { i, date ->
                        val r = byDay[date]
                        val isSelected = date == selectedDate
                        // 주가 시작하는 날(월요일) 앞에 틈을 둬 주 단위가 읽히게 한다
                        if (i > 0 && isMonday(date)) Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (isSelected) highlight.copy(alpha = 0.12f)
                                    else Color.Transparent
                                )
                                .clickable { onDayClick(date) }
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val zeroY = size.height / 2f
                                val half = zeroY - CHART_OVERSHOOT.toPx()
                                val barW = (w * 0.7f).coerceAtMost(6.dp.toPx())
                                val left = (w - barW) / 2f

                                if (r == null) {
                                    // 기록이 없는 날: 기준선 위 옅은 점만 남겨 "빈 자리"임을 보인다
                                    drawCircle(
                                        color = emptyColor,
                                        radius = 1.5.dp.toPx(),
                                        center = Offset(w / 2f, zeroY)
                                    )
                                    return@Canvas
                                }

                                val netValue = r.netCalories
                                // 눈금을 넘는 날은 선 위로 넘치되 카드 밖으로는 나가지 않게 막는다
                                val barH = (kotlin.math.abs(netValue).toFloat() / axisMax * half)
                                    .coerceIn(3.dp.toPx(), zeroY)
                                // 적자(음수)는 아래로, 흑자(양수)는 위로
                                val top = if (netValue <= 0) zeroY else zeroY - barH
                                drawRoundRect(
                                    color = net.of(netValue),
                                    topLeft = Offset(left, top),
                                    size = androidx.compose.ui.geometry.Size(barW, barH),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                                )
                            }
                        }
                    }
                }
            }
        }

        // 날짜 눈금. 하루 칸이 좁아 전부 적을 수 없어서 1일·5일 간격·말일만 적는다.
        // 왼쪽 눈금 숫자 자리만큼 밀어야 막대와 세로줄이 맞는다.
        val tickDays = remember(days) { tickDaysOf(days) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AXIS_LABEL_WIDTH, top = 6.dp)
        ) {
            days.forEachIndexed { i, date ->
                val day = date.takeLast(2).toIntOrNull() ?: 0
                if (i > 0 && isMonday(date)) Spacer(modifier = Modifier.width(5.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (day in tickDays) {
                        Text(
                            text = "$day",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            // 한 칸이 8dp 남짓이라 두 자리 숫자가 잘린다.
                            // unbounded로 칸 밖까지 그리게 해서 막대와의 정렬은 지키고 글자는 살린다.
                            modifier = Modifier.wrapContentWidth(unbounded = true)
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.month_report_daily_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

/** "yyyy-MM-dd"가 월요일인지. 주 시작 틈을 둘 때 쓴다 */
private fun isMonday(date: String): Boolean {
    val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date) ?: return false
    val cal = Calendar.getInstance().apply { time = parsed }
    return cal.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY
}

/**
 * 왼쪽 눈금 숫자 한 개. [y]는 눈금선이 그어진 높이 — 글자를 그 선 가운데에 맞춘다.
 *
 * 부호(+/−)까지 적어서 이 숫자만으로 위아래 방향이 읽힌다. 그래서 색 범례를 따로 두지 않는다.
 */
@Composable
private fun BoxScope.AxisLabel(text: String, y: Dp, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(y = y - 8.dp)
            .padding(end = 4.dp)
    )
}

/**
 * x축에 숫자를 적을 날짜를 고른다. 1일, 5일 간격, 그리고 마지막 날.
 *
 * 마지막 날은 직전 눈금과 3일 이상 떨어져 있을 때만 적는다.
 * 31일 달이면 30과 31이 8dp 간격으로 나란히 붙어 글자가 겹친다.
 */
private fun tickDaysOf(days: List<String>): Set<Int> {
    val numbers = days.mapNotNull { it.takeLast(2).toIntOrNull() }
    if (numbers.isEmpty()) return emptySet()

    val ticks = numbers.filter { it == 1 || it % 5 == 0 }.toMutableSet()
    val last = numbers.last()
    if (ticks.none { last - it < 3 }) ticks.add(last)
    return ticks
}

/**
 * 눈금에 적히기 좋은 수로 올린다. 1,137 → 1,500.
 * 사람이 한눈에 읽는 건 딱 떨어지는 수뿐이다.
 */
private fun niceCeil(value: Int): Int {
    if (value <= 0) return 500
    intArrayOf(100, 200, 300, 500, 800, 1000, 1500, 2000, 2500, 3000, 4000, 5000)
        .forEach { if (value <= it) return it }
    // 5,000을 넘는 값은 1,000 단위로만 올린다
    return (value + 999) / 1000 * 1000
}

/**
 * 그날 정보 pill (짙은 바탕에 흰 글자). 고른 날에 기록이 없으면 "기록 없음"으로 알린다.
 * 아무것도 안 골랐을 때는 옅은 안내 pill 로 같은 높이를 차지한다.
 */
@Composable
private fun SelectedDayBar(record: DailyRecordModel?, selectedDate: String?) {
    val net = DnTheme.net
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selectedDate == null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = stringResource(R.string.month_report_tap_day),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            return@Box
        }

        val dateText = "${getDayLabelText(selectedDate)} ${getDayOfWeekText(selectedDate)}".trim()
        val body = when {
            record == null -> "$dateText · ${stringResource(R.string.month_report_no_record)}"
            record.hasExercise -> "$dateText · ${String.format(Locale.getDefault(), "%,d kcal", record.netCalories)} · ${stringResource(R.string.exercise_chip)}"
            else -> "$dateText · ${String.format(Locale.getDefault(), "%,d kcal", record.netCalories)}"
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = net.heroBackground
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    color = net.onHero,
                    maxLines = 1
                )
                if (record != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.weight_trend_tap_again),
                        style = MaterialTheme.typography.labelSmall,
                        color = net.onHero.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 그 달 안에서 주차끼리 비교.
 *
 * 주차를 나누는 기준은 목록의 주차 구분선과 같은 [getWeekIdentifier]를 쓴다.
 *
 * 다만 합계는 **이 달에 속한 날만** 더한다. 달을 넘나드는 주(예: 7/30~8/5)는
 * 목록의 주차 구분선이 보여주는 값보다 작게 나온다.
 * 여기는 월 정산이라 주차 합이 월 합계와 맞아떨어지는 쪽이 맞다.
 * 한 주를 통째로 보려면 목록의 그 주차 구분선을 펼치면 된다.
 */
@Composable
private fun WeeklyCompareCard(records: List<DailyRecordModel>) {
    val net = DnTheme.net
    // 주차 → (그 주의 이 달 안 첫날, 마지막 날, 순칼로리 합). 오래된 주가 위로 오도록 날짜순으로 세운다.
    val weeks = remember(records) {
        records.groupBy { getWeekIdentifier(it.date) }
            .entries
            .sortedBy { it.value.minOf { r -> r.date } }
            .map { (_, rows) ->
                val oldest = rows.minOf { it.date }
                val newest = rows.maxOf { it.date }
                Triple(oldest, newest, rows.sumOf { it.netCalories })
            }
    }
    if (weeks.isEmpty()) return

    val maxAbs = weeks.maxOf { kotlin.math.abs(it.third) }.coerceAtLeast(1)

    DnCard {
        SectionHeader(title = stringResource(R.string.month_report_weekly_title))
        Spacer(modifier = Modifier.height(6.dp))
        weeks.forEach { (from, to, netValue) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // "7/1–7/7" 처럼 실제 기간. "7월 1주" 는 어느 날부터인지 알 수 없었다
                Text(
                    text = "${shortMonthDay(from)}–${shortMonthDay(to)}",
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(84.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(DnDimens.BarThick)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(kotlin.math.abs(netValue).toFloat() / maxAbs)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (netValue <= 0) net.mint else net.coral)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = String.format(Locale.getDefault(), "%,d", netValue),
                    style = MaterialTheme.typography.titleSmall.tabular(),
                    color = net.of(netValue),
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(60.dp)
                )
            }
        }
    }
}

/** "7/1" 식 짧은 월/일 */
private fun shortMonthDay(date: String): String {
    val parts = date.split("-")
    if (parts.size != 3) return date
    return "${parts[1].toIntOrNull() ?: parts[1]}/${parts[2].toIntOrNull() ?: parts[2]}"
}

/**
 * 그 달 체중 곡선.
 *
 * x축은 날짜 위치 그대로다. 기록이 드문 구간은 점 사이가 넓게 벌어지는데,
 * 그게 실제로 그 기간에 잰 적이 없다는 뜻이라 균등 간격으로 펴지 않는다.
 */
@Composable
private fun MonthWeightCard(records: List<DailyRecordModel>) {
    val net = DnTheme.net
    val points = remember(records) {
        records.filter { it.weight > 0f }.sortedBy { it.date }
    }
    val diff = if (points.size >= 2) points.last().weight - points.first().weight else null

    DnCard {
        SectionHeader(title = stringResource(R.string.month_report_weight_title)) {
            diff?.let {
                Text(
                    text = String.format(Locale.getDefault(), "%+.1f kg", it),
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    color = net.of(it)
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (points.size < 2) {
            Text(
                text = stringResource(R.string.month_report_weight_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@DnCard
        }

        // 눈금 범위. 위·아래 선에 실제 최고·최저 체중이 걸리도록 잡는다.
        // 폭이 너무 좁으면(하루 사이 0.1kg 같은) 곡선이 위아래로 요동쳐 보이므로 최소 0.5kg은 벌려둔다.
        val minWeight = points.minOf { it.weight }
        val maxWeight = points.maxOf { it.weight }
        val center = (minWeight + maxWeight) / 2f
        val half = ((maxWeight - minWeight) / 2f).coerceAtLeast(0.25f)
        val axisTop = center + half
        val axisBottom = center - half

        val firstDay = points.first().date.takeLast(2).toInt()
        val lastDay = points.last().date.takeLast(2).toInt()
        val daySpan = (lastDay - firstDay).coerceAtLeast(1)

        val lineColor = net.deficit
        val dotFill = MaterialTheme.colorScheme.surface
        val gridColor = MaterialTheme.colorScheme.outlineVariant
        val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        val maxIdx = points.indexOfFirst { it.weight == maxWeight }
        val minIdx = points.indexOfLast { it.weight == minWeight }

        Row(modifier = Modifier.fillMaxWidth()) {
            // 왼쪽 눈금 숫자. 일별 차트와 같은 자리·같은 방식이라 두 그래프를 같은 눈으로 읽는다.
            // 위·아래 선에 실제 최고·최저 체중이 걸린다
            Box(
                modifier = Modifier
                    .width(AXIS_LABEL_WIDTH)
                    .height(CHART_HEIGHT)
            ) {
                AxisLabel(
                    text = String.format(Locale.getDefault(), "%.1f", axisTop),
                    y = CHART_OVERSHOOT,
                    color = axisTextColor
                )
                AxisLabel(
                    text = String.format(Locale.getDefault(), "%.1f", center),
                    y = CHART_HEIGHT / 2,
                    color = axisTextColor
                )
                AxisLabel(
                    text = String.format(Locale.getDefault(), "%.1f", axisBottom),
                    y = CHART_HEIGHT - CHART_OVERSHOOT,
                    color = axisTextColor
                )
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(CHART_HEIGHT)
            ) {
                val w = size.width
                val topY = CHART_OVERSHOOT.toPx()
                val bottomY = size.height - CHART_OVERSHOOT.toPx()
                // 양 끝 점이 잘리지 않게 좌우 6dp 안쪽에서 그린다
                val inset = 6.dp.toPx()

                fun px(day: Int) = inset + (day - firstDay).toFloat() / daySpan * (w - inset * 2)
                fun py(kg: Float) =
                    bottomY - (kg - axisBottom) / (axisTop - axisBottom) * (bottomY - topY)

                // 눈금선 세 줄 (왼쪽 숫자와 같은 높이)
                val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                listOf(topY, (topY + bottomY) / 2f, bottomY).forEach { y ->
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash
                    )
                }

                val coords = points.map { p -> Offset(px(p.date.takeLast(2).toInt()), py(p.weight)) }

                // 선 아래 민트 그라데이션 영역
                val area = Path().apply {
                    moveTo(coords.first().x, bottomY)
                    coords.forEach { lineTo(it.x, it.y) }
                    lineTo(coords.last().x, bottomY)
                    close()
                }
                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        colors = listOf(net.mint.copy(alpha = 0.35f), net.mint.copy(alpha = 0f)),
                        startY = coords.minOf { it.y },
                        endY = bottomY
                    )
                )

                val line = Path().apply {
                    moveTo(coords.first().x, coords.first().y)
                    coords.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = line,
                    color = lineColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                )

                // 점은 최고·최저·마지막에만. 값은 왼쪽 눈금으로 읽는다 (점마다 숫자를 찍으면 한 달치는 겹쳐서 못 읽는다)
                fun dot(c: Offset, filled: Boolean) {
                    drawCircle(color = if (filled) lineColor else dotFill, radius = 4.dp.toPx(), center = c)
                    drawCircle(color = lineColor, radius = 4.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
                }
                val lastIdx = coords.lastIndex
                if (maxIdx >= 0) dot(coords[maxIdx], filled = false)
                if (minIdx >= 0 && minIdx != maxIdx) dot(coords[minIdx], filled = false)
                if (lastIdx != maxIdx && lastIdx != minIdx) dot(coords[lastIdx], filled = true)
            }
        }

        // 양 끝 날짜. 눈금 숫자 자리만큼 밀어야 곡선과 맞는다
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AXIS_LABEL_WIDTH, top = 6.dp)
        ) {
            Text(
                text = shortMonthDay(points.first().date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = shortMonthDay(points.last().date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 이 달의 기록: 가장 적자가 큰 날 / 운동한 날 수 / 가장 흑자가 큰 날 — KPI 타일 세 개 */
@Composable
private fun HighlightCard(
    records: List<DailyRecordModel>,
    summary: PeriodSummary,
    onOpenDay: (String) -> Unit
) {
    val net = DnTheme.net
    val best = remember(records) { records.minByOrNull { it.netCalories } }
    val worst = remember(records) { records.maxByOrNull { it.netCalories } }

    DnCard {
        SectionHeader(title = stringResource(R.string.month_report_highlight_title))
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            best?.let {
                KpiTile(
                    label = stringResource(R.string.month_report_best),
                    value = String.format(Locale.getDefault(), "%,d", it.netCalories),
                    sub = "${getDayLabelText(it.date)} ${getDayOfWeekText(it.date)}".trim(),
                    valueColor = net.of(it.netCalories),
                    onClick = { onOpenDay(it.date) },
                    modifier = Modifier.weight(1f)
                )
            }
            KpiTile(
                label = stringResource(R.string.month_report_exercise_days),
                value = stringResource(R.string.weight_stat_days_value, summary.exerciseDays),
                sub = stringResource(R.string.month_report_of_days, summary.recordDays),
                valueColor = MaterialTheme.colorScheme.onSurface,
                onClick = null,
                modifier = Modifier.weight(1f)
            )
            // 전부 적자인 달이면 최고와 최악이 같은 날이 된다. 같은 타일을 두 번 보여줄 이유가 없다.
            if (worst != null && worst.date != best?.date) {
                KpiTile(
                    label = stringResource(R.string.month_report_worst),
                    value = String.format(Locale.getDefault(), "%,d", worst.netCalories),
                    sub = "${getDayLabelText(worst.date)} ${getDayOfWeekText(worst.date)}".trim(),
                    valueColor = net.of(worst.netCalories),
                    onClick = { onOpenDay(worst.date) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun KpiTile(
    label: String,
    value: String,
    sub: String,
    valueColor: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(DnDimens.RadiusTile))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.tabular(),
            color = valueColor,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * 그릴 날짜 목록 ("yyyy-MM-01" ~ 말일).
 *
 * 이번 달이면 오늘까지만 돌려준다. 아직 오지 않은 날을 빈칸으로 두면
 * 기록을 빠뜨린 날과 구분이 안 된다.
 */
private fun daysToDraw(yearMonth: String): List<String> {
    val year = yearMonth.substring(0, 4).toIntOrNull() ?: return emptyList()
    val month = yearMonth.substring(5, 7).toIntOrNull() ?: return emptyList()

    val cal = Calendar.getInstance()
    val isThisMonth = cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) + 1 == month
    val today = cal.get(Calendar.DAY_OF_MONTH)

    cal.set(year, month - 1, 1)
    val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val until = if (isThisMonth) minOf(today, lastDay) else lastDay

    return (1..until).map { String.format(Locale.getDefault(), "%s-%02d", yearMonth, it) }
}
