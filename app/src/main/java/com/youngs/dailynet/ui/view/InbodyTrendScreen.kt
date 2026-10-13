package com.youngs.dailynet.ui.view

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.R
import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import com.youngs.dailynet.util.Constants
import java.util.Locale

/**
 * 그래프로 보는 항목. 한 그래프에 한 항목만 둔다 (단위가 다른 값을 한 축에 겹치지 않는다).
 * 색은 항목마다 고정이고 라이트·다크 각각 검증된 다섯 색을 돌려 쓴다.
 */
enum class InbodyMetric(
    val key: String,
    val labelRes: Int,
    val unit: String,
    val decimals: Int,
    val valueOf: (InbodyRecordEntity) -> Float
) {
    WEIGHT("weight", R.string.inbody_weight_label, "kg", 1, { it.weight }),
    MUSCLE("muscle", R.string.inbody_muscle_label, "kg", 1, { it.skeletalMuscle }),
    FAT("fat", R.string.inbody_fat_label, "kg", 1, { it.bodyFat }),
    FAT_PCT("fatPct", R.string.inbody_fat_pct_label, "%", 1, { it.bodyFatPercent }),
    BMR("bmr", R.string.inbody_bmr_label, "kcal", 0, { it.bmr.toFloat() }),
    VISCERAL("visceral", R.string.inbody_visceral_label, "", 0, { it.visceralFatLevel.toFloat() }),
    SCORE("score", R.string.inbody_score_label, "", 0, { it.inbodyScore.toFloat() }),
    WATER("water", R.string.inbody_water_label, "L", 1, { it.bodyWater });

    fun format(v: Float): String =
        if (decimals == 0) v.toInt().toString() else String.format(Locale.getDefault(), "%.1f", v)
}

private val LIGHT = listOf(Color(0xFF4A86E8), Color(0xFF2E9E5B), Color(0xFFC9971E), Color(0xFFC43C3C), Color(0xFF8E44AD))
private val DARK = listOf(Color(0xFF5B93F0), Color(0xFF35A862), Color(0xFFB8891A), Color(0xFFC43C3C), Color(0xFF9A55BF))

private const val KEY_HIDDEN = "inbody_trend_hidden"

private fun hiddenMetrics(context: Context): Set<String> =
    context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(KEY_HIDDEN, emptySet()).orEmpty()

private fun saveHiddenMetrics(context: Context, hidden: Set<String>) {
    context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putStringSet(KEY_HIDDEN, hidden).apply()
}

/**
 * 인바디 추이. 측정이 2건 이상일 때 목록 화면의 그래프 버튼으로 들어온다.
 *
 * 위에 항목 토글(칩), 요약 카드, 그 아래 항목별 꺾은선 그래프. 토글은 기기에 저장된다.
 * 값이 있는 측정이 2건 미만인 항목은 그래프를 그릴 수 없어 칩을 비활성화한다.
 * 점을 누르면 아래 줄에 그 측정 요약이 뜨고, 한 번 더 누르면 그 측정 화면으로 간다.
 *
 * 디버그 빌드에서만 더미 측정을 기기에 넣고 지우는 버튼이 보인다 (계정에는 올라가지 않는다).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InbodyTrendScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    onOpen: (measuredAt: String) -> Unit
) {
    val context = LocalContext.current
    val records by mainViewModel.inbodyRecords.collectAsState()   // 최신이 앞
    val colors = if (isSystemInDarkTheme()) DARK else LIGHT

    var hidden by remember { mutableStateOf(hiddenMetrics(context)) }
    var selectedKey by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) { onBack() }

    // 항목별로 값이 있는 측정만 모은다 (0은 "없음")
    fun pointsOf(metric: InbodyMetric): List<TrendPoint> = records
        .filter { metric.valueOf(it) > 0f }
        .map { TrendPoint(key = it.measuredAt, label = it.measuredAt.substring(2).replace("-", "."), value = metric.valueOf(it)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbody_trend_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(0.dp))

            // ── 항목 토글 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InbodyMetric.entries.forEach { metric ->
                    val available = pointsOf(metric).size >= 2
                    FilterChip(
                        selected = available && metric.key !in hidden,
                        enabled = available,
                        onClick = {
                            hidden = if (metric.key in hidden) hidden - metric.key else hidden + metric.key
                            saveHiddenMetrics(context, hidden)
                        },
                        label = { Text(stringResource(metric.labelRes).substringBefore(" (")) }
                    )
                }
            }

            if (records.size < 2) {
                Text(
                    stringResource(R.string.inbody_trend_need_two),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                TrendSummaryCard(records)

                // 선택한 점의 측정 요약
                val selected = records.firstOrNull { it.measuredAt == selectedKey }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = if (selected == null) stringResource(R.string.inbody_trend_hint)
                        else "${selected.measuredAt} · " + inbodySummary(selected.toData(), withDate = false) +
                            "  ›",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        maxLines = 2
                    )
                }

                InbodyMetric.entries.forEachIndexed { i, metric ->
                    val points = pointsOf(metric)
                    if (points.size < 2 || metric.key in hidden) return@forEachIndexed
                    val first = points.last().value
                    val last = points.first().value
                    val delta = last - first
                    val color = colors[i % colors.size]

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(metric.labelRes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = (if (delta > 0f) "+" else "") + metric.format(delta) + " " + metric.unit,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TrendLineChart(
                            points = points,
                            formatValue = { metric.format(it) },
                            selectedKey = selectedKey,
                            onPointClick = { key ->
                                if (selectedKey == key) onOpen(key) else selectedKey = key
                            },
                            lineColor = color,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(170.dp)
                        )
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { mainViewModel.insertInbodyDummy() }) { Text("더미 6건 넣기 (기기만)") }
                    OutlinedButton(onClick = { mainViewModel.clearInbodyDummy() }) { Text("더미 지우기") }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 측정 횟수·기간·체중/골격근/체지방 변화량 */
@Composable
private fun TrendSummaryCard(records: List<InbodyRecordEntity>) {
    val newest = records.first()
    val oldest = records.last()
    fun change(get: (InbodyRecordEntity) -> Float): String? {
        val a = oldest.let(get)
        val b = newest.let(get)
        if (a <= 0f || b <= 0f) return null
        val d = b - a
        return (if (d > 0f) "+" else "") + String.format(Locale.getDefault(), "%.1f", d)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.inbody_trend_period, records.size, oldest.measuredAt, newest.measuredAt),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryStat(stringResource(R.string.inbody_weight_label).substringBefore(" ("), change { it.weight }, "kg")
                SummaryStat(stringResource(R.string.inbody_muscle_label).substringBefore(" ("), change { it.skeletalMuscle }, "kg")
                SummaryStat(stringResource(R.string.inbody_fat_label).substringBefore(" ("), change { it.bodyFat }, "kg")
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String?, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (value == null) "–" else "$value $unit",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
