package com.youngs.dailynet.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.youngs.dailynet.R
import com.youngs.dailynet.ui.theme.DnDimens
import com.youngs.dailynet.ui.theme.Ink
import com.youngs.dailynet.ui.theme.tabular
import com.youngs.dailynet.util.isoDateToUtcMillis
import com.youngs.dailynet.util.todayUtcMillis
import com.youngs.dailynet.util.utcMillisToIsoDate
import com.youngs.dailynet.util.utcMillisYear

/*
 * 앱의 모달 부품. 2026-11-17 다이얼로그 디자인 기획서를 그대로 옮겼다.
 *
 * 왜 따로 만드나 — M3 기본 AlertDialog는 ① 회녹색 패널(surfaceContainerHigh) ② 24sp 제목 ③ 같은 무게의
 * TextButton 두 개 ④ 확인 버튼이 **왼쪽**이라 "삭제 | 취소"가 된다. 열네 곳이 그대로 써서 화면마다 반복됐다.
 *
 * 네 줄 규칙으로 고른다.
 * ① 본문 2줄 이하 + 액션 2개 → [DnDialog]
 * ② 입력이 두 칸 이상 → [DnFormSheet]
 * ③ 선택지가 두 갈래 이상 → [DnActionSheet]
 * ④ 처리 중 차단 → [DnBlockingOverlay]
 * ⑤ 날짜 하나 → [DnDatePickerDialog]
 */

/** 다이얼로그의 톤. 주액션 색과 닫기 가능 여부가 달라진다 */
enum class DnDialogTone {
    /** 확인·안내·로그아웃. 잉크 바탕 주액션 */
    Neutral,
    /** 삭제·탈퇴처럼 되돌릴 수 없는 것. 오류 레드 주액션, 대상 박스를 같이 쓴다 */
    Destructive,
    /** 닫으면 안 되는 필수 단계. 보조 액션 없음, 바깥 터치·뒤로가기 차단 */
    Required
}

/** 치수. 기획서 "DnDialog 치수" 표 그대로. 나중에 DnDimens로 옮겨도 된다 */
object DnDialogDefaults {
    /** 화면 좌우 여백. 폭 = 화면폭 - 40 */
    val ScreenMargin = 20.dp
    val MaxWidth = 400.dp
    val Radius = DnDimens.RadiusHero
    val Padding = 24.dp
    val PaddingBottom = 20.dp
    val IconCircle = 44.dp
    val IconGlyph = 24.dp
    val ButtonHeight = 48.dp
    val ButtonRadius = DnDimens.RadiusTile
    val ButtonGap = 8.dp
    val TargetRadius = DnDimens.RadiusChip
    /** 시트 하단 고정 CTA */
    val CtaHeight = 56.dp
    /** 스크림. 검정이 아니라 잉크라 배경이 회색으로 죽지 않는다 */
    val ScrimAlpha = 0.32f
}

/**
 * 확인·삭제·알림 한 장. 아이콘(선택) → 제목 → 본문 → 대상 박스(선택) → [content](선택) → 보조/주 액션.
 *
 * 버튼 순서는 왼쪽 [보조] → 오른쪽 [주액션] 하나로 고정한다. 손가락은 오른쪽으로 간다.
 *
 * @param targetValue 사용자가 눈으로 대조할 값(날짜·계정·이용권 이름). 파괴적 동작에는 꼭 넣는다
 * @param secondaryLabel null이면 보조 버튼이 없다. [DnDialogTone.Required]는 항상 없다
 * @param content 입력 한 칸처럼 본문 밑에 끼우는 것. 두 칸 이상이면 [DnFormSheet]로
 */
@Composable
fun DnDialog(
    title: String,
    onDismiss: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    body: String? = null,
    tone: DnDialogTone = DnDialogTone.Neutral,
    icon: ImageVector? = null,
    targetLabel: String? = null,
    targetValue: String? = null,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = stringResource(R.string.cancel),
    onSecondary: () -> Unit = onDismiss,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val dismissible = tone != DnDialogTone.Required
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        )
    ) {
        DnScrim(onClick = if (dismissible) onDismiss else null) {
            DnDialogCard {
                if (icon != null) {
                    DnDialogIcon(icon = icon, destructive = tone == DnDialogTone.Destructive)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!body.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!targetValue.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    DnTargetBox(label = targetLabel.orEmpty(), value = targetValue)
                }
                if (content != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    content()
                }
                Spacer(modifier = Modifier.height(20.dp))
                DnDialogActions(
                    tone = tone,
                    primaryLabel = primaryLabel,
                    onPrimary = onPrimary,
                    primaryEnabled = primaryEnabled,
                    secondaryLabel = if (dismissible) secondaryLabel else null,
                    onSecondary = onSecondary
                )
            }
        }
    }
}

/**
 * 잉크 스크림. 플랫폼 창의 검정 dim은 끄고 우리가 그린다.
 * [onClick]이 null이면(필수 단계) 바깥을 눌러도 아무 일도 없다.
 */
@Composable
private fun DnScrim(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = DnDialogDefaults.ScrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = onClick != null,
                onClick = { onClick?.invoke() }
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 흰 카드. 폭 = min(화면폭 - 40, 400), 라운드 28, 패딩 24 / 하단 20 */
@Composable
private fun DnDialogCard(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth - DnDialogDefaults.ScreenMargin * 2, DnDialogDefaults.MaxWidth)
        Surface(
            shape = RoundedCornerShape(DnDialogDefaults.Radius),
            color = MaterialTheme.colorScheme.surface,
            // 카드 안을 눌러도 스크림의 닫기가 먹지 않게 빈 클릭을 깐다
            modifier = Modifier
                .width(width)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            Column(
                modifier = Modifier.padding(
                    start = DnDialogDefaults.Padding,
                    end = DnDialogDefaults.Padding,
                    top = DnDialogDefaults.Padding,
                    bottom = DnDialogDefaults.PaddingBottom
                ),
                content = content
            )
        }
    }
}

/** 44dp 원 + 24dp 글리프. 중립은 민트 원, 파괴는 코랄 원 */
@Composable
private fun DnDialogIcon(icon: ImageVector, destructive: Boolean) {
    val container = if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = Modifier
            .size(DnDialogDefaults.IconCircle)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(DnDialogDefaults.IconGlyph)
        )
    }
}

/** 보조(텍스트) → 주액션(채움) 순. 둘 다 높이 48, 사이 8 */
@Composable
private fun DnDialogActions(
    tone: DnDialogTone,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean,
    secondaryLabel: String?,
    onSecondary: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (secondaryLabel != null) {
            TextButton(
                onClick = onSecondary,
                modifier = Modifier.height(DnDialogDefaults.ButtonHeight),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text(secondaryLabel, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.width(DnDialogDefaults.ButtonGap))
        }
        DnPrimaryButton(
            label = primaryLabel,
            onClick = onPrimary,
            enabled = primaryEnabled,
            destructive = tone == DnDialogTone.Destructive
        )
    }
}

/** 주액션 버튼. 잉크 바탕 + 흰 글자, 파괴적이면 오류 레드 바탕 */
@Composable
fun DnPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    height: androidx.compose.ui.unit.Dp = DnDialogDefaults.ButtonHeight
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(DnDialogDefaults.ButtonRadius),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.height(height)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** 대상 박스. 라벨 11 + 값 14 SemiBold, surfaceVariant r12 */
@Composable
fun DnTargetBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DnDialogDefaults.TargetRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.tabular(),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 시트 안의 파괴적 행. 구분선 아래 빨간 글자 한 줄 ("이용권 삭제") */
@Composable
fun DnDestructiveRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/**
 * 입력 폼 시트. 상단 라운드 28, 제목 줄(오른쪽 "닫기"), 본문 스크롤, 하단 고정 CTA 56dp.
 * 키보드가 올라오면 전체가 밀려 CTA가 가려지지 않는다(imePadding).
 *
 * @param dismissible false면(필수 단계) 바깥 터치·스와이프·뒤로가기로 닫히지 않고 "닫기"도 없다
 * @param destructiveLabel 있으면 CTA 위, 구분선 아래에 빨간 글자 행
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnFormSheet(
    title: String,
    onDismiss: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    subtitle: String? = null,
    dismissible: Boolean = true,
    destructiveLabel: String? = null,
    onDestructive: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // 필수 단계는 숨김으로 가는 전이를 거부한다. 스크림·스와이프 모두 막힌다
        confirmValueChange = { dismissible || it != SheetValue.Hidden }
    )
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = DnDialogDefaults.Radius, topEnd = DnDialogDefaults.Radius),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Ink.copy(alpha = DnDialogDefaults.ScrimAlpha),
        properties = ModalBottomSheetDefaults.properties(shouldDismissOnBackPress = dismissible)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = DnDialogDefaults.Padding)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (dismissible) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_close), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )

            if (destructiveLabel != null) {
                Spacer(modifier = Modifier.height(12.dp))
                DnDestructiveRow(label = destructiveLabel, onClick = onDestructive)
            }
            Spacer(modifier = Modifier.height(12.dp))
            DnPrimaryButton(
                label = primaryLabel,
                onClick = onPrimary,
                enabled = primaryEnabled,
                height = DnDialogDefaults.CtaHeight,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** 액션 시트의 한 행. [leading]은 44dp 원 안에 들어갈 것(아이콘이나 이모지 글자) */
data class DnSheetAction(
    val title: String,
    val subtitle: String? = null,
    val leading: @Composable () -> Unit,
    val onClick: () -> Unit
)

/**
 * 두 갈래 이상의 선택지. 행마다 44dp 민트 원 + 제목 15 + 설명 12 + 셰브론. 아래 "취소" 한 줄.
 * "카메라 | 갤러리"를 TextButton 둘로 놓던 자리를 대신한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnActionSheet(
    title: String,
    actions: List<DnSheetAction>,
    onDismiss: () -> Unit,
    subtitle: String? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = DnDialogDefaults.Radius, topEnd = DnDialogDefaults.Radius),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Ink.copy(alpha = DnDialogDefaults.ScrimAlpha)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = DnDialogDefaults.Padding)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DnDimens.RadiusTile))
                        .clickable(onClick = action.onClick)
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(DnDialogDefaults.IconCircle)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) { action.leading() }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = action.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        if (!action.subtitle.isNullOrBlank()) {
                            Text(
                                text = action.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(DnDialogDefaults.ButtonRadius),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DnDialogDefaults.ButtonHeight)
            ) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * 처리 중 차단. 잉크 스크림 위에 작은 카드(스피너 + 한 줄). 뒤를 누르지 못한다.
 * 로그아웃·탈퇴·캘린더 동기화가 쓴다. 호출한 쪽이 Box 맨 위에 올린다.
 */
@Composable
fun DnBlockingOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = DnDialogDefaults.ScrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(DnDimens.RadiusCard),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(14.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** 날짜 선택 창의 기본값 */
object DnDatePickerDefaults {
    /**
     * 고를 수 있는 연도. 플랫폼 DatePickerDialog는 제한이 없었지만 Compose 쪽은 범위를 줘야 한다.
     * 1900~2100이면 생년월일도 정산 날짜도 다 들어간다 (1세기 이전 생일은 없다고 본다)
     */
    val YearRange = 1900..2100

    /** 달력 색. DnDialog와 같은 surface 바탕에 선택은 primary(라이트 잉크 / 다크 민트) */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun colors(): DatePickerColors {
        val scheme = MaterialTheme.colorScheme
        return DatePickerDefaults.colors(
            containerColor = scheme.surface,
            titleContentColor = scheme.onSurfaceVariant,
            headlineContentColor = scheme.onSurface,
            weekdayContentColor = scheme.onSurfaceVariant,
            subheadContentColor = scheme.onSurfaceVariant,
            navigationContentColor = scheme.onSurfaceVariant,
            yearContentColor = scheme.onSurface,
            currentYearContentColor = scheme.primary,
            selectedYearContainerColor = scheme.primary,
            selectedYearContentColor = scheme.onPrimary,
            dayContentColor = scheme.onSurface,
            selectedDayContainerColor = scheme.primary,
            selectedDayContentColor = scheme.onPrimary,
            todayContentColor = scheme.primary,
            todayDateBorderColor = scheme.primary,
            dividerColor = scheme.outlineVariant,
            dateTextFieldColors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = scheme.onSurface,
                unfocusedTextColor = scheme.onSurface,
                focusedBorderColor = scheme.outline,
                unfocusedBorderColor = scheme.outline,
                focusedLabelColor = scheme.onSurfaceVariant,
                unfocusedLabelColor = scheme.onSurfaceVariant,
                cursorColor = scheme.primary
            )
        )
    }
}

/**
 * 날짜 하나를 고르는 다이얼로그. 플랫폼 `android.app.DatePickerDialog`를 대신한다.
 *
 * 왜 바꿨나 — 플랫폼 창은 Activity 테마(values-night/themes.xml의 Theme.Material.NoActionBar)를 따라가서,
 * 기기가 다크 모드면 검은 바탕에 민트/시안 기본 강조색으로 떴다. 회원가입에서 생년월일을 고를 때
 * 앱 색과 따로 놀았다. 이제 바탕·강조색·라운드는 DailyNetTheme 토큰만 쓴다.
 *
 * 연도는 헤더의 "2026년" 글자를 누르면 열리는 연도 목록에서 바로 고른다 (생년월일은 여기서 1997년으로 간다).
 * 오른쪽 위 연필 버튼을 누르면 숫자로 직접 입력하는 모드로 바뀐다.
 *
 * @param initial "yyyy-MM-dd". 비었거나 못 읽으면 오늘에서 시작한다 (예전 DatePickerDialog와 같다)
 * @param onPick 고른 날을 "yyyy-MM-dd"로 돌려준다. 형식과 콜백 동작은 예전과 같다
 * @param yearRange 고를 수 있는 연도. 기본은 [DnDatePickerDefaults.YearRange]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnDatePickerDialog(
    initial: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    yearRange: IntRange = DnDatePickerDefaults.YearRange
) {
    // 범위 밖 연도가 들어오면 rememberDatePickerState가 IllegalArgumentException을 던진다.
    // 저장된 값이 이상해도 앱이 죽지 않도록 그때는 오늘로 물러난다
    val startMillis = remember(initial, yearRange) {
        val parsed = isoDateToUtcMillis(initial)
        if (parsed != null && utcMillisYear(parsed) in yearRange) parsed else todayUtcMillis()
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = startMillis,
        initialDisplayedMonthMillis = startMillis,
        yearRange = yearRange
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(DnDialogDefaults.Radius),
        colors = DnDatePickerDefaults.colors(),
        // 다른 다이얼로그와 같이 취소(텍스트) → 확인(잉크 채움). 확인은 고른 날이 있어야 눌린다
        confirmButton = {
            DnPrimaryButton(
                label = stringResource(R.string.confirm),
                enabled = state.selectedDateMillis != null,
                onClick = { state.selectedDateMillis?.let { onPick(utcMillisToIsoDate(it)) } }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) {
        DatePicker(state = state, showModeToggle = true)
    }
}

/** 로그아웃 확인. 프로필 시트와 설정 화면이 같은 창을 쓴다 (두 벌 있던 것을 합침) */
@Composable
fun LogoutConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DnDialog(
        title = stringResource(R.string.logout_confirm_title),
        body = stringResource(R.string.logout_confirm_message),
        icon = Icons.AutoMirrored.Filled.ExitToApp,
        primaryLabel = stringResource(R.string.logout),
        onPrimary = onConfirm,
        onDismiss = onDismiss
    )
}

/**
 * 사진 소스 선택. 음식 사진(정산 입력)과 인바디 결과지가 같은 시트를 쓴다 (두 벌 있던 것을 합침).
 * 카메라 권한 확인은 호출한 쪽 몫이다.
 */
@Composable
fun PhotoSourceSheet(
    title: String,
    subtitle: String?,
    gallerySubtitle: String,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    DnActionSheet(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
        actions = listOf(
            DnSheetAction(
                title = stringResource(R.string.photo_source_camera),
                subtitle = stringResource(R.string.photo_source_camera_desc),
                leading = { Text("📷", style = MaterialTheme.typography.titleMedium) },
                onClick = onCamera
            ),
            DnSheetAction(
                title = stringResource(R.string.photo_source_gallery),
                subtitle = gallerySubtitle,
                leading = { Text("🖼️", style = MaterialTheme.typography.titleMedium) },
                onClick = onGallery
            )
        )
    )
}
