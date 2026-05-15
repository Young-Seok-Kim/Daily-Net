package com.youngs.dailynet.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.data.model.SettlementModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun getWeekIdentifier(dateString: String): Int {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateString) ?: return 0
    val calendar = Calendar.getInstance().apply {
        time = date
        firstDayOfWeek = Calendar.MONDAY // 월요일 시작
        minimalDaysInFirstWeek = 4
    }
    // 일요일이 한 주의 끝이 되도록 계산 (해당 날짜가 속한 연도 + 주차 조합)
    return calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.WEEK_OF_YEAR)
}

// 1. 월별 주차를 계산하는 헬퍼 함수 추가
fun getWeekOfMonthText(dateString: String): String {
    return try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateString) ?: return dateString
        val calendar = Calendar.getInstance().apply {
            time = date
            firstDayOfWeek = Calendar.MONDAY // 월요일 시작 기준
        }
        val month = calendar.get(Calendar.MONTH) + 1 // 0부터 시작하므로 +1
        val week = calendar.get(Calendar.WEEK_OF_MONTH)

        // "05월 3주차" 형식으로 반환
        String.format("%02d월 %d주차", month, week)
    } catch (e: Exception) {
        dateString // 에러 시 기본 날짜 반환
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onNavigateToInput: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val settlements by mainViewModel.allSettlements.collectAsState()
    val totalCalories by mainViewModel.totalNetCalories.collectAsState()

    // ✨ 앱 진입 시 프로필 존재 여부 체크
    LaunchedEffect(Unit) {
        mainViewModel.checkProfile()
    }

    // ✨ 프로필(키/체중)이 없을 경우 팝업 표시
    if (mainViewModel.showProfileDialog) {
        ProfileSetupDialog(
            onConfirm = { height, weight ->
                mainViewModel.saveInitialProfile(height, weight)
            }
        )
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToInput,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("오늘의 정산") },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SummaryHeaderCard(
                    totalCalories = totalCalories,
                    latestDate = settlements.firstOrNull()?.date ?: "기록 없음"
                )
            }

            item {
                Text(
                    text = "정산 기록",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            itemsIndexed(
                items = settlements,
                key = { _, item -> item.date }
            ) { index, item ->
                val currentWeekId = getWeekIdentifier(item.date)
                // ✨ 표시할 주차 텍스트 생성
                val weekDisplayText = getWeekOfMonthText(item.date)

                if (index == 0) {
                    // 첫 번째 항목에 헤더 표시
                    WeekDivider(headerText = weekDisplayText)
                } else {
                    val previousWeekId = getWeekIdentifier(settlements[index - 1].date)
                    if (currentWeekId != previousWeekId) {
                        // 주차가 바뀌면 헤더 표시
                        WeekDivider(headerText = weekDisplayText)
                    }
                }

                SettlementHistoryItem(
                    item = item,
                    onClick = { onNavigateToDetail(item.date) }
                )
            }
        }
    }
}

/**
 * 2. WeekDivider 컴포저블 수정 (date 대신 headerText 사용)
 */
@Composable
fun WeekDivider(headerText: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Material3 기준 HorizontalDivider 사용 (Divider도 무방)
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Text(
                text = " $headerText 정산 ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/**
 * 신체 정보(키, 시작 체중)를 최초 1회 입력받는 다이얼로그
 */
@Composable
fun ProfileSetupDialog(
    onConfirm: (height: Float, weight: Float) -> Unit
) {
    var heightText by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { /* 필수 입력 사항이므로 닫기 방지 */ },
        title = { Text("신체 정보 입력") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "더 정확한 AI 영양 분석을 위해\n키와 시작 몸무게를 입력해 주세요.",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    label = { Text("키 (cm)") },
                    placeholder = { Text("예: 175.5") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )

                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text("시작 몸무게 (kg)") },
                    placeholder = { Text("예: 70.0") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = heightText.toFloatOrNull() ?: 0f
                    val w = weightText.toFloatOrNull() ?: 0f
                    if (h > 0f && w > 0f) {
                        onConfirm(h, w)
                    }
                },
                enabled = heightText.isNotEmpty() && weightText.isNotEmpty()
            ) {
                Text("저장 및 시작")
            }
        }
    )
}

@Preview
@Composable
fun SummaryHeaderCard(totalCalories: Int, latestDate: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("현재 누적 에너지 밸런스", color = Color.White.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                // ✨ 이제 합산된 totalCalories가 표시됩니다.
                text = "$totalCalories kcal",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "최근 업데이트: $latestDate",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun SettlementHistoryItem(item: SettlementModel, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = item.date, style = MaterialTheme.typography.labelLarge)
                Text(
                    // SettlementModel에 isExercise 필드가 있다고 가정
                    text = if (item.hasExercise) "운동 기록 있음 💪" else "휴식 😴",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "${item.netCalories} kcal",
                style = MaterialTheme.typography.titleMedium,
                color = if (item.netCalories <= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
        }
    }
}