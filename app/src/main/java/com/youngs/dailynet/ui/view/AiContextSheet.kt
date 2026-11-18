package com.youngs.dailynet.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import com.youngs.dailynet.data.model.AiActivity
import com.youngs.dailynet.data.model.AiContext
import com.youngs.dailynet.data.model.AiGoal
import com.youngs.dailynet.ui.theme.DnDimens

/*
 * 설정 > 내 정보 > 'AI 분석 참고 정보'.
 *
 * 기획 시안(drafts/assets/dailynet-ai-context-mockup.png)의 시트를 앱의 [DnFormSheet] 규격으로 옮겼다.
 * 시안은 아래에 [취소 | 저장]을 나란히 두었지만, 앱의 입력 시트는 모두 오른쪽 위 '닫기' + 하단 전체 폭
 * '저장'이다(신체 정보 수정·이용권 추가와 같다). 한 화면 안에서 버튼 자리가 달라지지 않게 앱 쪽을 따랐다.
 *
 * 칩은 고른 것을 다시 누르면 해제된다. 목표를 정하지 않은 사람도 있으므로 '선택 안 함' 칩을
 * 따로 두지 않고 같은 동작으로 처리했다 (시안에도 그런 칩은 없다).
 */

/** 화면에 보이는 말. 저장·전송에는 코드만 쓰므로 언어는 여기서만 탄다 */
val AiGoal.labelRes: Int
    get() = when (this) {
        AiGoal.LOSE -> R.string.ai_context_goal_lose
        AiGoal.MAINTAIN -> R.string.ai_context_goal_maintain
        AiGoal.GAIN -> R.string.ai_context_goal_gain
    }

val AiActivity.labelRes: Int
    get() = when (this) {
        AiActivity.SEDENTARY -> R.string.ai_context_activity_sedentary
        AiActivity.ACTIVE -> R.string.ai_context_activity_active
        AiActivity.LABOR -> R.string.ai_context_activity_labor
    }

/**
 * 설정 줄의 부제목. 정한 것만 "체중 감량 · 사무직·좌식 · 메모 있음"처럼 잇고,
 * 아무것도 없으면 무엇을 정하면 되는지 안내한다.
 *
 * 메모는 내용을 그대로 넣지 않는다. 200자까지라 줄이 넘치고, 민감한 말이 설정 목록에
 * 늘 비치게 된다. 정했는지만 알린다.
 */
@Composable
fun aiContextSummary(context: AiContext): String {
    val parts = listOfNotNull(
        context.goal?.let { stringResource(it.labelRes) },
        context.activity?.let { stringResource(it.labelRes) },
        if (context.note.isNotBlank()) stringResource(R.string.ai_context_summary_note) else null
    )
    return if (parts.isEmpty()) stringResource(R.string.settings_ai_context_empty)
    else parts.joinToString(" · ")
}

/**
 * 목표·활동량·메모를 고치는 시트.
 *
 * @param initial 지금 저장된 값. 시트를 열 때마다 그 값에서 시작한다
 * @param onSave 저장을 누르면 정리된 메모와 함께 돌려준다. 시트를 닫는 건 부른 쪽 몫이다
 */
@Composable
fun AiContextSheet(
    initial: AiContext,
    onDismiss: () -> Unit,
    onSave: (goal: AiGoal?, activity: AiActivity?, note: String) -> Unit
) {
    var goal by remember { mutableStateOf(initial.goal) }
    var activity by remember { mutableStateOf(initial.activity) }
    var note by remember { mutableStateOf(initial.note) }

    DnFormSheet(
        title = stringResource(R.string.ai_context_title),
        subtitle = stringResource(R.string.ai_context_subtitle),
        onDismiss = onDismiss,
        primaryLabel = stringResource(R.string.save),
        onPrimary = { onSave(goal, activity, AiContext.normalizeNote(note)) }
    ) {
        AiChoiceGroup(
            label = stringResource(R.string.ai_context_goal),
            options = AiGoal.entries.map { it to stringResource(it.labelRes) },
            selected = goal,
            onSelect = { goal = it }
        )
        AiChoiceGroup(
            label = stringResource(R.string.ai_context_activity),
            options = AiActivity.entries.map { it to stringResource(it.labelRes) },
            selected = activity,
            onSelect = { activity = it }
        )
        Text(
            text = stringResource(R.string.ai_context_chip_clear_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ai_context_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                // 남은 글자를 세는 대신 지금까지 쓴 수를 보여준다 (시안과 같다)
                Text(
                    text = stringResource(R.string.ai_context_note_counter, note.length, AiContext.NOTE_MAX),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = note,
                // 상한을 넘겨 적으면 그 자리에서 잘린다. 저장할 때가 아니라 적는 동안 막아야
                // 카운터가 200에서 멈춰 있는 걸 보고 사용자가 바로 안다
                onValueChange = { note = AiContext.limitNote(it) },
                placeholder = { Text(stringResource(R.string.ai_context_note_hint)) },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(DnDimens.RadiusChip),
                minLines = 4,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 제목 한 줄 + 고를 수 있는 칩들. 고른 칩을 다시 누르면 해제된다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> AiChoiceGroup(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T?) -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, text) ->
                val isSelected = selected == value
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelect(if (isSelected) null else value) }
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
