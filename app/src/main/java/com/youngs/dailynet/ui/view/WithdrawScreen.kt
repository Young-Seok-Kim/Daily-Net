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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            title = { Text("마지막 확인") },
            text = { Text("정말 탈퇴하시겠습니까?\n지금까지의 모든 정산 기록이 영구 삭제되며, 복구할 수 없습니다.") },
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
                        "탈퇴하기",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinalConfirm = false }) { Text("취소") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("회원탈퇴") },
                navigationIcon = {
                    IconButton(onClick = { if (!processing) onBack() }) {
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
                    .padding(20.dp)
            ) {
                Text(
                    text = "탈퇴하면 아래 내용이 모두 삭제됩니다.",
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
                        WithdrawBullet("지금까지 기록한 모든 정산 내역")
                        WithdrawBullet("키 · 몸무게 · 생년월일 등 신체 정보")
                        WithdrawBullet("AI 분석 결과 및 피드백 기록")
                        WithdrawBullet("계정 정보 (다시 가입해도 복구되지 않음)")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "잠시 쉬어가려는 것이라면 탈퇴 대신 로그아웃을 권합니다. " +
                            "로그아웃은 기록이 그대로 남아 있어 다시 로그인하면 이어서 쓸 수 있습니다.",
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
                        text = "위 내용을 모두 확인했으며, 복구할 수 없다는 점에 동의합니다.",
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
                    Text("회원탈퇴 진행", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = { if (!processing) onBack() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("돌아가기")
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
                        Text("회원탈퇴 처리 중입니다...", style = MaterialTheme.typography.bodyLarge)
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
