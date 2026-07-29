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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import com.youngs.dailynet.ui.viewmodel.MainViewModel

/**
 * 회원탈퇴 전용 화면.
 *
 * 되돌릴 수 없는 작업이라 실수로 도달하지 않도록 관문을 세 겹 둔다.
 *   1) 설정 화면에서 "회원탈퇴"를 눌러 이 화면까지 따로 들어와야 하고
 *   2) 삭제되는 내용을 읽고 체크박스를 켜야 버튼이 활성화되며
 *   3) 마지막으로 확인 다이얼로그에서 한 번 더 눌러야 실행된다
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithdrawScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    val context = LocalContext.current
    var agreed by remember { mutableStateOf(false) }
    var showFinalConfirm by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }

    // 처리 중에는 뒤로가기로 빠져나가지 못하게 막는다
    BackHandler(enabled = true) { if (!processing) onBack() }

    if (showFinalConfirm) {
        AlertDialog(
            onDismissRequest = { showFinalConfirm = false },
            title = { Text(stringResource(R.string.withdraw_final_title)) },
            text = { Text(stringResource(R.string.withdraw_final_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showFinalConfirm = false
                    processing = true
                    mainViewModel.withdrawAccount(context) { success ->
                        processing = false
                        if (success) onNavigateToLogin()
                    }
                }) {
                    Text(
                        stringResource(R.string.withdraw_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinalConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_withdraw)) },
                navigationIcon = {
                    IconButton(onClick = { if (!processing) onBack() }) {
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
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.withdraw_heading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        WithdrawBullet(stringResource(R.string.withdraw_bullet_records))
                        WithdrawBullet(stringResource(R.string.withdraw_bullet_body))
                        WithdrawBullet(stringResource(R.string.withdraw_bullet_analysis))
                        WithdrawBullet(stringResource(R.string.withdraw_bullet_account))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.withdraw_suggest_logout),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !processing) { agreed = !agreed }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = agreed,
                        onCheckedChange = { if (!processing) agreed = it }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.withdraw_agree),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { showFinalConfirm = true },
                    enabled = agreed && !processing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.withdraw_proceed), fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = { if (!processing) onBack() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.go_back))
                }
            }

            if (processing) {
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
                        Text(
                            stringResource(R.string.withdraw_processing),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WithdrawBullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text("· ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
