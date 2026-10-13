package com.youngs.dailynet.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 꺾은선 그래프의 점 하나. [key]는 선택·이동에 쓰는 식별자(측정일), [label]은 가로축 글자 */
data class TrendPoint(val key: String, val label: String, val value: Float)

/**
 * 가로로 스크롤되는 꺾은선 그래프. 체중 추이(WeightTrendScreen)의 그리는 방식을 값 목록으로 일반화한 것이다.
 *
 * 각 점은 자기 칸 안에서 "왼쪽 이웃과의 중간점 → 내 점 → 오른쪽 이웃과의 중간점" 두 구간을 그린다.
 * 그래서 칸 단위로 잘라 그려도 이어진 선처럼 보이고, LazyRow라 점이 많아도 보이는 구간만 그린다.
 * 점의 값은 위에, 가로축 글자는 아래에 둔다. 값 글자는 잉크색이 아니라 선 색을 써 어느 그래프의 값인지 바로 보이게 한다.
 */
@Composable
fun TrendLineChart(
    points: List<TrendPoint>,          // 최신이 앞 (reverseLayout으로 오른쪽에 온다)
    formatValue: (Float) -> String,
    selectedKey: String?,
    onPointClick: (String) -> Unit,
    lineColor: Color,
    modifier: Modifier = Modifier,
    scrollState: LazyListState = rememberLazyListState()
) {
    val minV = remember(points) { points.minOf { it.value } }
    val maxV = remember(points) { points.maxOf { it.value } }
    val range = (maxV - minV).coerceAtLeast(0.1f)

    // 0f(최저) ~ 1f(최고). 위아래 12%씩 여백을 둬 점과 숫자가 잘리지 않게 한다
    fun yFraction(v: Float): Float = 0.12f + (v - minV) / range * 0.76f

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
                .padding(vertical = 12.dp),
            state = scrollState,
            reverseLayout = true,
            verticalAlignment = Alignment.Bottom
        ) {
            itemsIndexed(points, key = { _, p -> p.key }) { index, p ->
                val olderY = points.getOrNull(index + 1)?.let { yFraction(it.value) }
                val newerY = points.getOrNull(index - 1)?.let { yFraction(it.value) }
                val myY = yFraction(p.value)
                val isSelected = p.key == selectedKey

                Column(
                    modifier = Modifier
                        .width(64.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) lineColor.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { onPointClick(p.key) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = formatValue(p.value),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
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
                        val cy = h * (1f - myY)
                        val strokeW = 2.dp.toPx()

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
                        val dotR = if (isSelected) 6.dp.toPx() else 4.dp.toPx()
                        drawCircle(color = dotFill, radius = dotR, center = Offset(cx, cy))
                        drawCircle(
                            color = lineColor,
                            radius = dotR,
                            center = Offset(cx, cy),
                            style = Stroke(width = if (isSelected) 3.dp.toPx() else 2.dp.toPx())
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = p.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
