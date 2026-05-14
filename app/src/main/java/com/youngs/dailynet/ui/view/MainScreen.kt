package com.youngs.dailynet.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.data.model.SettlementModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onNavigateToInput: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val settlements by mainViewModel.allSettlements.collectAsState()

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
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("DailyNet Dashboard", style = MaterialTheme.typography.titleLarge) }
            )
        },
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
                SummaryHeaderCard(settlements.firstOrNull())
            }

            item {
                Text(
                    text = "정산 기록",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(
                items = settlements,
                key = { it.date }
            ) { item ->
                SettlementHistoryItem(
                    item = item,
                    onClick = { onNavigateToDetail(item.date) }
                )
            }
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

@Composable
fun SummaryHeaderCard(latestItem: SettlementModel?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("현재 누적 에너지 밸런스", color = Color.White.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${latestItem?.netCalories ?: 0} kcal",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "최근 업데이트: ${latestItem?.date ?: "기록 없음"}",
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