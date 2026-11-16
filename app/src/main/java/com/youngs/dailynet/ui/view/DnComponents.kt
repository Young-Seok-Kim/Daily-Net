package com.youngs.dailynet.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.ui.theme.DnDimens
import com.youngs.dailynet.ui.theme.DnTheme
import com.youngs.dailynet.ui.theme.tabular
import java.util.Locale
import kotlin.math.roundToInt

/*
 * 전 화면이 같이 쓰는 모양 부품.
 *
 * 규칙 세 가지.
 * - 라운드는 DnDimens 의 네 단(12/16/20/28)만 쓴다
 * - 카드는 테두리와 그림자 중 하나만 쓴다. 여기서는 둘 다 없이 배경 차이로만 구분한다
 * - 숫자는 tabular 로 자릿수 폭을 고정한다
 */

/** 기본 카드. surface 바탕, 라운드 20, 테두리·그림자 없음 */
@Composable
fun DnCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(DnDimens.CardPadding),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DnDimens.RadiusCard),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 카드 제목 줄. 왼쪽 제목, 오른쪽에 보조 값이나 셰브론 같은 것.
 * [trailing] 은 제목과 같은 줄 끝에 붙는다.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/**
 * kcal 숫자 한 덩어리. 부호·천 단위 구분·색·tabular 를 한 곳에서 처리한다.
 *
 * @param withUnit "kcal" 단위를 작은 글자로 뒤에 붙일지
 * @param signed 양수에도 + 를 붙일지 (기본은 음수에만 -)
 */
@Composable
fun KcalValue(
    value: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = DnTheme.net.of(value),
    withUnit: Boolean = false,
    signed: Boolean = false,
    unitStyle: TextStyle = MaterialTheme.typography.labelLarge
) {
    val text = if (signed) String.format(Locale.getDefault(), "%+,d", value)
    else String.format(Locale.getDefault(), "%,d", value)
    // 숫자와 단위를 글자 기준선으로 맞춘다 (Bottom 정렬은 줄 높이 차이만큼 단위가 떠 보였다)
    Row(modifier = modifier) {
        Text(
            text = text, style = style.tabular(), color = color, maxLines = 1,
            modifier = Modifier.alignByBaseline()
        )
        if (withUnit) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "kcal",
                style = unitStyle,
                color = color.copy(alpha = 0.85f),
                maxLines = 1,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}

/**
 * "어제 -420" 같은 증감 pill.
 * 짙은 바탕(히어로 카드) 위에서는 [onHero] 를 켜서 반투명 흰 바탕에 민트 글자로 그린다.
 */
@Composable
fun NetDeltaBadge(
    text: String,
    modifier: Modifier = Modifier,
    onHero: Boolean = false,
    net: Int? = null
) {
    val bg: Color
    val fg: Color
    if (onHero) {
        bg = Color.White.copy(alpha = 0.12f)
        fg = DnTheme.net.mint
    } else {
        bg = MaterialTheme.colorScheme.surfaceVariant
        fg = net?.let { DnTheme.net.of(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = bg, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.tabular(),
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** 작은 라벨 pill. 정산 기록의 "운동" 칩 등 */
@Composable
fun DnChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Surface(shape = RoundedCornerShape(50), color = containerColor, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * 탄·단·지 비율 스택 바. 세 색이 한 줄에 g 비율대로 이어진다.
 * 전부 0이면 옅은 트랙만 남긴다.
 */
@Composable
fun MacroStackBar(
    carb: Float,
    protein: Float,
    fat: Float,
    modifier: Modifier = Modifier,
    thickness: androidx.compose.ui.unit.Dp = DnDimens.BarThin
) {
    val net = DnTheme.net
    val total = (carb + protein + fat).coerceAtLeast(0f)
    Row(
        modifier = modifier
            .height(thickness)
            .clip(RoundedCornerShape(thickness / 2))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (total <= 0f) return@Row
        listOf(carb to net.carb, protein to net.protein, fat to net.fat).forEach { (g, color) ->
            if (g > 0f) {
                Box(
                    modifier = Modifier
                        .weight(g / total)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(thickness / 2))
                        .background(color)
                )
            }
        }
    }
}

/** "탄 15g" 칩 하나. 영양소 색의 옅은 바탕에 진한 글자 */
@Composable
fun MacroChip(label: String, grams: Float, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            text = "$label ${formatGrams(grams)}g",
            style = MaterialTheme.typography.labelLarge.tabular(),
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/** 탄·단·지 칩 세 개 한 줄 */
@Composable
fun MacroChipRow(
    carbLabel: String, proteinLabel: String, fatLabel: String,
    carb: Float, protein: Float, fat: Float,
    modifier: Modifier = Modifier
) {
    val net = DnTheme.net
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MacroChip(carbLabel, carb, net.carbContainer, net.onCarbContainer)
        MacroChip(proteinLabel, protein, net.proteinContainer, net.onProteinContainer)
        MacroChip(fatLabel, fat, net.fatContainer, net.onFatContainer)
    }
}

/**
 * 권장량 대비 진행 바 한 줄. 라벨 왼쪽, "115g / 250g" 오른쪽, 아래 막대.
 * 권장을 넘으면 막대가 코랄로 바뀌고 꽉 찬 채 멈춘다. 넘친 정도는 숫자로 읽는다.
 */
@Composable
fun MacroProgressRow(
    label: String,
    value: Float,
    recommended: Int,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    val ratio = if (recommended <= 0) 0f else (value / recommended).coerceIn(0f, 1f)
    val over = recommended > 0 && value > recommended
    val fill = if (over) DnTheme.net.coral else barColor

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${formatGrams(value)}g / ${recommended}g",
                style = MaterialTheme.typography.titleSmall.tabular(),
                color = if (over) DnTheme.net.surplus else MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DnDimens.BarThin)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(fill)
            )
        }
    }
}

/** 라벨 위 · 값 아래 지표 한 칸. 히어로 카드·월 헤드라인이 같이 쓴다 */
@Composable
fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueStyle: TextStyle = MaterialTheme.typography.titleLarge
) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 1)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = valueStyle.tabular(), color = valueColor, maxLines = 1)
    }
}

/** 총평처럼 인용 느낌의 글. 왼쪽에 3dp 민트 바가 글 높이만큼 선다 */
@Composable
fun QuoteBlock(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(DnTheme.net.mint)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 소수 첫째 자리까지, 정수면 소수 없이 */
fun formatGrams(value: Float): String {
    val rounded = (value * 10).roundToInt() / 10f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
    else String.format(Locale.getDefault(), "%.1f", rounded)
}

/** 운동 시간 표기. 0 이면 빈 문자열 */
fun formatMinutes(minutes: Int, hourLabel: String, minuteLabel: String): String {
    if (minutes <= 0) return ""
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "$h$hourLabel $m$minuteLabel"
        h > 0 -> "$h$hourLabel"
        else -> "$m$minuteLabel"
    }
}
