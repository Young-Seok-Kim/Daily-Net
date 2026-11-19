package com.youngs.dailynet.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youngs.dailynet.R
import com.youngs.dailynet.data.model.InbodyAnalysisDetail
import com.youngs.dailynet.data.model.InbodyData
import com.youngs.dailynet.data.model.InbodyMetrics
import com.youngs.dailynet.ui.theme.DnTheme

/**
 * 인바디 해석을 구조([InbodyAnalysisDetail])로 그린다 (b68~).
 *
 * 글 한 덩어리 대신 총평 → 판정 뱃지 → 체지방·골격근·기초대사량 카드 → 할 일 순서다.
 * 식단 리포트(AnalysisReport)처럼 카드 안에 카드를 넣지 않고 배경 위에 바로 깐다.
 * 모델이 비운 칸(빈 문자열)은 카드를 그리지 않는다. 구조가 없는 옛 해석은 호출하는 쪽이 글로 보여준다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InbodyAnalysisReport(detail: InbodyAnalysisDetail, data: InbodyData, modifier: Modifier = Modifier) {
    val m = detail.metrics
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (detail.summary.isNotBlank()) {
            Text(detail.summary, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
        }

        // 판정 뱃지 줄. 서버가 표준 구간으로 정한 값이라 색만 입힌다
        val badges = buildList {
            if (m.bmi > 0f) add(stringResource(R.string.inbody_badge_bmi, m.bmi) to InbodyMetrics.LEVEL_UNKNOWN)
            if (data.bodyFatPercent > 0f) add(stringResource(R.string.inbody_badge_fat, levelText(m.fatLevel)) to m.fatLevel)
            if (data.skeletalMuscle > 0f) add(stringResource(R.string.inbody_badge_muscle, levelText(m.muscleLevel)) to m.muscleLevel)
            if (data.visceralFatLevel > 0) add(stringResource(R.string.inbody_badge_visceral, levelText(m.visceralLevel)) to m.visceralLevel)
        }
        if (badges.isNotEmpty()) {
            // 뱃지가 넷이면 좁은 화면에서 한 줄을 넘으므로 줄바꿈되는 FlowRow
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                badges.forEach { (text, level) -> LevelBadge(text, level) }
            }
        }

        if (detail.bodyFat.isNotBlank()) {
            SectionCard(
                title = stringResource(R.string.inbody_report_fat_title),
                meta = listOfNotNull(
                    "${data.bodyFatPercent}%".takeIf { data.bodyFatPercent > 0f },
                    m.fatStandard.takeIf { it.isNotBlank() }?.let { stringResource(R.string.inbody_report_standard, it) }
                ).joinToString(" · "),
                body = detail.bodyFat
            )
        }
        if (detail.muscle.isNotBlank()) {
            SectionCard(
                title = stringResource(R.string.inbody_report_muscle_title),
                meta = listOfNotNull(
                    "${data.skeletalMuscle}kg".takeIf { data.skeletalMuscle > 0f },
                    stringResource(R.string.inbody_report_smm_ratio, m.smmRatio).takeIf { m.smmRatio > 0f },
                    m.muscleStandard.takeIf { it.isNotBlank() }?.let { stringResource(R.string.inbody_report_standard, it) }
                ).joinToString(" · "),
                body = detail.muscle
            )
        }
        if (detail.bmr.isNotBlank()) {
            SectionCard(
                title = stringResource(R.string.inbody_report_bmr_title),
                meta = listOfNotNull(
                    "${data.bmr} kcal".takeIf { data.bmr > 0 },
                    stringResource(R.string.inbody_report_bmr_formula, m.formulaBmr, m.bmrDiff).takeIf { m.formulaBmr > 0 }
                ).joinToString(" · "),
                body = detail.bmr
            )
        }

        if (detail.actions.isNotEmpty()) {
            SectionCard(
                title = stringResource(R.string.inbody_report_actions_title),
                meta = stringResource(R.string.inbody_report_protein, m.proteinTarget).takeIf { m.proteinTarget > 0 }.orEmpty(),
                body = null
            ) {
                detail.actions.forEachIndexed { index, action ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = DnTheme.net.deficit
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(action, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
                    }
                }
            }
        }
    }
}

/** 제목 + (작은 수치 줄) + 본문. [body]가 null이면 [extra]로 내용을 채운다 (할 일 목록) */
@Composable
private fun SectionCard(
    title: String,
    meta: String,
    body: String?,
    extra: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (meta.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
            extra?.invoke()
        }
    }
}

/** 판정 코드에 색을 입힌 작은 알약. 표준은 초록, 표준 이상은 적자색(빨강), 표준 이하·정보 없음은 회색 */
@Composable
private fun LevelBadge(text: String, level: String) {
    val (bg, fg) = when (level) {
        InbodyMetrics.LEVEL_STANDARD -> DnTheme.net.deficit.copy(alpha = 0.15f) to DnTheme.net.deficit
        InbodyMetrics.LEVEL_HIGH -> DnTheme.net.surplus.copy(alpha = 0.15f) to DnTheme.net.surplus
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun levelText(level: String): String = stringResource(
    when (level) {
        InbodyMetrics.LEVEL_LOW -> R.string.inbody_level_low
        InbodyMetrics.LEVEL_STANDARD -> R.string.inbody_level_standard
        InbodyMetrics.LEVEL_HIGH -> R.string.inbody_level_high
        else -> R.string.inbody_level_unknown
    }
)
