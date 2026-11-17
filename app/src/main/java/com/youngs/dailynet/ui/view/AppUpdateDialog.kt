package com.youngs.dailynet.ui.view

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.youngs.dailynet.R
import com.youngs.dailynet.util.AppUpdateInfo

/**
 * 앱을 켤 때 뜨는 업데이트 안내.
 *
 * 강제([AppUpdateInfo.forced])일 때는 [DnDialogTone.Required]라 뒤로가기와 바깥 터치를 모두 막고 "나중에"도 없다.
 * 하나라도 열어두면 사용자가 그냥 닫고 구버전을 계속 쓰게 되는데,
 * 서버와 형식이 안 맞아 차단하는 상황에서는 그게 곧 "분석 실패"만 반복되는 상태다.
 */
@Composable
fun AppUpdateDialog(
    info: AppUpdateInfo,
    onLater: () -> Unit,
    onUpdate: () -> Unit
) {
    DnDialog(
        title = stringResource(
            if (info.forced) R.string.update_title_forced else R.string.update_title_recommend
        ),
        body = info.message.ifBlank { null },
        tone = if (info.forced) DnDialogTone.Required else DnDialogTone.Neutral,
        icon = Icons.Default.Info,
        primaryLabel = stringResource(R.string.update_action),
        onPrimary = onUpdate,
        secondaryLabel = stringResource(R.string.update_later),
        onSecondary = onLater,
        onDismiss = onLater
    )
}
