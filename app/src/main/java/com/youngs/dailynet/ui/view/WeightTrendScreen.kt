package com.youngs.dailynet.ui.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.data.model.DailyRecordModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import java.util.Locale

/**
 * 몸무게가 "이전 날짜 대비 바뀐 날"만 골라낸다.
 *
 * 같은 몸무게가 며칠씩 이어지는 경우(예: 55.3 / 55.3 / 55.3 / 54.0)
 * 평평한 구간은 그래프에서 의미가 없으므로 걷어내고, 최초 기록과 값이 바뀐 날만 남긴다.
 * 위 예시라면 55.3(최초)과 54.0(바뀐 날) 두 점만 남는다.
 *
 * @param records 최신순(DESC) 정렬된 전체 기록
 * @return 최신순(DESC)을 유지한 변화 지점 목록
 */
internal fun changedWeightPoints(records: List<DailyRecordModel>): List<DailyRecordModel> {
    // 시간순(ASC)으로 뒤집어 "이전 날짜"와 비교한 뒤, 다시 최신순으로 돌려준다
    val asc = records.filter { it.weight > 0f }.asReversed()
    val result = ArrayList<DailyRecordModel>()
    var prevWeight: Float? = null
    for (r in asc) {
        if (prevWeight == null || r.weight != prevWeight) {
            result.add(r)
        }
        prevWeight = r.weight
    }
    return result.asReversed()
}

/**
 * 몸무게 추이 전용 화면.
 *
 * 좌우로 스와이프하면서 전체 기간을 훑어볼 수 있다.
 * LazyRow라서 화면에 보이는 구간만 그려지므로, 기록이 수천 개여도
 * 실제로 그려지는 항목은 십여 개뿐이라 별도 페이징 없이도 가볍다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightTrendScreen(
    mainViewModel: MainViewModel,
    focusDate: String,
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit
) {
    val records by mainViewModel.allDailyRecord.collectAsState()

    // 몸무게가 "이전 날짜 대비 바뀐 날"만 남긴다.
    // 예) 55.3 / 55.3 / 55.3 / 54.0 → 55.3(최초) 과 54.0(바뀐 날)만 표시.
    // 같은 몸무게가 이어지는 구간은 그래프에서 평평한 선이 길게 늘어질 뿐이라 걷어낸다.
    val shown = remember(records) { changedWeightPoints(records) }

    val listState = rememberLazyListState()

    // 한 번 누르면 선택(위에 정보 표시), 같은 점을 한 번 더 누르면 그날 정산 화면으로 이동
    var selectedDate by remember { mutableStateOf<String?>(null) }

    // 시스템 뒤로가기로 앱이 종료되지 않고 이전 화면으로 돌아가도록
    BackHandler(enabled = true) { onBack() }

    // 들어온 날짜를 기준으로 그래프를 연다.
    // 그 날짜에 변화가 없었다면(=목록에 없다면) 그 이전의 가장 가까운 변화 지점으로 맞춘다.
    LaunchedEffect(shown, focusDate) {
        if (shown.isEmpty()) return@LaunchedEffect
        // shown은 최신순(DESC)이므로, 기준일 이하인 첫 항목이 "기준일 시점의 몸무게"다
        val idx = shown.indexOfFirst { it.date <= focusDate }
            .let { if (it < 0) shown.lastIndex else it }
        // reverseLayout이라 scrollToItem(idx)는 그 지점을 화면 맨 오른쪽에 붙인다.
        // 이후 흐름도 같이 보이도록 조금 더 최신 쪽(작은 인덱스)으로 당겨 화면 안쪽에 오게 한다.
        listState.scrollToItem((idx - 2).coerceAtLeast(0))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("몸무게 추이") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (shown.isEmpty()) {
                EmptyWeightMessage()
                return@Column
            }

            Spacer(modifier = Modifier.height(8.dp))
            WeightSummaryCard(shown)
            Spacer(modifier = Modifier.height(12.dp))

            // 점을 한 번 누르면 여기에 그날 정보가 뜬다
            SelectedPointBar(
                record = shown.firstOrNull { it.date == selectedDate },
                onOpen = { selectedDate?.let(onNavigateToDetail) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            WeightLineChart(
                shown = shown,
                scrollState = listState,
                focusDate = focusDate,
                selectedDate = selectedDate,
                onDayClick = { date ->
                    // 이미 선택된 점을 다시 누르면 열고, 아니면 선택만 바꾼다
                    if (selectedDate == date) onNavigateToDetail(date) else selectedDate = date
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Text(
                text = "좌우로 밀어서 지난 기록을 볼 수 있어요. 점을 누르면 위에 정보가 뜨고, 한 번 더 누르면 그날 정산이 열립니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
    }
}

/**
 * 그래프에서 선택한 점의 정보를 보여주는 줄.
 * 아직 아무것도 고르지 않았을 때도 높이가 변하지 않도록 안내 문구를 대신 띄운다.
 */
@Composable
private fun SelectedPointBar(record: DailyRecordModel?, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (record != null) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        if (record == null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "그래프의 점을 눌러보세요",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(Locale.getDefault(), "%.1f kg", record.weight),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${record.date} ${getDayOfWeekText(record.date)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "한 번 더 눌러 열기",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun EmptyWeightMessage() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "아직 기록된 몸무게가 없습니다.\n정산할 때 몸무게를 입력하면\n여기에 추이가 그려집니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 현재 몸무게 / 시작 대비 변화 / 최저·최고 요약 */
@Composable
private fun WeightSummaryCard(shown: List<DailyRecordModel>) {
    val latest = shown.first().weight
    val oldest = shown.last().weight
    val diff = latest - oldest
    val minWeight = shown.minOf { it.weight }
    val maxWeight = shown.maxOf { it.weight }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format(Locale.getDefault(), "%.1f", latest),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = " kg",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                if (shown.size >= 2) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = String.format(Locale.getDefault(), "%+.1f kg", diff),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            // 감량(음수)이 초록, 증량(양수)이 빨강 — 칼로리 차트와 같은 규칙
                            color = if (diff <= 0f) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                        Text(
                            text = "시작(${shown.last().date}) 대비",
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
                WeightStat("최저", String.format(Locale.getDefault(), "%.1f kg", minWeight), Modifier.weight(1f))
                WeightStat("최고", String.format(Locale.getDefault(), "%.1f kg", maxWeight), Modifier.weight(1f))
                WeightStat("측정 일수", "${shown.size}일", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WeightStat(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
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
 * 꺾은선 그래프 본체.
 *
 * 선이 끊겨 보이지 않도록, 각 항목은 자기 영역 안에서
 * "왼쪽 이웃과의 중간점 → 내 점 → 오른쪽 이웃과의 중간점" 두 구간을 그린다.
 * 이렇게 하면 항목 단위로 잘라 그려도 이어진 하나의 선처럼 보인다.
 */
@Composable
private fun WeightLineChart(
    shown: List<DailyRecordModel>,
    scrollState: LazyListState,
    focusDate: String,
    selectedDate: String?,
    onDayClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 어느 지점이 "들어온 날짜"인지 강조 표시하기 위해 인덱스를 구해둔다
    val focusIndex = remember(shown, focusDate) {
        shown.indexOfFirst { it.date <= focusDate }.let { if (it < 0) shown.lastIndex else it }
    }
    // Y축 범위는 전체 기록 기준으로 한 번만 계산 (스크롤해도 기준이 흔들리지 않도록)
    val minWeight = remember(shown) { shown.minOf { it.weight } }
    val maxWeight = remember(shown) { shown.maxOf { it.weight } }
    // 전부 같은 몸무게여도 선이 바닥에 붙지 않도록 최소 범위를 둔다
    val range = (maxWeight - minWeight).coerceAtLeast(1f)

    // 0f(최저) ~ 1f(최고). 위아래 12%씩 여백을 둬서 점과 숫자가 잘리지 않게 한다
    fun yFraction(w: Float): Float = 0.12f + (w - minWeight) / range * 0.76f

    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val dotFill = MaterialTheme.colorScheme.surface

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
            state = scrollState,
            reverseLayout = true,   // 최신이 오른쪽. 왼쪽으로 밀면 과거가 이어서 나온다
            verticalAlignment = Alignment.Bottom
        ) {
            itemsIndexed(shown, key = { _, r -> r.date }) { index, r ->
                // reverseLayout이라 화면상 왼쪽 이웃이 index+1(과거), 오른쪽 이웃이 index-1(최신)
                val olderY = shown.getOrNull(index + 1)?.let { yFraction(it.weight) }
                val newerY = shown.getOrNull(index - 1)?.let { yFraction(it.weight) }
                val myY = yFraction(r.weight)
                // 들어온 날짜 지점은 옅게, 사용자가 직접 고른 점은 더 진하게 표시
                val isFocus = index == focusIndex
                val isSelected = r.date == selectedDate

                Column(
                    modifier = Modifier
                        .width(56.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                isSelected -> lineColor.copy(alpha = 0.18f)
                                isFocus -> lineColor.copy(alpha = 0.08f)
                                else -> Color.Transparent
                            }
                        )
                        .clickable { onDayClick(r.date) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.1f", r.weight),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isFocus) FontWeight.Bold else FontWeight.SemiBold,
                        color = lineColor,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val w = size.width
                        val h = size.height
                        val cx = w / 2f
                        // yFraction은 1f가 최고 몸무게 → 화면 위쪽이므로 뒤집는다
                        val cy = h * (1f - myY)
                        val strokeW = 2.dp.toPx()

                        // 바닥 기준선 (옅게)
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, h),
                            end = Offset(w, h),
                            strokeWidth = 1.dp.toPx()
                        )

                        olderY?.let {
                            drawLine(
                                color = lineColor,
                                start = Offset(0f, h * (1f - (myY + it) / 2f)),
                                end = Offset(cx, cy),
                                strokeWidth = strokeW,
                                cap = StrokeCap.Round
                            )
                        }
                        newerY?.let {
                            drawLine(
                                color = lineColor,
                                start = Offset(cx, cy),
                                end = Offset(w, h * (1f - (myY + it) / 2f)),
                                strokeWidth = strokeW,
                                cap = StrokeCap.Round
                            )
                        }

                        // 점: 안쪽을 배경색으로 채워 선 위에 또렷하게 보이도록
                        val emphasized = isSelected || isFocus
                        val dotR = if (emphasized) 6.dp.toPx() else 4.dp.toPx()
                        drawCircle(color = dotFill, radius = dotR, center = Offset(cx, cy))
                        drawCircle(
                            color = lineColor,
                            radius = dotR,
                            center = Offset(cx, cy),
                            style = Stroke(width = if (emphasized) 3.dp.toPx() else 2.dp.toPx())
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    // 일요일=빨강, 토요일=파랑, 평일=기본색 (칼로리 차트와 동일 규칙)
                    val dayColor = weekdayColor(r.date, emptySet())
                    Text(
                        text = r.date.takeLast(2),
                        style = MaterialTheme.typography.labelSmall,
                        color = dayColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
