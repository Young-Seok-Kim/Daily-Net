package com.youngs.dailynet.ui.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import java.util.Calendar
import java.util.Locale

/** 적자(감량 방향)는 초록, 흑자는 빨강 — 앱 전체 규칙 */
private val GOOD = Color(0xFF4CAF50)
private val BAD = Color(0xFFF44336)

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
private val AXIS_LABEL_WIDTH = 38.dp

/**
 * 한 달 정산을 한 화면에 펼쳐 보는 화면.
 *
 * 목록의 월 구분선을 펼친 뒤 [MonthSummaryPanel] 하단 줄을 눌러 들어온다.
 * 인라인 패널과 역할을 나눠 갖는다.
 * - 패널: 스크롤하다 흘깃 보는 핵심 숫자 몇 개
 * - 이 화면: 목록 사이에 낀 패널이 구조적으로 담을 수 없는 것 (그래프, 주차 비교, 하이라이트)
 *
 * @param yearMonth "yyyy-MM"
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

    // 스크롤 위치는 ViewModel에 둔다. 여기서 어떤 날을 눌러 상세로 갔다 돌아와도
    // 보던 자리가 그대로 남도록.
    val scrollState = mainViewModel.monthReportScrollState

    // 다른 달을 열었을 때만 맨 위로 되돌린다.
    // 매번 되돌리면 상세에 갔다 올 때마다 보던 자리가 날아간다.
    LaunchedEffect(yearMonth) {
        if (mainViewModel.monthReportScrolledMonth != yearMonth) {
            scrollState.scrollTo(0)
            mainViewModel.monthReportScrolledMonth = yearMonth
        }
    }

    BackHandler(enabled = true) { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(monthTitleOf(yearMonth)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.month_report_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            MonthHeadlineCard(summary)

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
}

/** 화면 제목 "2026년 8월". 그 달 1일을 만들어 기존 표기 함수를 그대로 쓴다. */
private fun monthTitleOf(yearMonth: String): String = getMonthHeaderText("$yearMonth-01")

/** 맨 위 큰 숫자 카드: 월 합계 / 하루 평균 / 체중 변화 / 기록·운동 일수 */
@Composable
private fun MonthHeadlineCard(summary: PeriodSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column {
                    Text(
                        text = String.format(Locale.getDefault(), "%,d kcal", summary.netTotal),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (summary.netTotal <= 0) GOOD else BAD
                    )
                    Text(
                        text = stringResource(R.string.month_summary_total),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                summary.weightDiff?.let { diff ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = String.format(Locale.getDefault(), "%+.1f kg", diff),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (diff <= 0f) GOOD else BAD
                        )
                        Text(
                            text = stringResource(R.string.month_summary_weight),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                WeightStatCell(
                    stringResource(R.string.stat_record_days),
                    stringResource(R.string.weight_stat_days_value, summary.recordDays),
                    Modifier.weight(1f)
                )
                WeightStatCell(
                    stringResource(R.string.month_report_exercise_days),
                    stringResource(R.string.weight_stat_days_value, summary.exerciseDays),
                    Modifier.weight(1f)
                )
                WeightStatCell(
                    stringResource(R.string.stat_avg_net),
                    String.format(Locale.getDefault(), "%,d", summary.netAverage),
                    Modifier.weight(1f),
                    valueColor = if (summary.netAverage <= 0) GOOD else BAD
                )
            }
        }
    }
}

@Composable
private fun WeightStatCell(
    label: String,
    value: String,
    modifier: Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
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

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val emptyColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val highlight = MaterialTheme.colorScheme.primary
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    SectionCard(title = stringResource(R.string.month_report_daily_title)) {
        // 선택한 날 정보. 아무것도 안 골랐을 때도 높이가 변하지 않도록 안내 문구로 자리를 채운다.
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
                            color = gridColor.copy(alpha = 0.6f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    // 0선은 기준이라 더 또렷하게
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, zeroY),
                        end = Offset(size.width, zeroY),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    days.forEach { date ->
                        val r = byDay[date]
                        val isSelected = date == selectedDate
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (isSelected) highlight.copy(alpha = 0.18f)
                                    else Color.Transparent
                                )
                                .clickable { onDayClick(date) }
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val zeroY = size.height / 2f
                                val half = zeroY - CHART_OVERSHOOT.toPx()
                                val barW = (w * 0.6f).coerceAtMost(8.dp.toPx())
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

                                val net = r.netCalories
                                // 눈금을 넘는 날은 선 위로 넘치되 카드 밖으로는 나가지 않게 막는다
                                val barH = (kotlin.math.abs(net).toFloat() / axisMax * half)
                                    .coerceIn(2.dp.toPx(), zeroY)
                                // 적자(음수)는 아래로, 흑자(양수)는 위로
                                val top = if (net <= 0) zeroY else zeroY - barH
                                drawRoundRect(
                                    color = if (net <= 0) GOOD else BAD,
                                    topLeft = Offset(left, top),
                                    size = androidx.compose.ui.geometry.Size(barW, barH),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f)
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
                .padding(start = AXIS_LABEL_WIDTH)
        ) {
            days.forEach { date ->
                val day = date.takeLast(2).toIntOrNull() ?: 0
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
            modifier = Modifier.padding(top = 8.dp)
        )
    }
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

/** 그날 정보 줄. 고른 날에 기록이 없으면 "기록 없음"으로 알린다. */
@Composable
private fun SelectedDayBar(record: DailyRecordModel?, selectedDate: String?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selectedDate != null) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectedDate == null) {
                Text(
                    text = stringResource(R.string.month_report_tap_day),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Row
            }

            Text(
                text = "${getDayLabelText(selectedDate)} ${getDayOfWeekText(selectedDate)}".trim(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (record == null) {
                Text(
                    text = stringResource(R.string.month_report_no_record),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            } else {
                Text(
                    text = String.format(Locale.getDefault(), "%,d kcal", record.netCalories),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (record.netCalories <= 0) GOOD else BAD
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.weight_trend_tap_again),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
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
    val context = androidx.compose.ui.platform.LocalContext.current
    // 주차 → (표기, 순칼로리 합). 오래된 주가 위로 오도록 날짜순으로 세운다.
    val weeks = remember(records) {
        records.groupBy { getWeekIdentifier(it.date) }
            .entries
            .sortedBy { it.value.minOf { r -> r.date } }
            .map { (_, rows) ->
                val oldest = rows.minByOrNull { it.date }?.date ?: ""
                oldest to rows.sumOf { it.netCalories }
            }
    }
    if (weeks.isEmpty()) return

    val maxAbs = weeks.maxOf { kotlin.math.abs(it.second) }.coerceAtLeast(1)

    SectionCard(title = stringResource(R.string.month_report_weekly_title)) {
        weeks.forEach { (dateInWeek, net) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = getWeekOfMonthText(context, dateInWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(88.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(kotlin.math.abs(net).toFloat() / maxAbs)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (net <= 0) GOOD else BAD)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format(Locale.getDefault(), "%,d", net),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (net <= 0) GOOD else BAD,
                    maxLines = 1,
                    modifier = Modifier.width(56.dp)
                )
            }
        }
    }
}

/**
 * 그 달 체중 곡선.
 *
 * x축은 날짜 위치 그대로다. 기록이 드문 구간은 점 사이가 넓게 벌어지는데,
 * 그게 실제로 그 기간에 잰 적이 없다는 뜻이라 균등 간격으로 펴지 않는다.
 */
@Composable
private fun MonthWeightCard(records: List<DailyRecordModel>) {
    val points = remember(records) {
        records.filter { it.weight > 0f }.sortedBy { it.date }
    }

    SectionCard(title = stringResource(R.string.month_report_weight_title)) {
        if (points.size < 2) {
            Text(
                text = stringResource(R.string.month_report_weight_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }

        // 눈금 범위. 위·아래 선에 실제 최고·최저 체중이 걸리도록 잡는다.
        //
        // 폭이 너무 좁으면(하루 사이 0.1kg 같은) 곡선이 위아래로 요동쳐 보이므로
        // 최소 0.5kg은 벌려둔다. 그 경우 눈금 숫자도 벌어진 값으로 적어야
        // "선에 걸린 값"과 "적힌 값"이 어긋나지 않는다.
        val minWeight = points.minOf { it.weight }
        val maxWeight = points.maxOf { it.weight }
        val center = (minWeight + maxWeight) / 2f
        val half = ((maxWeight - minWeight) / 2f).coerceAtLeast(0.25f)
        val axisTop = center + half
        val axisBottom = center - half

        val firstDay = points.first().date.takeLast(2).toInt()
        val lastDay = points.last().date.takeLast(2).toInt()
        val daySpan = (lastDay - firstDay).coerceAtLeast(1)

        val lineColor = MaterialTheme.colorScheme.primary
        val dotFill = MaterialTheme.colorScheme.surface
        val gridColor = MaterialTheme.colorScheme.outlineVariant
        val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant

        Row(modifier = Modifier.fillMaxWidth()) {
            // 왼쪽 눈금 숫자. 일별 차트와 같은 자리·같은 방식이라 두 그래프를 같은 눈으로 읽는다.
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

                fun px(day: Int) = (day - firstDay).toFloat() / daySpan * w
                fun py(kg: Float) =
                    bottomY - (kg - axisBottom) / (axisTop - axisBottom) * (bottomY - topY)

                // 눈금선 세 줄. 가운데는 기준이라 조금 더 또렷하게.
                listOf(topY, bottomY).forEach { y ->
                    drawLine(
                        color = gridColor.copy(alpha = 0.6f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                drawLine(
                    color = gridColor,
                    start = Offset(0f, (topY + bottomY) / 2f),
                    end = Offset(w, (topY + bottomY) / 2f),
                    strokeWidth = 1.dp.toPx()
                )

                var prev: Offset? = null
                points.forEach { p ->
                    val here = Offset(px(p.date.takeLast(2).toInt()), py(p.weight))
                    prev?.let {
                        drawLine(
                            color = lineColor,
                            start = it,
                            end = here,
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                    prev = here
                }
                points.forEach { p ->
                    val c = Offset(px(p.date.takeLast(2).toInt()), py(p.weight))
                    drawCircle(color = dotFill, radius = 3.5.dp.toPx(), center = c)
                    drawCircle(
                        color = lineColor,
                        radius = 3.5.dp.toPx(),
                        center = c,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        // 위 일별 차트의 눈금과 같은 형식(숫자만). 눈금 숫자 자리만큼 밀어야 곡선과 맞는다.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AXIS_LABEL_WIDTH)
        ) {
            Text(
                text = "$firstDay",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$lastDay",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 가장 적자가 큰 날 / 가장 흑자가 큰 날 / 운동한 날 수 */
@Composable
private fun HighlightCard(
    records: List<DailyRecordModel>,
    summary: PeriodSummary,
    onOpenDay: (String) -> Unit
) {
    val best = remember(records) { records.minByOrNull { it.netCalories } }
    val worst = remember(records) { records.maxByOrNull { it.netCalories } }

    SectionCard(title = stringResource(R.string.month_report_highlight_title)) {
        best?.let {
            HighlightRow(
                label = stringResource(R.string.month_report_best),
                day = it.date,
                value = String.format(Locale.getDefault(), "%,d kcal", it.netCalories),
                valueColor = GOOD,
                onClick = { onOpenDay(it.date) }
            )
        }
        // 전부 적자인 달이면 최고와 최악이 같은 날이 된다. 같은 줄을 두 번 보여줄 이유가 없다.
        if (worst != null && worst.date != best?.date) {
            HighlightRow(
                label = stringResource(R.string.month_report_worst),
                day = worst.date,
                value = String.format(Locale.getDefault(), "%,d kcal", worst.netCalories),
                valueColor = if (worst.netCalories <= 0) GOOD else BAD,
                onClick = { onOpenDay(worst.date) }
            )
        }
        HighlightRow(
            label = stringResource(R.string.month_report_exercise_days),
            day = null,
            value = stringResource(R.string.weight_stat_days_value, summary.exerciseDays),
            valueColor = MaterialTheme.colorScheme.onSurface,
            onClick = null
        )
    }
}

@Composable
private fun HighlightRow(
    label: String,
    day: String?,
    value: String,
    valueColor: Color,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        day?.let {
            Text(
                text = "${getDayLabelText(it)} ${getDayOfWeekText(it)}".trim(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

/** 제목 + 내용 한 덩어리. 이 화면의 카드들이 같은 모양을 갖도록 묶어둔다. */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
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
