package com.youngs.dailynet.ui.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R
import com.youngs.dailynet.ui.viewmodel.MainViewModel

/**
 * 주·연 정산 상세 화면. 목록 패널의 "AI 평가" 줄을 누르면 들어온다.
 *
 * 월은 이미 월 정산 화면(MonthReportScreen)이 있어 거기로 가고, 주와 연은 화면이 없어서 이걸 쓴다.
 * 구성은 월 정산 화면의 맨 위와 같다 — 합계 카드 한 장 + AI 평가 카드. 평가 글이 열 줄을 넘어
 * 목록 패널 안에 두면 기록이 한참 밀려서 여기로 뺐다.
 *
 * @param kind WEEK 또는 YEAR
 * @param label 주 "202646" / 연 "2026" — 목록 구분선과 저장 키가 쓰는 그 값
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodReportScreen(
    mainViewModel: MainViewModel,
    kind: PeriodKind,
    label: String,
    onBack: () -> Unit
) {
    val allRecords by mainViewModel.visibleRecords.collectAsState()
    val userProfile by mainViewModel.userProfile.collectAsState()
    val context = LocalContext.current

    val records = remember(allRecords, kind, label) {
        when (kind) {
            PeriodKind.WEEK -> allRecords.filter { getWeekIdentifier(it.date).toString() == label }
            PeriodKind.YEAR -> allRecords.filter { it.date.take(4) == label }
            PeriodKind.MONTH -> allRecords.filter { it.date.take(7) == label }
        }
    }
    // 제목은 목록 구분선과 같은 글자여야 한다 ("11월 3주차", "2026년"). 주는 라벨만으로 못 만들어 기록 날짜를 쓴다
    val title = remember(records, kind, label) {
        when (kind) {
            PeriodKind.WEEK -> records.firstOrNull()?.let { getWeekOfMonthText(context, it.date) } ?: label
            PeriodKind.YEAR -> getYearHeaderText("$label-01-01")
            PeriodKind.MONTH -> getMonthHeaderText("$label-01")
        }
    }
    val summary = remember(records, allRecords, userProfile) {
        val fallbackWeight = allRecords.firstOrNull { it.weight > 0f }?.weight
            ?: userProfile?.initialWeight
            ?: 0f
        buildPeriodSummary(records, fallbackWeight)
    }

    BackHandler(enabled = true) { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
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
        if (records.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.period_report_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            MonthHeadlineCard(
                summary = summary,
                totalLabel = stringResource(
                    if (kind == PeriodKind.YEAR) R.string.year_summary_total else R.string.week_summary_total
                )
            )

            PeriodEvaluationSection(
                mainViewModel = mainViewModel,
                kind = kind,
                label = label,
                title = title,
                records = records
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
