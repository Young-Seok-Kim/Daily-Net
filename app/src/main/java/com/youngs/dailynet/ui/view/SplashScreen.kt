package com.youngs.dailynet.ui.view

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youngs.dailynet.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * 스플래시 색. 앱 테마(다이나믹 컬러)와 무관하게 항상 같은 연보라 계열로 보이도록 고정한다.
 * 라이트/다크 어느 쪽에서 켜도 같은 첫 화면이 나오게 하려는 것.
 * 배경은 밝게, 링·막대·글자는 진하게 해서 대비를 준다.
 */
private val SplashTop = Color(0xFFF3EEFF)
private val SplashMid = Color(0xFFE2D8FF)
private val SplashBottom = Color(0xFFC4B3F5)
/** 배경에 떠다니는 빛 덩어리 색 (연한 배경 위라 조금 진하게) */
private val Lavender = Color(0xFFA890FF)
private val Mint = Color(0xFF6FDCC0)
private val Rose = Color(0xFFF7A9C4)
/** 링·막대에 쓰는 진한 색 */
private val DeepMint = Color(0xFF2FBF9A)
private val DeepPurple = Color(0xFF7C5CF2)
private val DeepRose = Color(0xFFEE6F9B)
/** 글자색 */
private val Ink = Color(0xFF2A1B5E)
private val InkSoft = Color(0xFF6650A4)

/** 반짝이 별 위치(화면 비율)와 깜빡임 위상 */
private val Stars = listOf(
    Triple(0.12f, 0.18f, 0.0f), Triple(0.28f, 0.08f, 1.1f), Triple(0.55f, 0.12f, 2.3f),
    Triple(0.82f, 0.2f, 0.7f), Triple(0.92f, 0.55f, 1.9f), Triple(0.08f, 0.62f, 2.8f),
    Triple(0.2f, 0.86f, 0.4f), Triple(0.48f, 0.92f, 1.5f), Triple(0.78f, 0.9f, 2.6f),
    Triple(0.66f, 0.3f, 3.3f), Triple(0.35f, 0.7f, 0.9f), Triple(0.88f, 0.78f, 2.0f)
)

/**
 * 앱을 켤 때 한 번 보여주는 스플래시.
 *
 * 순서: 링이 그려지고 → 안쪽 막대 그래프가 자라고 → 제목이 한 글자씩 떠오르고 → 문구가 나온 뒤
 * 전체가 서서히 사라지며 [onFinished]를 부른다. 다 합쳐 3초 남짓.
 * 뒤에서는 빛 덩어리가 천천히 떠다니고 별이 깜빡인다.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.5f) }
    val ringSweep = remember { Animatable(0f) }
    val barsProgress = remember { Animatable(0f) }
    val titleProgress = remember { Animatable(0f) }
    val taglineAlpha = remember { Animatable(0f) }
    val screenAlpha = remember { Animatable(1f) }

    // 재구성으로 람다가 바뀌어도 마지막 것을 부르도록
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(450)) }
        launch {
            logoScale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        }
        launch { ringSweep.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)) }
        delay(350)
        launch { barsProgress.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        delay(300)
        launch { titleProgress.animateTo(1f, tween(1000, easing = LinearEasing)) }
        delay(800)
        taglineAlpha.animateTo(1f, tween(500))
        delay(900)
        screenAlpha.animateTo(0f, tween(450))
        currentOnFinished()
    }

    val infinite = rememberInfiniteTransition(label = "splash")
    val drift by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "drift"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = screenAlpha.value }
            .background(Brush.verticalGradient(listOf(SplashTop, SplashMid, SplashBottom))),
        contentAlignment = Alignment.Center
    ) {
        // 배경: 떠다니는 빛 덩어리 + 깜빡이는 별
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = drift * 2f * PI.toFloat()

            fun orb(fx: Float, fy: Float, radiusFrac: Float, color: Color, phase: Float) {
                val r = size.minDimension * radiusFrac
                val center = Offset(
                    size.width * fx + cos(t + phase) * size.width * 0.06f,
                    size.height * fy + sin(t * 1.3f + phase) * size.height * 0.04f
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = 0.5f), Color.Transparent),
                        center = center, radius = r
                    ),
                    radius = r,
                    center = center
                )
            }
            orb(0.2f, 0.25f, 0.38f, Lavender, 0f)
            orb(0.85f, 0.35f, 0.32f, Rose, 2f)
            orb(0.7f, 0.82f, 0.42f, Mint, 4f)
            orb(0.15f, 0.85f, 0.28f, DeepPurple, 5f)

            Stars.forEach { (fx, fy, phase) ->
                val twinkle = abs(sin(t * 3f + phase))
                val r = (1.2f + twinkle * 1.6f).dp.toPx()
                drawCircle(
                    color = Color.White.copy(alpha = 0.45f + twinkle * 0.55f),
                    radius = r,
                    center = Offset(size.width * fx, size.height * fy)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 로고: 링 + 막대 그래프
            Canvas(
                modifier = Modifier
                    .size(160.dp)
                    .graphicsLayer {
                        alpha = logoAlpha.value
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                    }
            ) {
                val stroke = 10.dp.toPx()
                val inset = stroke / 2 + 8.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)

                // 링 뒤의 은은한 빛 (숨쉬듯 커졌다 작아진다)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(DeepPurple.copy(alpha = 0.16f + pulse * 0.14f), Color.Transparent),
                        center = center, radius = size.minDimension / 2
                    ),
                    radius = size.minDimension / 2
                )

                // 트랙
                drawArc(
                    color = Ink.copy(alpha = 0.10f),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke)
                )

                // 12시에서 시작해 시계 방향으로 그려지는 호
                val sweep = 300f * ringSweep.value
                if (sweep > 0f) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(DeepMint, DeepPurple, DeepRose, DeepMint), center),
                        startAngle = -90f, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                    // 호 끝의 흰 점
                    val rad = Math.toRadians((-90f + sweep).toDouble())
                    val rr = arcSize.width / 2
                    val end = Offset(center.x + cos(rad).toFloat() * rr, center.y + sin(rad).toFloat() * rr)
                    drawCircle(DeepPurple.copy(alpha = 0.35f), stroke * 1.1f, end)
                    drawCircle(Color.White, stroke * 0.55f, end)
                }

                // 안쪽 막대 세 개가 순서대로 자란다
                val barW = 14.dp.toPx()
                val gap = 8.dp.toPx()
                val maxH = arcSize.height * 0.44f
                val baseY = center.y + maxH / 2
                val startX = center.x - (barW * 3 + gap * 2) / 2
                val bars = listOf(0.4f to DeepMint, 0.65f to DeepPurple, 0.95f to DeepRose)
                bars.forEachIndexed { i, (h, color) ->
                    val local = (barsProgress.value * 2.2f - i * 0.6f).coerceIn(0f, 1f)
                    val bh = maxH * h * FastOutSlowInEasing.transform(local)
                    if (bh > 0f) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(startX + i * (barW + gap), baseY - bh),
                            size = Size(barW, bh),
                            cornerRadius = CornerRadius(barW / 2)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 제목이 한 글자씩 아래에서 떠오른다
            val title = "DailyNet"
            Row {
                title.forEachIndexed { i, ch ->
                    val local = (titleProgress.value * (title.length + 2) - i).coerceIn(0f, 1f)
                    val eased = FastOutSlowInEasing.transform(local)
                    Text(
                        text = ch.toString(),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Ink,
                        modifier = Modifier.graphicsLayer {
                            alpha = eased
                            translationY = (1f - eased) * 28.dp.toPx()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.login_tagline),
                fontSize = 16.sp,
                letterSpacing = 0.5.sp,
                color = InkSoft,
                modifier = Modifier.graphicsLayer { alpha = taglineAlpha.value }
            )
        }
    }
}
