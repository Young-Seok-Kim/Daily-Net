package com.youngs.dailynet.ui.view

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.ui.viewmodel.MainViewModel

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
            title = { Text("로그아웃") },
            text = { Text("로그아웃하시겠습니까?\n기록은 서버에 남아 있어 다시 로그인하면 그대로 복구됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    loadingMessage = "로그아웃 중입니다..."
                    mainViewModel.logout(context) { success ->
                        loadingMessage = null
                        if (success) onNavigateToLogin()
                    }
                }) { Text("로그아웃") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("취소") }
            }
        )
    }

    // ── 본문 ──────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
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
                SettingsSection("내 정보") {
                    SettingsItem(
                        title = "신체 정보 수정",
                        subtitle = userProfile?.let {
                            "키 ${it.height}cm · 시작 ${it.initialWeight}kg · ${it.birthDate}"
                        } ?: "불러오는 중...",
                        enabled = userProfile != null,
                        onClick = { showProfileEditDialog = true }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                SettingsSection("계정") {
                    SettingsItem(
                        title = "로그아웃",
                        subtitle = "다시 로그인하면 기록이 그대로 복구됩니다",
                        onClick = { showLogoutConfirm = true }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                SettingsSection("앱 정보") {
                    SettingsItem(
                        title = "버전",
                        subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        onClick = null
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 되돌릴 수 없는 작업만 모아둔 구역. 색과 위치로 명확히 구분한다
                SettingsSection("위험 구역", titleColor = MaterialTheme.colorScheme.error) {
                    // 실수로 눌리지 않도록 여기서 바로 실행하지 않고 전용 화면으로 한 단계 더 들어간다
                    SettingsItem(
                        title = "회원탈퇴",
                        subtitle = "모든 정산 기록과 신체 정보가 영구 삭제됩니다",
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
