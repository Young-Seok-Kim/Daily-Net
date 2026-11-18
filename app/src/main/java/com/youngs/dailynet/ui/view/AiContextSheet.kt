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
 *
 * 같은 칸을 가입(온보딩) 2단계 시트([AiContextOnboardingSheet])도 쓴다. 그쪽은 처음 한 번
 * 빈 값에서 시작하고, 건너뛰기와 '저장하고 시작'을 둔다.
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
        AiContextFields(
            goal = goal, onGoal = { goal = it },
            activity = activity, onActivity = { activity = it },
            note = note, onNote = { note = it }
        )
    }
}

/**
 * 가입(온보딩) 2단계 시트. 신체 정보를 저장한 직후 한 번 열린다.
 *
 * 칸은 설정의 [AiContextSheet]와 **같은 것**을 쓴다([AiContextFields]). 두 곳의 칸이 갈라지면
 * 같은 값을 두 화면에서 다르게 고르게 된다. 다른 점은 셋뿐이다.
 *  - 처음 여는 자리라 빈 값에서 시작한다
 *  - 선택 단계이므로 닫기·건너뛰기가 곧 '아무것도 저장하지 않음'이다
 *  - 주액션이 '저장하고 시작' — 저장에 성공해야 시트가 닫힌다
 *
 * @param onSkip 건너뛰기(닫기 포함). 아무것도 저장하지 않는다
 * @param onSave 저장을 누르면 정리된 값과 함께 돌려준다. 시트를 닫는 건 부른 쪽 몫이다
 */
@Composable
fun AiContextOnboardingSheet(
    onSkip: () -> Unit,
    onSave: (goal: AiGoal?, activity: AiActivity?, note: String) -> Unit
) {
    var goal by remember { mutableStateOf<AiGoal?>(null) }
    var activity by remember { mutableStateOf<AiActivity?>(null) }
    var note by remember { mutableStateOf("") }

    DnFormSheet(
        title = stringResource(R.string.ai_context_setup_title),
        subtitle = stringResource(R.string.ai_context_setup_subtitle),
        onDismiss = onSkip,
        primaryLabel = stringResource(R.string.ai_context_setup_save),
        onPrimary = { onSave(goal, activity, AiContext.normalizeNote(note)) },
        secondaryLabel = stringResource(R.string.ai_context_setup_skip),
        onSecondary = onSkip
    ) {
        AiContextFields(
            goal = goal, onGoal = { goal = it },
            activity = activity, onActivity = { activity = it },
            note = note, onNote = { note = it }
        )
    }
}

/**
 * 목표·활동량·메모를 고르는 칸 묶음. 설정 시트와 가입 2단계 시트가 함께 쓴다.
 * 값은 들고 있지 않고 위에서 내려준다(두 시트가 각자 기억을 가진다).
 */
@Composable
private fun AiContextFields(
    goal: AiGoal?,
    onGoal: (AiGoal?) -> Unit,
    activity: AiActivity?,
    onActivity: (AiActivity?) -> Unit,
    note: String,
    onNote: (String) -> Unit
) {
    AiChoiceGroup(
        label = stringResource(R.string.ai_context_goal),
        options = AiGoal.entries.map { it to stringResource(it.labelRes) },
        selected = goal,
        onSelect = onGoal
    )
    AiChoiceGroup(
        label = stringResource(R.string.ai_context_activity),
        options = AiActivity.entries.map { it to stringResource(it.labelRes) },
        selected = activity,
        onSelect = onActivity
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
            onValueChange = { onNote(AiContext.limitNote(it)) },
            placeholder = { Text(stringResource(R.string.ai_context_note_hint)) },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(DnDimens.RadiusChip),
            minLines = 4,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
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
