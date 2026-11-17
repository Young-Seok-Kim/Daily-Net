package com.youngs.dailynet.ui.view

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.ui.viewmodel.MainViewModel.DateRange
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.R
import com.youngs.dailynet.data.calendar.GoogleCalendarSync
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import com.youngs.dailynet.util.DailyReminder

/**
 * 설정 화면.
 *
 * 회원탈퇴처럼 되돌릴 수 없는 항목은 메인에서 한 번에 닿지 않도록 여기 "위험 구역"에 모아둔다.
 * 앞으로 항목이 늘어날 것을 감안해, 섹션([SettingsSection]) + 항목([SettingsItem]) 조합으로
 * 줄만 추가하면 되도록 구성했다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToWithdraw: () -> Unit,
    onNavigateToInbody: () -> Unit,
    onNavigateToPasses: () -> Unit = {}
) {
    val context = LocalContext.current
    val userProfile by mainViewModel.userProfile.collectAsState()
    val inbodyRecords by mainViewModel.inbodyRecords.collectAsState()

    var showProfileEditDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf<String?>(null) }

    var reminderEnabled by remember { mutableStateOf(DailyReminder.isEnabled(context)) }
    val permissionDeniedMessage = stringResource(R.string.reminder_permission_denied)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 거부하면 켜지 않는다. 알림이 오지 않는데 켜져 있는 것처럼 보이면 더 혼란스럽다.
        if (granted) {
            DailyReminder.setEnabled(context, true)
            reminderEnabled = true
        } else {
            Toast.makeText(context, permissionDeniedMessage, Toast.LENGTH_LONG).show()
        }
    }

    // ── 구글 캘린더 연동 ──────────────────────────────────────────
    var calendarEnabled by remember { mutableStateOf(mainViewModel.isCalendarSyncEnabled()) }
    var calendarTitleByContent by remember {
        mutableStateOf(mainViewModel.calendarTitleMode() != GoogleCalendarSync.TITLE_MODE_FIXED)
    }
    var calendarFixedTitle by remember { mutableStateOf(mainViewModel.calendarFixedTitle()) }
    var showCalendarTitleDialog by remember { mutableStateOf(false) }
    // "지금 동기화"를 눌렀는데 동의 화면부터 거쳐야 했던 경우. 동의가 끝나면 이어서 동기화한다.
    var syncAfterConsent by remember { mutableStateOf(false) }
    val calendarSyncingText = stringResource(R.string.calendar_sync_in_progress)
    val calendarConnectingText = stringResource(R.string.calendar_connecting)
    val calendarConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // 동의 화면에서 뒤로 가면 꺼진 상태 그대로 둔다. 안 켜졌는데 켜진 것처럼 보이면 안 된다.
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            syncAfterConsent = false
            return@rememberLauncherForActivityResult
        }
        // 동의 뒤 캘린더를 만들고 확인하는 몇 초 동안 화면을 잠근다. 연달아 누르면 캘린더가 여러 개 생긴다.
        loadingMessage = calendarConnectingText
        mainViewModel.finishCalendarConsent(result.data) { ok ->
            loadingMessage = null
            calendarEnabled = ok
            if (ok && syncAfterConsent) {
                syncAfterConsent = false
                loadingMessage = calendarSyncingText
                // 방금 승인됐으므로 동의 화면이 다시 뜰 일은 없다
                mainViewModel.syncCalendarNow(onNeedsConsent = {}, onDone = { loadingMessage = null })
            }
        }
    }
    val launchCalendarConsent: (android.app.PendingIntent) -> Unit = { pendingIntent ->
        calendarConsentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    // ViewModel이 띄운 토스트(캘린더 연결 결과 등)는 기록 화면만 보고 있어서 여기서도 받아야 보인다
    LaunchedEffect(mainViewModel.toastMessage) {
        mainViewModel.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            mainViewModel.onToastShown()
        }
    }

    // 시스템 뒤로가기로 앱이 종료되지 않고 이전 화면으로 돌아가도록
    BackHandler(enabled = true) { if (loadingMessage == null) onBack() }

    // ── 다이얼로그들 ────────────────────────────────────────────────
    // 프로필이 로드되기 전에는 열지 않는다 (빈 값으로 덮어쓰는 것 방지).
    // 조건에 넣어 두면 컴포지션 도중 상태를 쓰지 않아도 된다.
    userProfile?.takeIf { showProfileEditDialog }?.let { p ->
        ProfileEditDialog(
            initialHeight = p.height,
            initialWeight = p.initialWeight,
            initialIsMale = p.isMale,
            initialBirthDate = p.birthDate,
            onDismiss = { showProfileEditDialog = false },
            onConfirm = { h, w, male, birth ->
                mainViewModel.updateProfile(h, w, male, birth) { success ->
                    if (success) showProfileEditDialog = false
                }
            }
        )
    }

    if (showCalendarTitleDialog) {
        var draft by remember { mutableStateOf(calendarFixedTitle) }
        AlertDialog(
            onDismissRequest = { showCalendarTitleDialog = false },
            title = { Text(stringResource(R.string.settings_calendar_fixed_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.calendar_title_dialog_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = draft.trim()
                    if (title.isNotEmpty()) {
                        mainViewModel.setCalendarFixedTitle(title)
                        calendarFixedTitle = title
                    }
                    showCalendarTitleDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarTitleDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.logout)) },
            text = { Text(stringResource(R.string.logout_confirm_message)) },
            confirmButton = {
                val loadingText = stringResource(R.string.logout_in_progress)
                TextButton(onClick = {
                    showLogoutConfirm = false
                    loadingMessage = loadingText
                    mainViewModel.logout(context) { success ->
                        loadingMessage = null
                        if (success) onNavigateToLogin()
                    }
                }) { Text(stringResource(R.string.logout)) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // ── 본문 ──────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSection(stringResource(R.string.settings_section_my_info)) {
                    SettingsItem(
                        title = stringResource(R.string.settings_edit_body_info),
                        subtitle = userProfile?.let {
                            stringResource(
                                R.string.settings_body_info_summary,
                                it.height.toString(),
                                it.initialWeight.toString(),
                                it.birthDate
                            )
                        } ?: stringResource(R.string.loading),
                        enabled = userProfile != null,
                        onClick = { showProfileEditDialog = true }
                    )
                    SettingsItem(
                        title = stringResource(R.string.settings_inbody),
                        subtitle = inbodySummary(inbodyRecords.firstOrNull()?.toData()),
                        enabled = userProfile != null,
                        onClick = onNavigateToInbody
                    )
                    val passUsages by mainViewModel.passUsages.collectAsState()
                    val activePasses = passUsages.count { it.active }
                    SettingsItem(
                        title = stringResource(R.string.pass_title),
                        subtitle = if (activePasses > 0) stringResource(R.string.pass_active_count, activePasses)
                        else stringResource(R.string.settings_passes_subtitle),
                        enabled = userProfile != null,
                        onClick = onNavigateToPasses
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                SettingsSection(stringResource(R.string.settings_section_account)) {
                    SettingsItem(
                        title = stringResource(R.string.logout),
                        subtitle = stringResource(R.string.settings_logout_subtitle),
                        onClick = { showLogoutConfirm = true }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 표시 기간. 다이어트를 다시 시작한 날부터만 보고 싶을 때 켠다. 종료일을 비우면 오늘까지.
                val range by mainViewModel.dateRange.collectAsState()
                SettingsSection(stringResource(R.string.settings_section_range)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_range_enabled),
                        subtitle = stringResource(R.string.settings_range_enabled_subtitle),
                        checked = range.enabled,
                        onCheckedChange = { mainViewModel.setDateRange(range.copy(enabled = it)) }
                    )
                    // 시작일 ~ 종료일을 한 줄에. 각 칸을 누르면 그 날짜를 고른다.
                    // 종료일 칸을 길게 누르면 비워서 "오늘까지"로 돌아간다.
                    DateRangeRow(
                        // 시작일을 안 골랐으면 가입일이 기본값이다. 칸에도 그 날짜를 보여준다
                        start = range.start.ifBlank { mainViewModel.defaultStartDate() },
                        end = range.end,
                        onPickStart = {
                            showDatePicker(context, range.start.ifBlank { mainViewModel.defaultStartDate() }) { picked ->
                                mainViewModel.setDateRange(range.copy(start = picked, enabled = true))
                            }
                        },
                        onPickEnd = {
                            showDatePicker(context, range.end) { picked ->
                                mainViewModel.setDateRange(range.copy(end = picked, enabled = true))
                            }
                        },
                        onClearEnd = { mainViewModel.setDateRange(range.copy(end = "")) },
                        // 시작일을 길게 누르면 비워서 가입일로 돌아간다 (빈 값 = 가입일)
                        onClearStart = { mainViewModel.setDateRange(range.copy(start = "")) },
                        startIsDefault = range.start.isBlank()
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 정산 목록의 주·월·연 구분선. 연 구분선은 올해 첫 기록 위에 항상 나오므로
                // 거슬리면 여기서 끈다. 끄면 그 계층의 요약 패널과 AI 평가도 같이 사라진다.
                SettingsSection(stringResource(R.string.settings_section_list)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_show_year_divider),
                        subtitle = stringResource(R.string.settings_show_year_divider_subtitle),
                        checked = mainViewModel.showYearDivider,
                        onCheckedChange = { mainViewModel.setDividerShown(PeriodKind.YEAR, it) }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_show_month_divider),
                        subtitle = stringResource(R.string.settings_show_month_divider_subtitle),
                        checked = mainViewModel.showMonthDivider,
                        onCheckedChange = { mainViewModel.setDividerShown(PeriodKind.MONTH, it) }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_show_week_divider),
                        subtitle = stringResource(R.string.settings_show_week_divider_subtitle),
                        checked = mainViewModel.showWeekDivider,
                        onCheckedChange = { mainViewModel.setDividerShown(PeriodKind.WEEK, it) }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                SettingsSection(stringResource(R.string.settings_section_notification)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_reminder),
                        subtitle = stringResource(R.string.settings_reminder_subtitle),
                        checked = reminderEnabled,
                        onCheckedChange = { wanted ->
                            if (!wanted) {
                                DailyReminder.setEnabled(context, false)
                                reminderEnabled = false
                                return@SettingsSwitchItem
                            }
                            // 안드로이드 13부터는 권한이 없으면 알림이 조용히 무시된다.
                            // 켜자마자 물어봐야 사용자가 이유를 안다.
                            if (needsNotificationPermission(context)) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            } else {
                                DailyReminder.setEnabled(context, true)
                                reminderEnabled = true
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 운동 -> 구글 캘린더. 앱이 만든 보조 캘린더에만 쓰므로 구글 심사 없이 바로 쓸 수 있다.
                SettingsSection(stringResource(R.string.settings_section_calendar)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_calendar_sync),
                        subtitle = stringResource(R.string.settings_calendar_sync_subtitle),
                        checked = calendarEnabled,
                        onCheckedChange = { wanted ->
                            if (!wanted) {
                                mainViewModel.disableCalendarSync()
                                calendarEnabled = false
                                return@SettingsSwitchItem
                            }
                            // 켜는 순간 구글 동의 화면을 띄운다. 승인이 끝나야 스위치가 켜진다.
                            // 연결되는 몇 초 동안은 화면을 잠가 두 번 눌리지 않게 한다.
                            loadingMessage = calendarConnectingText
                            mainViewModel.connectCalendar(
                                onNeedsConsent = { pendingIntent ->
                                    loadingMessage = null
                                    launchCalendarConsent(pendingIntent)
                                },
                                onDone = { ok ->
                                    loadingMessage = null
                                    calendarEnabled = ok
                                }
                            )
                        }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_calendar_title_mode),
                        subtitle = stringResource(
                            if (calendarTitleByContent) R.string.settings_calendar_title_mode_on
                            else R.string.settings_calendar_title_mode_off
                        ),
                        checked = calendarTitleByContent,
                        onCheckedChange = { byContent ->
                            calendarTitleByContent = byContent
                            mainViewModel.setCalendarTitleMode(
                                if (byContent) GoogleCalendarSync.TITLE_MODE_CONTENT
                                else GoogleCalendarSync.TITLE_MODE_FIXED
                            )
                        }
                    )
                    if (!calendarTitleByContent) {
                        SettingsItem(
                            title = stringResource(R.string.settings_calendar_fixed_title),
                            subtitle = calendarFixedTitle,
                            onClick = { showCalendarTitleDialog = true }
                        )
                    }
                    SettingsItem(
                        title = stringResource(R.string.settings_calendar_sync_now),
                        subtitle = stringResource(R.string.settings_calendar_sync_now_subtitle),
                        enabled = calendarEnabled,
                        trailingIcon = Icons.Filled.Refresh,
                        onClick = {
                            loadingMessage = calendarSyncingText
                            mainViewModel.syncCalendarNow(
                                onNeedsConsent = { pendingIntent ->
                                    loadingMessage = null
                                    syncAfterConsent = true
                                    launchCalendarConsent(pendingIntent)
                                },
                                onDone = { loadingMessage = null }
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                SettingsSection(stringResource(R.string.settings_section_app_info)) {
                    SettingsItem(
                        title = stringResource(R.string.settings_version),
                        subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        onClick = null
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 되돌릴 수 없는 작업만 모아둔 구역. 색과 위치로 명확히 구분한다
                SettingsSection(
                    stringResource(R.string.settings_section_danger),
                    titleColor = MaterialTheme.colorScheme.error
                ) {
                    // 실수로 눌리지 않도록 여기서 바로 실행하지 않고 전용 화면으로 한 단계 더 들어간다
                    SettingsItem(
                        title = stringResource(R.string.settings_withdraw),
                        subtitle = stringResource(R.string.settings_withdraw_subtitle),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = onNavigateToWithdraw
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            loadingMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .clickable(enabled = false) {},   // 뒷배경 클릭 방지
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(msg, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/**
 * 표시 기간의 시작일·종료일 한 줄. 왼쪽 칸이 시작일, 물결 뒤 오른쪽 칸이 종료일.
 * 비어 있는 칸은 흐리게 "선택"·"오늘까지"로 보인다. 종료일 칸은 길게 누르면 비운다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DateRangeRow(
    start: String,
    end: String,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onClearEnd: () -> Unit,
    onClearStart: () -> Unit,
    /** 시작일을 따로 고르지 않아 가입일이 보이는 상태인지. 그러면 길게 눌러도 할 일이 없다 */
    startIsDefault: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = stringResource(R.string.settings_range_dates), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateCell(
                text = start.ifBlank { stringResource(R.string.settings_range_not_set) },
                filled = start.isNotBlank(),
                onClick = onPickStart,
                onLongClick = if (startIsDefault) null else onClearStart,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "~",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            DateCell(
                text = end.ifBlank { stringResource(R.string.settings_range_end_today) },
                filled = end.isNotBlank(),
                onClick = onPickEnd,
                onLongClick = if (end.isNotBlank()) onClearEnd else null,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_range_long_press_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DateCell(
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (filled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 날짜 하나를 고르는 시스템 대화상자. [initial]이 비어 있거나 못 읽으면 오늘부터 시작한다 */
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

/** 제목 + 카드로 묶인 설정 그룹. 항목을 늘리려면 content 안에 SettingsItem만 더 넣으면 된다. */
@Composable
private fun SettingsSection(
    title: String,
    titleColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = titleColor,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(content = content)
    }
}

/** 안드로이드 13 이상에서 알림 권한을 아직 못 받았는지 */
private fun needsNotificationPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) != PackageManager.PERMISSION_GRANTED
}

/** 스위치가 달린 설정 한 줄. 줄 전체를 눌러도 토글된다. */
@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 설정 한 줄. onClick이 null이면 정보 표시용(누를 수 없음)으로 동작한다.
 * [trailingIcon]을 주면 오른쪽 끝에 아이콘을 놓는다(동기화처럼 "누르면 뭔가 한다"는 걸 보여줄 때).
 */
@Composable
private fun SettingsItem(
    title: String,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (onClick != null && enabled) it.clickable(onClick = onClick) else it
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.5f)
            )
            subtitle?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailingIcon?.let {
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
