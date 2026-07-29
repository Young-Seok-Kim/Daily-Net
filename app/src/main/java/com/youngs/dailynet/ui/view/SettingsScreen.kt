package com.youngs.dailynet.ui.view

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.R
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
    onNavigateToWithdraw: () -> Unit
) {
    val context = LocalContext.current
    val userProfile by mainViewModel.userProfile.collectAsState()

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

/** 설정 한 줄. onClick이 null이면 정보 표시용(누를 수 없음)으로 동작한다. */
@Composable
private fun SettingsItem(
    title: String,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (onClick != null && enabled) it.clickable(onClick = onClick) else it
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
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
}
