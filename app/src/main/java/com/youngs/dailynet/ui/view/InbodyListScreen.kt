package com.youngs.dailynet.ui.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import com.youngs.dailynet.data.local.entity.InbodyRecordEntity
import com.youngs.dailynet.ui.viewmodel.MainViewModel

/**
 * 인바디 측정 이력. 설정 > 내 정보 > 인바디 결과에서 들어온다.
 *
 * 최근 측정이 위에 오고, 맨 위 것이 식단 분석에 쓰이는 값이라 "분석에 적용 중"을 달아 둔다.
 * 줄을 누르면 그 측정의 값·해석을 보는 화면([InbodyEditScreen])으로, 아래 버튼으로 새 측정을 추가한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InbodyListScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    onOpen: (measuredAt: String) -> Unit,
    onAdd: () -> Unit,
    onTrend: () -> Unit
) {
    val records by mainViewModel.inbodyRecords.collectAsState()

    BackHandler(enabled = true) { onBack() }

    // 들어올 때마다 계정 쪽 이력을 한 번 맞춘다 (다른 기기에서 넣은 것이 보이도록)
    LaunchedEffect(Unit) { mainViewModel.syncInbody() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbody_dialog_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    // 측정이 2건 이상일 때만 추이를 볼 수 있다
                    IconButton(onClick = onTrend, enabled = records.size >= 2) {
                        Icon(
                            painterResource(R.drawable.ic_weight_chart),
                            contentDescription = stringResource(R.string.inbody_trend_title)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.inbody_add)) }
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.inbody_list_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(records, key = { it.measuredAt }) { record ->
                InbodyRow(
                    record = record,
                    applied = record.measuredAt == records.first().measuredAt,
                    onClick = { onOpen(record.measuredAt) }
                )
            }
        }
    }
}

@Composable
private fun InbodyRow(record: InbodyRecordEntity, applied: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (applied) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.measuredAt,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (record.isDummy) {
                    Text(
                        "더미",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                if (applied) {
                    SuggestionChip(
                        onClick = onClick,
                        label = { Text(stringResource(R.string.inbody_applied)) }
                    )
                }
            }
            Text(
                inbodySummary(record.toData(), withDate = false),
                style = MaterialTheme.typography.bodyMedium
            )
            if (record.analysis.isBlank()) {
                Text(
                    stringResource(R.string.inbody_analysis_missing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}
