package com.youngs.dailynet.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import com.youngs.dailynet.data.model.AnalysisDetail
import com.youngs.dailynet.data.model.AnalysisItem
import com.youngs.dailynet.data.model.MacroSet
import com.youngs.dailynet.ui.theme.DnDimens
import com.youngs.dailynet.ui.theme.DnTheme
import com.youngs.dailynet.ui.theme.tabular
import com.youngs.dailynet.util.ReportSection
import java.util.Locale

/** 사용자가 고치려고 누른 항목 */
data class ReportEditTarget(
    val section: ReportSection,
    val index: Int,
    val item: AnalysisItem
)

/**
 * 분석 결과를 구조(AnalysisDetail)에서 카드로 그린 리포트.
 *
 * 순서: 요약 스트립(IN/OUT/NET + 권장 대비) → 끼니 카드 4장 → 운동 카드 → 목표 가이드 → 총평.
 * 예전에는 같은 구조를 "   • 항목명", "[탄 | 단 | 지]" 같은 문자열로 조판해 메모장처럼 보였다.
 *
 * 텍스트 대신 구조로 그리는 이유는 하나다 — 항목을 눌러 kcal을 고칠 수 있어야 하고,
 * 고치면 합계·결산이 같이 바뀌어야 한다. 텍스트는 숫자 하나를 바꾸면 같은 글 안의 합계와 어긋난다.
 *
 * b33 이전에 분석한 기록은 이 구조가 없어 [LegacyReport]로 텍스트를 보여준다.
 */
@Composable
fun AnalysisReport(
    detail: AnalysisDetail,
    edited: Boolean,
    editable: Boolean,
    onEditItem: (ReportEditTarget) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DnDimens.SpaceMd)
    ) {
        if (edited || editable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (edited) {
                    DnChip(
                        text = stringResource(R.string.report_edited_badge),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (editable) {
                    Text(
                        text = stringResource(R.string.report_edit_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SummaryStrip(detail)

        MealCard(
            title = stringResource(R.string.report_breakfast),
            items = detail.meals.breakfast,
            total = detail.calories.breakfast,
            macros = detail.mealMacros.breakfast,
            description = detail.descriptions.breakfast,
            editable = editable,
            onItemClick = { i, item -> onEditItem(ReportEditTarget(ReportSection.BREAKFAST, i, item)) }
        )
        MealCard(
            title = stringResource(R.string.report_lunch),
            items = detail.meals.lunch,
            total = detail.calories.lunch,
            macros = detail.mealMacros.lunch,
            description = detail.descriptions.lunch,
            editable = editable,
            onItemClick = { i, item -> onEditItem(ReportEditTarget(ReportSection.LUNCH, i, item)) }
        )
        MealCard(
            title = stringResource(R.string.report_dinner),
            items = detail.meals.dinner,
            total = detail.calories.dinner,
            macros = detail.mealMacros.dinner,
            description = detail.descriptions.dinner,
            editable = editable,
            onItemClick = { i, item -> onEditItem(ReportEditTarget(ReportSection.DINNER, i, item)) }
        )
        MealCard(
            title = stringResource(R.string.report_snack),
            items = detail.meals.snack,
            total = detail.calories.snack,
            macros = detail.mealMacros.snack,
            description = detail.descriptions.snack,
            editable = editable,
            onItemClick = { i, item -> onEditItem(ReportEditTarget(ReportSection.SNACK, i, item)) }
        )

        ExerciseCard(
            items = detail.exercises,
            total = detail.calories.exercise,
            editable = editable,
            onItemClick = { i, item -> onEditItem(ReportEditTarget(ReportSection.EXERCISE, i, item)) }
        )

        GoalCard(detail)

        if (detail.evaluation.isNotBlank()) {
            DnCard {
                Text(
                    text = stringResource(R.string.report_eval_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                QuoteBlock(text = detail.evaluation)
            }
        }
        // 결산 한 줄. 기초대사가 얼마나 들어갔는지는 여기서만 밝힌다
        Text(
            text = stringResource(
                if (detail.bmrSource == "inbody") R.string.report_bmr_note_inbody else R.string.report_bmr_note,
                detail.bmr
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/** 맨 위: IN / OUT / NET 세 칸 + 권장 섭취 대비 진행 바 */
@Composable
private fun SummaryStrip(detail: AnalysisDetail) {
    val net = DnTheme.net
    val intake = detail.totals.intake
    val rec = detail.recommended.calories
    val ratio = if (rec <= 0) 0f else (intake.toFloat() / rec).coerceIn(0f, 1f)
    val over = rec > 0 && intake > rec

    DnCard(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                label = stringResource(R.string.report_summary_in),
                value = String.format(Locale.getDefault(), "%,d", intake),
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.report_summary_out),
                value = String.format(Locale.getDefault(), "%,d", detail.totals.burned),
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.report_summary_net),
                value = String.format(Locale.getDefault(), "%,d", detail.totals.net),
                valueColor = net.of(detail.totals.net),
                modifier = Modifier.weight(1f)
            )
        }
        if (rec > 0) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.report_intake_vs, rec),
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(if (over) R.string.report_over else R.string.report_under),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (over) net.surplus else net.deficit
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DnDimens.BarThin)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (over) net.coral else net.mint)
                )
            }
        }
    }
}

/** 끼니 카드 한 장: 헤더(끼니명 + kcal) → 항목 → 탄단지 스택 바 + 칩 → AI 한줄평 */
@Composable
private fun MealCard(
    title: String,
    items: List<AnalysisItem>,
    total: Int,
    macros: MacroSet,
    description: String,
    editable: Boolean,
    onItemClick: (Int, AnalysisItem) -> Unit
) {
    val net = DnTheme.net
    DnCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            KcalValue(
                value = total,
                style = MaterialTheme.typography.titleLarge,
                color = net.deficit,
                withUnit = true,
                unitStyle = MaterialTheme.typography.titleSmall
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.report_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            items.forEachIndexed { i, item ->
                ItemRow(item = item, sign = "", editable = editable, onClick = { onItemClick(i, item) })
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MacroStackBar(
                carb = macros.carb, protein = macros.protein, fat = macros.fat,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            MacroChipRow(
                carbLabel = stringResource(R.string.macro_carb),
                proteinLabel = stringResource(R.string.macro_protein),
                fatLabel = stringResource(R.string.macro_fat),
                carb = macros.carb, protein = macros.protein, fat = macros.fat
            )
        }
        if (description.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(net.mint)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 운동 카드: 제목 + 합계, 항목마다 왼쪽 민트 바 · 이름 · 시간/강도 · -kcal */
@Composable
private fun ExerciseCard(
    items: List<AnalysisItem>,
    total: Int,
    editable: Boolean,
    onItemClick: (Int, AnalysisItem) -> Unit
) {
    DnCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.report_exercise_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.report_exercise_sum, total),
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.report_no_exercise),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            items.forEachIndexed { i, item ->
                ItemRow(
                    item = item,
                    sign = "-",
                    editable = editable,
                    accentBar = true,
                    onClick = { onItemClick(i, item) }
                )
            }
        }
    }
}

/** 목표 가이드: 탄·단·지 권장량 대비 진행 바 셋 */
@Composable
private fun GoalCard(detail: AnalysisDetail) {
    val net = DnTheme.net
    val rec = detail.recommended
    DnCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.report_goal_guide),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.report_vs_recommended),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        MacroProgressRow(
            label = stringResource(R.string.macro_carb_full),
            value = detail.macros.carb, recommended = rec.carb, barColor = net.carb
        )
        Spacer(modifier = Modifier.height(12.dp))
        MacroProgressRow(
            label = stringResource(R.string.macro_protein_full),
            value = detail.macros.protein, recommended = rec.protein, barColor = net.protein
        )
        Spacer(modifier = Modifier.height(12.dp))
        MacroProgressRow(
            label = stringResource(R.string.macro_fat_full),
            value = detail.macros.fat, recommended = rec.fat, barColor = net.fat
        )
    }
}

/**
 * 항목 한 줄. 이름 · 양(옅게) · kcal. 행 전체가 눌려 kcal 수정 창이 뜬다 (연필 아이콘 하나보다 훨씬 넓다).
 * 사용자가 고친 항목은 AI가 냈던 값을 작게 같이 보여줘서 무엇을 얼마나 고쳤는지 보이게 한다.
 *
 * @param accentBar 운동 항목처럼 왼쪽에 민트 바를 세울지
 */
@Composable
private fun ItemRow(
    item: AnalysisItem,
    sign: String,
    editable: Boolean,
    accentBar: Boolean = false,
    onClick: () -> Unit
) {
    val net = DnTheme.net
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(DnDimens.RadiusChip))
        .let { if (editable) it.clickable(onClick = onClick) else it }
        .padding(vertical = 6.dp)

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (accentBar) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(net.mint)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            val detailText = itemDetailText(item)
            if (detailText.isNotBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = sign + String.format(Locale.getDefault(), "%,d", item.kcal),
                style = MaterialTheme.typography.titleMedium.tabular(),
                color = when {
                    item.isEdited -> MaterialTheme.colorScheme.tertiary
                    sign == "-" -> net.deficit
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            if (item.isEdited) {
                Text(
                    text = stringResource(R.string.report_ai_kcal, item.aiKcal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 메뉴면 "150g", 운동이면 "1시간 · 4.3 METs". 둘 다 없으면 빈 문자열 */
@Composable
private fun itemDetailText(item: AnalysisItem): String {
    val time = formatMinutes(
        item.minutes,
        stringResource(R.string.unit_hour),
        stringResource(R.string.unit_minute)
    )
    val mets = if (item.mets > 0f) stringResource(
        R.string.report_exercise_mets,
        String.format(Locale.getDefault(), "%.1f", item.mets)
    ) else ""
    val parts = listOf(item.amount, time, mets).filter { it.isNotBlank() }
    return parts.joinToString(" · ")
}

/**
 * b33 이전 기록(구조 없음)의 텍스트 리포트. 이모지·대괄호 장식만 걷어내고 카드 안에 넣는다.
 * 숫자를 고칠 수는 없으니 "이전 형식" 안내 한 줄을 붙인다.
 */
@Composable
fun LegacyReport(text: String) {
    DnCard {
        Text(
            text = stringResource(R.string.report_legacy_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stripReportDecorations(text),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 옛 텍스트 리포트의 이모지·대괄호·불릿을 걷어낸다. 글 내용은 손대지 않는다 */
internal fun stripReportDecorations(text: String): String {
    val emoji = Regex("[\\p{So}\\p{Cn}\\uFE0F\\u200D]")
    return text.lines().joinToString("\n") { line ->
        line.replace(emoji, "")
            .replace("[", "").replace("]", "")
            .replace(Regex("^\\s*[•·]\\s*"), "  ")
            .trimEnd()
    }.replace(Regex("\n{3,}"), "\n\n").trim()
}

/**
 * kcal 수정 창. 이름은 못 고친다 — 항목을 바꾸고 싶으면 입력을 고쳐 다시 분석하는 게 맞다.
 * 여기서 고치는 건 "AI가 양을 잘못 잡았다"를 바로잡는 것뿐이다.
 */
@Composable
fun EditKcalDialog(
    target: ReportEditTarget,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(target) { mutableStateOf(target.item.kcal.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(DnDimens.RadiusHero),
        title = { Text(stringResource(R.string.edit_kcal_title)) },
        text = {
            Column {
                Text(target.item.name, style = MaterialTheme.typography.titleMedium)
                if (target.item.aiKcal > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.report_ai_kcal, target.item.aiKcal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.edit_kcal_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(DnDimens.RadiusChip),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
