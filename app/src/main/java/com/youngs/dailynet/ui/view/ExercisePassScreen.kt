package com.youngs.dailynet.ui.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import com.youngs.dailynet.data.local.entity.ExercisePassEntity
import com.youngs.dailynet.ui.theme.DnDimens
import com.youngs.dailynet.ui.theme.DnTheme
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import com.youngs.dailynet.util.PassUsage
import com.youngs.dailynet.util.passMatches
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 운동 이용권 목록·등록 화면. 설정 > 내 정보 > 운동 이용권에서 들어온다.
 *
 * 헬스장 3개월 같은 **기간제**와 PT 10회 같은 **횟수권**을 여러 장 둔다.
 * 횟수권은 정산의 운동 칸에 키워드가 들어간 날을 자동으로 1회로 센다 (util/PassUsage.kt).
 * 잘못 세어진 건 카드의 +1/−1로 보정한다. 쓸 수 있는 것이 위, 끝난 것은 흐리게 아래.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePassScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit
) {
    val usages by mainViewModel.passUsages.collectAsState()
    val today = mainViewModel.todayDate

    // 들어올 때마다 계정 쪽을 한 번 맞춘다 (다른 기기에서 넣은 것이 보이도록)
    LaunchedEffect(Unit) { mainViewModel.syncPasses() }

    BackHandler(enabled = true) { onBack() }

    var editing by remember { mutableStateOf<ExercisePassEntity?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }

    editing?.let { target ->
        PassEditDialog(
            initial = target,
            isNew = editingIsNew,
            onSave = { pass ->
                mainViewModel.savePass(pass)
                editing = null
            },
            onDelete = {
                mainViewModel.deletePass(target.id)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pass_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // id는 저장할 때 이름으로 만든다 (PassEditDialog). 여기서는 자리만
                    editing = ExercisePassEntity(id = "", startDate = today)
                    editingIsNew = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.pass_add)) }
            )
        }
    ) { padding ->
        if (usages.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.pass_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        // 쓸 수 있는 것 먼저. 그 안에서는 끝이 가까운 순(종료일 없는 건 뒤)
        val sorted = remember(usages) {
            usages.sortedWith(
                compareBy<PassUsage>({ !it.active }, { it.pass.endDate.ifBlank { "9999" } }, { it.pass.name })
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(sorted, key = { it.pass.id }) { usage ->
                PassCard(
                    usage = usage,
                    today = today,
                    onClick = {
                        editing = usage.pass
                        editingIsNew = false
                    },
                    onAdjust = { delta -> mainViewModel.savePass(usage.pass.copy(adjust = usage.pass.adjust + delta)) }
                )
            }
        }
    }
}

/** 남은 양을 한 토막으로. "14일 남음" / "6회 남음" / "기간 종료" / "다 썼음" */
@Composable
fun passRemainingText(usage: PassUsage): String = when {
    usage.expired -> stringResource(R.string.pass_expired)
    usage.exhausted -> stringResource(R.string.pass_exhausted)
    usage.remaining != null -> stringResource(R.string.pass_sessions_left, usage.remaining)
    usage.daysLeft != null -> stringResource(R.string.pass_days_left, usage.daysLeft)
    else -> ""
}

@Composable
private fun PassCard(
    usage: PassUsage,
    today: String,
    onClick: () -> Unit,
    onAdjust: (Int) -> Unit
) {
    val pass = usage.pass
    val net = DnTheme.net
    val dim = !usage.active
    DnCard(
        modifier = Modifier.clickable(onClick = onClick),
        containerColor = if (dim) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surface
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pass.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(if (pass.isCount) R.string.pass_kind_count else R.string.pass_kind_period) +
                        periodSuffix(pass),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = passRemainingText(usage),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    dim -> MaterialTheme.colorScheme.onSurfaceVariant
                    usage.notStarted(today) -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> net.deficit
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 진행 막대. 횟수권은 쓴 횟수/전체, 기간제는 지난 날/전체
        val progress = when {
            pass.isCount && pass.totalCount > 0 -> usage.used.toFloat() / pass.totalCount
            usage.totalDays != null && usage.totalDays > 0 && usage.daysLeft != null ->
                (usage.totalDays - usage.daysLeft).toFloat() / usage.totalDays
            else -> 0f
        }.coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (dim) MaterialTheme.colorScheme.outline else net.mint,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        if (pass.isCount) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.pass_used_of, usage.used, pass.totalCount) +
                        if (pass.adjust != 0) "  (${stringResource(R.string.pass_adjusted, pass.adjust)})" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                // 자동으로 잘못 센 날을 바로잡는 버튼. 저장된 건 보정값(adjust)뿐이다
                TextButton(onClick = { onAdjust(-1) }, enabled = usage.used > 0) { Text("−1") }
                TextButton(onClick = { onAdjust(+1) }) { Text("+1") }
            }
            if (pass.keyword.isBlank()) {
                Text(
                    text = stringResource(R.string.pass_no_keyword),
                    style = MaterialTheme.typography.labelSmall,
                    color = net.coral
                )
            }
        }
    }
}

/** " · 11-01 ~ 11-30" 꼴. 종료일이 없으면 " · 11-01 ~". 시작일도 없으면 빈 문자열 */
private fun periodSuffix(pass: ExercisePassEntity): String {
    if (pass.startDate.isBlank() && pass.endDate.isBlank()) return ""
    val s = pass.startDate.drop(5)
    val e = pass.endDate.drop(5)
    return " · $s ~ $e"
}

/**
 * 홈 화면 누적 결산 카드 밑의 한 줄. 쓸 수 있는 이용권만 "헬스 14일 · PT 6회"로 늘어놓는다.
 * 누르면 이용권 화면. 쓸 수 있는 게 없으면 그리지 않는다.
 */
@Composable
fun PassSummaryRow(usages: List<PassUsage>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = usages.filter { it.active }
    if (active.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DnDimens.RadiusTile))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.pass_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            // map은 inline이라 안에서 stringResource를 불러도 된다 (joinToString의 변환 람다는 아니다)
            text = active.map { "${it.pass.name} ${passRemainingText(it)}" }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 정산 입력의 운동 칸 밑 한 줄. 쓸 수 있는 이용권의 남은 양을 보여주고,
 * 지금 적은 운동에 키워드가 들어 있으면 "오늘 차감"을 붙여 자동으로 세고 있음을 알린다.
 */
@Composable
fun PassHintRow(usages: List<PassUsage>, exerciseText: String, modifier: Modifier = Modifier) {
    val active = usages.filter { it.active }
    if (active.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        active.forEach { usage ->
            val matched = usage.pass.isCount && passMatches(usage.pass, exerciseText)
            Text(
                text = buildString {
                    append(usage.pass.name)
                    append(" · ")
                    append(passRemainingText(usage))
                    if (matched) {
                        append(" · ")
                        append(stringResource(R.string.pass_today_deducted))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (matched) DnTheme.net.deficit else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun showDatePicker(context: android.content.Context, initial: String, onPicked: (String) -> Unit) {
    val cal = Calendar.getInstance()
    runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(initial) }.getOrNull()?.let { cal.time = it }
    android.app.DatePickerDialog(
        context,
        { _, year, month, day -> onPicked(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)) },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    ).show()
}

/** 이용권 등록·수정 시트. 기간제/횟수권에 따라 칸이 달라진다. 삭제는 CTA 위 구분선 아래 행 → 확인은 DnDialog */
@Composable
private fun PassEditDialog(
    initial: ExercisePassEntity,
    isNew: Boolean,
    onSave: (ExercisePassEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var isCount by remember { mutableStateOf(initial.isCount) }
    var startDate by remember { mutableStateOf(initial.startDate) }
    var endDate by remember { mutableStateOf(initial.endDate) }
    var totalText by remember { mutableStateOf(if (initial.totalCount > 0) initial.totalCount.toString() else "") }
    var keyword by remember { mutableStateOf(initial.keyword) }
    var confirmDelete by remember { mutableStateOf(false) }

    val total = totalText.toIntOrNull() ?: 0
    val valid = name.isNotBlank() && startDate.isNotBlank() &&
        if (isCount) total > 0 else endDate.isNotBlank() && endDate >= startDate

    if (confirmDelete) {
        DnDialog(
            title = stringResource(R.string.pass_delete_title),
            body = stringResource(R.string.pass_delete_confirm),
            tone = DnDialogTone.Destructive,
            icon = Icons.Default.Delete,
            targetLabel = stringResource(R.string.delete_target),
            targetValue = name,
            primaryLabel = stringResource(R.string.delete),
            onPrimary = onDelete,
            onDismiss = { confirmDelete = false }
        )
    }

    DnFormSheet(
        title = stringResource(if (isNew) R.string.pass_add else R.string.pass_edit),
        onDismiss = onDismiss,
        primaryLabel = stringResource(R.string.save),
        primaryEnabled = valid,
        destructiveLabel = if (isNew) null else stringResource(R.string.pass_delete),
        onDestructive = { confirmDelete = true },
        onPrimary = {
            onSave(
                initial.copy(
                    // 새 이용권이면 지금 적은 이름으로 id를 만든다. 수정이면 그대로
                    id = if (isNew) ExercisePassEntity.newId(name) else initial.id,
                    name = name.trim(),
                    kind = if (isCount) ExercisePassEntity.KIND_COUNT else ExercisePassEntity.KIND_PERIOD,
                    startDate = startDate,
                    endDate = endDate,
                    totalCount = if (isCount) total else 0,
                    keyword = if (isCount) keyword.trim() else ""
                )
            )
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !isCount,
                onClick = { isCount = false },
                label = { Text(stringResource(R.string.pass_kind_period)) }
            )
            FilterChip(
                selected = isCount,
                onClick = { isCount = true },
                label = { Text(stringResource(R.string.pass_kind_count)) }
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.pass_name)) },
            placeholder = { Text(stringResource(R.string.pass_name_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(DnDimens.RadiusChip),
            modifier = Modifier.fillMaxWidth()
        )

        DateField(
            label = stringResource(R.string.pass_start),
            value = startDate,
            onClick = { showDatePicker(context, startDate) { startDate = it } }
        )
        DateField(
            label = stringResource(if (isCount) R.string.pass_end_optional else R.string.pass_end),
            value = endDate,
            onClick = { showDatePicker(context, endDate.ifBlank { startDate }) { endDate = it } },
            onClear = if (isCount && endDate.isNotBlank()) ({ endDate = "" }) else null
        )

        if (isCount) {
            OutlinedTextField(
                value = totalText,
                onValueChange = { totalText = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.pass_total)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = RoundedCornerShape(DnDimens.RadiusChip),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text(stringResource(R.string.pass_keyword)) },
                placeholder = { Text(stringResource(R.string.pass_keyword_placeholder)) },
                supportingText = { Text(stringResource(R.string.pass_keyword_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(DnDimens.RadiusChip),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 누르면 날짜 선택 창이 뜨는 읽기 전용 칸. [onClear]가 있으면 끝에 지우기 버튼 */
@Composable
private fun DateField(label: String, value: String, onClick: () -> Unit, onClear: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                label = { Text(label) },
                readOnly = true,
                enabled = false,
                singleLine = true,
                shape = RoundedCornerShape(DnDimens.RadiusChip),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )
            // 비활성 입력칸은 터치를 먹지 않아 위에 투명 판을 깔아 누르게 한다
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = onClick)
            )
        }
        // 지우기는 판 바깥에 둔다. 판 아래 있으면 눌리지 않는다
        onClear?.let { clear ->
            TextButton(onClick = clear) { Text(stringResource(R.string.pass_clear_date)) }
        }
    }
}
