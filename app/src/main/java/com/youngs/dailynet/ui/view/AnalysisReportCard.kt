package com.youngs.dailynet.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youngs.dailynet.R
import com.youngs.dailynet.data.model.AnalysisDetail
import com.youngs.dailynet.data.model.AnalysisItem
import com.youngs.dailynet.data.model.MacroSet
import com.youngs.dailynet.util.ReportSection
import java.util.Locale

/** 사용자가 고치려고 누른 항목 */
data class ReportEditTarget(
    val section: ReportSection,
    val index: Int,
    val item: AnalysisItem
)

/**
 * 분석 결과를 구조(AnalysisDetail)에서 그린 리포트.
 *
 * 서버가 조립해 주던 텍스트 리포트와 같은 순서·같은 내용이다. 텍스트 대신 구조로 그리는 이유는
 * 하나다 — 항목을 눌러 kcal을 고칠 수 있어야 하고, 고치면 합계·결산이 같이 바뀌어야 한다.
 * 텍스트는 숫자 하나를 바꾸면 같은 글 안의 합계와 어긋난다.
 *
 * b33 이전에 분석한 기록은 이 구조가 없어 DailyRecordScreen이 텍스트를 그대로 보여준다.
 */
@Composable
fun AnalysisReport(
    detail: AnalysisDetail,
    edited: Boolean,
    editable: Boolean,
    onEditItem: (ReportEditTarget) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (edited || editable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (edited) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.report_edited_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
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
            Spacer(modifier = Modifier.height(12.dp))
        }

        MealBlock(
            title = stringResource(R.string.report_breakfast),
            items = detail.meals.breakfast,
            total = detail.calories.breakfast,
            macros = detail.mealMacros.breakfast,
            description = detail.descriptions.breakfast,
            editable = editable,
            onItemClick = { i, item -> onEditItem(ReportEditTarget(ReportSection.BREAKFAST, i, item)) }
        )
        MealBlock(
            title = stringResource(R.string.report_lunch),
            items = detail.meals.lunch,
            total = detail.calories.lunch,
            macros = detail.mealMacros.lunch,
            description = detail.descriptions.lunch,
            editable = editable,
            onItemClick = { i, item -> onEditItem(ReportEditTarget(ReportSection.LUNCH, i, item)) }
        )
        MealBlock(
            title = stringResource(R.string.report_dinner),
            items = detail.meals.dinner,
            total = detail.calories.dinner,
            macros = detail.mealMacros.dinner,
            description = detail.descriptions.dinner,
            editable = editable,
            onItemClick = { i, item -> onEditItem(ReportEditTarget(ReportSection.DINNER, i, item)) }
        )
        MealBlock(
            title = stringResource(R.string.report_snack),
            items = detail.meals.snack,
            total = detail.calories.snack,
            macros = detail.mealMacros.snack,
            description = detail.descriptions.snack,
            editable = editable,
            onItemClick = { i, item -> onEditItem(ReportEditTarget(ReportSection.SNACK, i, item)) }
        )

        // 운동
        SectionTitle(
            stringResource(R.string.report_exercise_title) + " " +
                stringResource(R.string.report_exercise_total, detail.calories.exercise)
        )
        if (detail.exercises.isEmpty()) {
            BodyLine("   • " + stringResource(R.string.report_no_exercise))
        } else {
            detail.exercises.forEachIndexed { i, item ->
                ItemRow(
                    item = item,
                    sign = "-",
                    editable = editable,
                    onClick = { onEditItem(ReportEditTarget(ReportSection.EXERCISE, i, item)) }
                )
            }
        }

        ReportDivider()

        // 목표
        val rec = detail.recommended
        val intake = detail.totals.intake
        SectionTitle(stringResource(R.string.report_goal_title))
        BodyLine(stringResource(R.string.report_goal_intake, rec.calories))
        BodyLine(stringResource(R.string.report_goal_macro, rec.carb, rec.protein, rec.fat))
        BodyLine(
            stringResource(
                R.string.report_my_intake,
                intake,
                stringResource(if (intake > rec.calories) R.string.report_over else R.string.report_under)
            )
        )
        Spacer(modifier = Modifier.height(10.dp))

        SectionTitle(stringResource(R.string.report_macro_title))
        BodyLine(stringResource(R.string.report_carb_full, f1(detail.macros.carb), rec.carb))
        BodyLine(stringResource(R.string.report_protein_full, f1(detail.macros.protein), rec.protein))
        BodyLine(stringResource(R.string.report_fat_full, f1(detail.macros.fat), rec.fat))

        ReportDivider()

        BodyLine(stringResource(R.string.report_in, intake), bold = true)
        BodyLine(stringResource(R.string.report_out, detail.totals.burned, detail.bmr), bold = true)
        BodyLine(stringResource(R.string.report_net, detail.totals.net), bold = true)

        if (detail.evaluation.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            SectionTitle(stringResource(R.string.report_eval_title))
            BodyLine(detail.evaluation)
        }
    }
}

@Composable
private fun MealBlock(
    title: String,
    items: List<AnalysisItem>,
    total: Int,
    macros: MacroSet,
    description: String,
    editable: Boolean,
    onItemClick: (Int, AnalysisItem) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.report_total) + " " + stringResource(R.string.kcal_value, total),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
    if (items.isEmpty()) {
        BodyLine("   " + stringResource(R.string.report_no_info))
    } else {
        items.forEachIndexed { i, item ->
            ItemRow(item = item, sign = "", editable = editable, onClick = { onItemClick(i, item) })
        }
    }
    BodyLine(
        "   " + stringResource(R.string.report_macro_line, f1(macros.carb), f1(macros.protein), f1(macros.fat)),
        small = true
    )
    if (description.isNotBlank()) {
        BodyLine("   💡 $description")
    }
    Spacer(modifier = Modifier.height(12.dp))
}

/**
 * 항목 한 줄. 누르면 kcal 수정 창이 뜬다.
 * 사용자가 고친 항목은 AI가 냈던 값을 작게 같이 보여줘서 무엇을 얼마나 고쳤는지 보이게 한다.
 */
@Composable
private fun ItemRow(
    item: AnalysisItem,
    sign: String,
    editable: Boolean,
    onClick: () -> Unit
) {
    val rowModifier = if (editable) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    }
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "   • ${item.name}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = sign + stringResource(R.string.kcal_value, item.kcal),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.isEdited) FontWeight.Bold else FontWeight.Normal,
                color = if (item.isEdited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            if (item.isEdited) {
                Text(
                    text = stringResource(R.string.report_ai_kcal, item.aiKcal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (editable) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.edit_kcal_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
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
        title = { Text(stringResource(R.string.edit_kcal_title)) },
        text = {
            Column {
                Text(target.item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

@Composable
private fun BodyLine(text: String, bold: Boolean = false, small: Boolean = false) {
    Text(
        text = text,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        lineHeight = 22.sp
    )
}

@Composable
private fun ReportDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    )
}

private fun f1(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)
