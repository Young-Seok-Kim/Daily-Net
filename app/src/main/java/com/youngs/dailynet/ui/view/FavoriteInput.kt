package com.youngs.dailynet.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import com.youngs.dailynet.data.local.entity.FavoriteItemEntity
import com.youngs.dailynet.util.FavoriteRank
import kotlinx.coroutines.launch

/**
 * 입력칸 바로 아래 칩 한 줄. 이 줄이 곧 즐겨찾기 진입점이다.
 *
 * 따로 버튼을 두지 않는 이유: 칸 안에 카메라 아이콘이 이미 있어 아이콘 하나를 더 넣으면 좁아지고,
 * 무엇보다 한 번 더 눌러야 목록이 보인다. 칩은 보이는 즉시 한 번에 넣는다.
 *
 * 순서는 "어제와 같게" → 고정(★) → 자주 쓴 순 → "더보기". 정산이 끝난 날(읽기 전용)에는 부르지 않는다.
 */
@Composable
fun FavoriteChipRow(
    field: String,
    items: List<FavoriteItemEntity>,
    onSameAsYesterday: () -> Unit,
    onChip: (FavoriteItemEntity) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = onSameAsYesterday,
            label = { Text(stringResource(R.string.fav_same_as_yesterday)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                )
            }
        )

        items.take(FavoriteRank.CHIP_LIMIT).forEach { item ->
            if (item.isPinnedFor(field)) {
                SuggestionChip(
                    onClick = { onChip(item) },
                    label = { Text(item.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ),
                    border = null
                )
            } else {
                SuggestionChip(
                    onClick = { onChip(item) },
                    label = { Text(item.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    border = null
                )
            }
        }

        AssistChip(
            onClick = onMore,
            label = { Text(stringResource(R.string.fav_more)) },
            trailingIcon = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                )
            }
        )
    }
}

private enum class FavoriteTab { THIS_FIELD, ALL, PINNED }

/**
 * "더보기"를 눌렀을 때 뜨는 즐겨찾기 시트. 검색·여러 개 선택·고정·숨김·직접 추가를 여기서 한다.
 *
 * 관리 동작을 입력 화면에 펼치지 않고 시트에 모은 이유는, 입력 화면이 이미 칸 6개와 걸음수로
 * 길기 때문이다. 칩 줄은 "넣기"만 하고, 나머지는 전부 여기로 보낸다.
 *
 * 검색과 직접 추가는 한 칸이다. 친 글자와 똑같은 항목이 없으면 맨 위에 "○○ 추가" 줄이 생기고,
 * 누르면 저장과 동시에 선택된다. 별도의 추가 버튼을 두는 것보다 한 단계 짧다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteSheet(
    field: String,
    fieldLabel: String,
    items: List<FavoriteItemEntity>,
    onDismiss: () -> Unit,
    onInsert: (List<String>) -> Unit,
    onPin: (FavoriteItemEntity, Boolean) -> Unit,
    onHide: (FavoriteItemEntity) -> Unit,
    onAdd: (String) -> Unit
) {
    val isMeal = FavoriteItemEntity.kindOf(field) == FavoriteItemEntity.KIND_MEAL
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val visible = remember(items) { items.filter { !it.hidden } }
    val thisFieldList = remember(items, field) {
        FavoriteRank.rankForField(items, field).filter { it.isPinnedFor(field) || it.countFor(field) > 0 }
    }
    val overall = remember(items, field) { FavoriteRank.rankOverall(items, field) }

    // 이 끼니에 쓴 항목이 하나도 없으면(처음 쓰는 사용자) 전체 탭으로 시작한다. 빈 탭을 먼저 보여주지 않는다
    var tab by remember {
        mutableStateOf(if (isMeal && thisFieldList.isNotEmpty()) FavoriteTab.THIS_FIELD else FavoriteTab.ALL)
    }
    var query by remember { mutableStateOf("") }
    // 선택은 이름으로 기억한다. 방금 직접 추가한 항목은 Room에서 돌아오기 전에도 선택된 상태여야 한다
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }

    val trimmedQuery = query.trim()
    val listed = remember(tab, thisFieldList, overall, trimmedQuery) {
        val base = when (tab) {
            FavoriteTab.THIS_FIELD -> thisFieldList
            FavoriteTab.ALL -> overall
            FavoriteTab.PINNED -> overall.filter { it.isPinnedFor(field) }
        }
        if (trimmedQuery.isEmpty()) base
        else base.filter { it.text.contains(trimmedQuery, ignoreCase = true) }
    }
    val canAdd = trimmedQuery.isNotEmpty() &&
        visible.none { it.text.equals(trimmedQuery, ignoreCase = true) }

    // LazyColumn은 맨 위에 보이는 항목을 키로 기억해 두고, 그 항목이 다른 자리로 가면 스크롤이 따라간다.
    // 그래서 맨 윗줄 항목의 고정을 끄면 화면이 그 항목을 따라 아래로 내려가 버린다. 목록 순서가 바뀔 때마다
    // 다음 측정에서 지금 보고 있는 인덱스·오프셋을 그대로 쓰라고 요청해 스크롤을 제자리에 둔다
    // (requestScrollToItem은 composition 중에 불러도 되는 Foundation 1.7 API)
    val listState = rememberLazyListState()
    remember(listed) {
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }

    fun toggle(text: String) {
        selected = if (selected.contains(text)) selected - text else selected + text
    }

    fun close(after: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            after()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.fav_sheet_title, fieldLabel),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { close() }) { Text(stringResource(R.string.fav_done)) }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.fav_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isMeal) {
                    FilterChip(
                        selected = tab == FavoriteTab.THIS_FIELD,
                        onClick = { tab = FavoriteTab.THIS_FIELD },
                        label = { Text(stringResource(R.string.fav_tab_this_field, fieldLabel)) }
                    )
                }
                FilterChip(
                    selected = tab == FavoriteTab.ALL,
                    onClick = { tab = FavoriteTab.ALL },
                    label = {
                        Text(stringResource(if (isMeal) R.string.fav_tab_all_meals else R.string.fav_tab_frequent))
                    }
                )
                FilterChip(
                    selected = tab == FavoriteTab.PINNED,
                    onClick = { tab = FavoriteTab.PINNED },
                    label = { Text(stringResource(R.string.fav_tab_pinned)) }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp)
            ) {
                if (canAdd) {
                    item(key = "add") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAdd(trimmedQuery)
                                    if (!selected.contains(trimmedQuery)) selected = selected + trimmedQuery
                                    query = ""
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            Text(
                                text = stringResource(R.string.fav_add_row, trimmedQuery),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        HorizontalDivider()
                    }
                }

                if (listed.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(
                                if (trimmedQuery.isEmpty()) R.string.fav_empty else R.string.fav_empty_search
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                items(listed, key = { favoriteKey(it) }) { item ->
                    // 자리 이동은 미끄러지고, 숨긴 행은 서서히 사라진다. 스크롤은 위 requestScrollToItem으로 제자리에 둔다
                    // (한 번 따라가게 해 봤더니 고정을 누를 때마다 화면이 튀어 사용자가 되돌리길 원했다)
                    Column(modifier = Modifier.animateItem()) {
                        FavoriteRow(
                            item = item,
                            pinned = item.isPinnedFor(field),
                            checked = selected.contains(item.text),
                            onToggle = { toggle(item.text) },
                            onPin = { onPin(item, !item.isPinnedFor(field)) },
                            onHide = {
                                onHide(item)
                                selected = selected - item.text
                            }
                        )
                        HorizontalDivider()
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            Button(
                onClick = {
                    val chosen = selected
                    close { onInsert(chosen) }
                },
                enabled = selected.isNotEmpty(),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text(stringResource(R.string.fav_insert_button, selected.size))
            }
        }
    }
}

private fun favoriteKey(item: FavoriteItemEntity): String = item.kind + "/" + item.text

@Composable
private fun FavoriteRow(
    item: FavoriteItemEntity,
    pinned: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val avg = item.averageKcal
            Text(
                text = when {
                    item.useCount == 0 -> stringResource(R.string.fav_meta_manual)
                    avg != null -> stringResource(R.string.fav_meta_with_kcal, item.useCount, avg)
                    else -> stringResource(R.string.fav_meta_count, item.useCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onPin) {
            Icon(
                imageVector = if (pinned) Icons.Default.Star else Icons.Outlined.Star,
                contentDescription = stringResource(
                    if (pinned) R.string.fav_cd_unpin else R.string.fav_cd_pin
                ),
                tint = if (pinned) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.outlineVariant
            )
        }
        IconButton(onClick = onHide) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.fav_cd_hide),
                tint = MaterialTheme.colorScheme.outlineVariant
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}
