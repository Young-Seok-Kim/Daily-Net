package com.youngs.dailynet.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import java.util.Locale

/**
 * 구간 막대. 값이 어느 구간(저체중/정상/과체중…)에 있는지 한눈에 보여준다.
 *
 * 색만으로 구간을 구분하지 않는다 — 구간마다 글자 라벨과 경계 숫자가 붙고, 구간 사이에 2dp 틈을 둔다.
 * 색은 라이트·다크 각각 색각 이상 구분(ΔE)과 밝기 검증을 통과한 다섯 개를 쓴다.
 * 값은 막대 위 삼각 표시와 숫자로, 글자는 본문 잉크색이다 (구간 색을 글자에 쓰지 않는다).
 */
data class GaugeSegment(val label: String, val from: Float, val to: Float)

/** 심한 정도 순서의 다섯 색. 구간이 넷이면 앞 넷을 쓴다 */
private val LIGHT_COLORS = listOf(
    Color(0xFF4A86E8), Color(0xFF2E9E5B), Color(0xFFC9971E), Color(0xFFC43C3C), Color(0xFF8E44AD)
)
private val DARK_COLORS = listOf(
    Color(0xFF5B93F0), Color(0xFF35A862), Color(0xFFB8891A), Color(0xFFC43C3C), Color(0xFF9A55BF)
)

@Composable
fun RangeGauge(
    title: String,
    value: Float,
    valueText: String,
    segments: List<GaugeSegment>,
    modifier: Modifier = Modifier
) {
    val colors = if (isSystemInDarkTheme()) DARK_COLORS else LIGHT_COLORS
    val min = segments.first().from
    val max = segments.last().to
    val clamped = value.coerceIn(min, max)
    val fraction = (clamped - min) / (max - min)
    val current = segments.firstOrNull { value >= it.from && value < it.to } ?: segments.last()
    val ink = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "$valueText · ${current.label}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = ink
            )
        }

        // 삼각 표시 + 막대. 표시는 값 위치에, 막대는 구간별로 틈을 두고 그린다
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
        ) {
            val gap = with(density) { 2.dp.toPx() }
            val barTop = with(density) { 10.dp.toPx() }
            val barHeight = with(density) { 12.dp.toPx() }
            val radius = with(density) { 4.dp.toPx() }
            val w = size.width

            segments.forEachIndexed { i, seg ->
                val x0 = (seg.from - min) / (max - min) * w + (if (i == 0) 0f else gap / 2)
                val x1 = (seg.to - min) / (max - min) * w - (if (i == segments.lastIndex) 0f else gap / 2)
                drawRoundRect(
                    color = colors[i.coerceAtMost(colors.lastIndex)],
                    topLeft = Offset(x0, barTop),
                    size = Size((x1 - x0).coerceAtLeast(0f), barHeight),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }

            // 내 위치: 막대 위 역삼각형
            val mx = fraction * w
            val half = with(density) { 6.dp.toPx() }
            val tri = Path().apply {
                moveTo(mx - half, 0f)
                lineTo(mx + half, 0f)
                lineTo(mx, barTop - with(density) { 1.dp.toPx() })
                close()
            }
            drawPath(tri, ink)
        }

        // 구간 라벨 (아래 줄). 구간 폭에 비례해 자리를 나눈다
        Row(modifier = Modifier.fillMaxWidth()) {
            segments.forEach { seg ->
                Text(
                    seg.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (seg == current) ink else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (seg == current) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight((seg.to - seg.from).coerceAtLeast(0.01f)),
                    maxLines = 1
                )
            }
        }
        // 경계 숫자 (양 끝은 생략)
        Row(modifier = Modifier.fillMaxWidth()) {
            segments.forEachIndexed { i, seg ->
                Text(
                    if (i == 0) "" else trimNumber(seg.from),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight((seg.to - seg.from).coerceAtLeast(0.01f)),
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

private fun trimNumber(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(Locale.getDefault(), "%.1f", v)

/**
 * BMI 막대. 대한비만학회 기준(아시아·태평양): 저체중 <18.5, 정상 18.5~23, 과체중 23~25, 비만 25~30, 고도비만 ≥30.
 * 축은 15~35로 자른다. 벗어나면 끝에 붙는다.
 */
@Composable
fun BmiGauge(bmi: Float, modifier: Modifier = Modifier) {
    val segments = listOf(
        GaugeSegment(stringResource(R.string.bmi_underweight), 15f, 18.5f),
        GaugeSegment(stringResource(R.string.bmi_normal), 18.5f, 23f),
        GaugeSegment(stringResource(R.string.bmi_overweight), 23f, 25f),
        GaugeSegment(stringResource(R.string.bmi_obese), 25f, 30f),
        GaugeSegment(stringResource(R.string.bmi_severely_obese), 30f, 35f)
    )
    RangeGauge(
        title = stringResource(R.string.gauge_bmi_title),
        value = bmi,
        valueText = String.format(Locale.getDefault(), "%.1f", bmi),
        segments = segments,
        modifier = modifier
    )
}

/**
 * 체지방률 막대. 인바디 표준 구간: 남 10~20%, 여 18~28%. 그 아래는 낮음, 위는 높음·매우 높음.
 */
@Composable
fun BodyFatGauge(percent: Float, isMale: Boolean, modifier: Modifier = Modifier) {
    val (lo, hi, veryHigh, max) = if (isMale) listOf(10f, 20f, 25f, 40f) else listOf(18f, 28f, 33f, 48f)
    val min = if (isMale) 0f else 8f
    val segments = listOf(
        GaugeSegment(stringResource(R.string.fat_low), min, lo),
        GaugeSegment(stringResource(R.string.fat_standard), lo, hi),
        GaugeSegment(stringResource(R.string.fat_high), hi, veryHigh),
        GaugeSegment(stringResource(R.string.fat_very_high), veryHigh, max)
    )
    RangeGauge(
        title = stringResource(R.string.gauge_fat_title),
        value = percent,
        valueText = String.format(Locale.getDefault(), "%.1f%%", percent),
        segments = segments,
        modifier = modifier
    )
}
