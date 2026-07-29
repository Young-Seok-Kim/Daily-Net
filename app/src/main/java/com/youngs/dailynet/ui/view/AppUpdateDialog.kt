package com.youngs.dailynet.ui.view

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import com.youngs.dailynet.util.AppUpdateInfo

/**
 * 앱을 켤 때 뜨는 업데이트 안내.
 *
 * 강제([AppUpdateInfo.forced])일 때는 뒤로가기와 바깥 터치를 모두 막고 "나중에" 버튼도 없앤다.
 * 하나라도 열어두면 사용자가 그냥 닫고 구버전을 계속 쓰게 되는데,
 * 서버와 형식이 안 맞아 차단하는 상황에서는 그게 곧 "분석 실패"만 반복되는 상태다.
 */
@Composable
fun AppUpdateDialog(
    info: AppUpdateInfo,
    onLater: () -> Unit,
    onUpdate: () -> Unit
) {
    AlertDialog(
        // 강제일 때는 닫기 요청 자체를 무시한다
        onDismissRequest = { if (!info.forced) onLater() },
        properties = DialogProperties(
            dismissOnBackPress = !info.forced,
            dismissOnClickOutside = !info.forced
        ),
        title = { Text(if (info.forced) "업데이트가 필요합니다" else "새 버전이 있습니다") },
        text = { Text(info.message) },
        confirmButton = {
            TextButton(onClick = onUpdate) { Text("업데이트") }
        },
        dismissButton = {
            if (!info.forced) {
                TextButton(onClick = onLater) { Text("나중에") }
            }
        }
    )
}
