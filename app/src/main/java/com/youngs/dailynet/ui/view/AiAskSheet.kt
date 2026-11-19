package com.youngs.dailynet.ui.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youngs.dailynet.R
import com.youngs.dailynet.data.model.AiAskMessage
import com.youngs.dailynet.data.model.AiScreenTypes
import com.youngs.dailynet.ui.theme.DnDimens
import com.youngs.dailynet.ui.theme.DnTheme
import com.youngs.dailynet.ui.theme.Ink
import com.youngs.dailynet.ui.viewmodel.MainViewModel

/*
 * 전역 AI 질문 (기획 시안 2 — drafts/assets/dailynet-ai-ask-opt2.png).
 *
 * 조회·분석 화면(일일 목록·일일 정산·월/주/연 정산·인바디 목록/추이·체중 추이)은 각자
 * Scaffold의 floatingActionButton 자리에 [AiAskFab]을 둔다. 화면마다 그 화면의 데이터를 요약해
 * 넘기므로 "지금 보고 있는 것"이 곧 질문의 맥락이 된다. 서버는 그 요약을 다시 계산하지 않는다.
 *
 * 기존 '+' 버튼이 있는 화면은 버튼을 Column으로 묶어 위에 쌓는다 — 겹치지 않게 하는 별도 좌표 계산이
 * 필요 없고, 화면을 스크롤해도 두 버튼이 함께 움직인다.
 *
 * 대화는 세션 안에서만 남는다(서버·DB 저장 없음). 화면을 옮겨도 말풍선은 MainViewModel에 남아
 * 새 화면에서 이어서 물을 수 있다.
 */

/** 우하단 AI 버튼. 누르면 이 화면을 인식한 바텀시트가 열린다 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAskFab(
    mainViewModel: MainViewModel,
    screenType: String,
    screenTitle: String,
    contextData: String,
    modifier: Modifier = Modifier
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val askLabel = stringResource(R.string.cd_ai_ask)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Surface(
        onClick = { showSheet = true },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(2.dp, DnTheme.net.deficit),
        shadowElevation = 6.dp,
        modifier = modifier
            .size(60.dp)
            .semantics { contentDescription = askLabel }
    ) {
        Box(contentAlignment = Alignment.Center) {
            // 시안: 민트 원 안에 'AI'. 원 전체를 민트로 채우고 테두리만 진한 초록으로 두른다
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(DnTheme.net.mint),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.ai_ask_fab),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
            }
        }
    }

    if (showSheet) {
        AiAskSheet(
            mainViewModel = mainViewModel,
            screenType = screenType,
            screenTitle = screenTitle,
            contextData = contextData,
            sheetState = sheetState,
            onDismiss = { showSheet = false }
        )
    }
}

/**
 * AI 질문 바텀시트. 위에서부터 제목·인식된 화면 뱃지 → 추천 질문 칩 → 대화 → 입력.
 *
 * @param contextData 이 화면이 지금 보여주는 값의 요약 (AiAskContext)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AiAskSheet(
    mainViewModel: MainViewModel,
    screenType: String,
    screenTitle: String,
    contextData: String,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    val messages by mainViewModel.aiMessages.collectAsState()
    val asking by mainViewModel.aiAsking.collectAsState()
    val quota by mainViewModel.aiQuota.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current

    fun send(question: String) {
        val text = question.trim()
        if (text.isEmpty() || asking) return
        mainViewModel.askAiQuestion(screenType, screenTitle, contextData, text)
        input = ""
        focus.clearFocus()
    }

    // 새 말풍선이나 진행 표시가 생기면 맨 아래로 따라간다
    LaunchedEffect(messages.size, asking) {
        val extra = if (asking) 1 else 0
        val last = messages.size + extra - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = DnDimens.RadiusHero, topEnd = DnDimens.RadiusHero),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Ink.copy(alpha = 0.32f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 대화가 짧아도 입력창이 화면 아래에 붙어 있도록 시트를 거의 다 열어 둔다
                .fillMaxHeight(0.9f)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ai_ask_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ScreenBadge(title = screenTitle)
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.dialog_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 추천 질문 — 누르면 바로 전송된다
            val questions = AiAskQuestions.forScreen(screenType)
            if (questions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.ai_ask_suggested),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    questions.forEach { res ->
                        val text = stringResource(res)
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = !asking) { send(text) }
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty() && !asking) {
                    item {
                        Text(
                            text = stringResource(R.string.ai_ask_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(messages) { message ->
                    MessageBubble(
                        message = message,
                        // 오류 말풍선 중 마지막 것에만 "다시 시도"를 붙인다
                        canRetry = message.error && message === messages.lastOrNull() && !asking,
                        onRetry = { mainViewModel.retryAiAsk() }
                    )
                }
                if (asking) {
                    item { ThinkingBubble() }
                }
            }

            // 남은 횟수는 서버가 숫자를 준 경우에만 (무료/구독 구분이 있는 동안)
            quota?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ai_ask_remaining, it.remaining, it.limit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= AiScreenTypes.MAX_QUESTION) input = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.ai_ask_placeholder),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(50),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                SendButton(
                    enabled = input.isNotBlank() && !asking,
                    onClick = { send(input) }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** "인식된 화면: 11월 월간 정산" — 민트 알약 */
@Composable
private fun ScreenBadge(title: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Text(
            text = stringResource(R.string.ai_ask_screen_badge, title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** 사용자 말은 오른쪽 잉크 말풍선, AI 답은 왼쪽 surfaceVariant 카드 (시안과 같다) */
@Composable
private fun MessageBubble(message: AiAskMessage, canRetry: Boolean, onRetry: () -> Unit) {
    if (message.fromUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = Ink,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            color = if (message.error) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.error) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurface,
                // 여러 문장을 줄로 나눠 보여주므로 줄 간격을 조금 넓힌다
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }
        if (canRetry) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.ai_ask_retry), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 답을 만드는 동안의 말풍선. 누르는 동안 멈춘 것처럼 보이지 않게 한다 */
@Composable
private fun ThinkingBubble() {
    Surface(
        shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.widthIn(max = 220.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.ai_ask_thinking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 입력창 오른쪽 원형 전송 버튼 (시안의 잉크 원 + 화살표) */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) Ink else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) Color.White else MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.ai_ask_send),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
