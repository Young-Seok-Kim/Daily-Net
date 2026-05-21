package com.youngs.dailynet.ui


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.youngs.dailynet.ui.theme.DailyNetTheme
import com.youngs.dailynet.ui.view.LoginScreen
import com.youngs.dailynet.ui.view.MainScreen
import com.youngs.dailynet.ui.view.SettlementScreen
import com.youngs.dailynet.ui.viewmodel.AuthViewModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContent {
            DailyNetTheme {
                val user by authViewModel.user.collectAsState()

                var currentScreen by remember { mutableStateOf("main") } // "main", "input", "detail"
                var selectedDate by remember { mutableStateOf("") }     // 상세 화면에 넘겨줄 날짜

                LaunchedEffect(user) {
                    if (user == null) {
                        currentScreen = "login"
                    }
                }


                Scaffold(modifier = Modifier.Companion.fillMaxSize()) { innerPadding ->
                    if (user == null || currentScreen == "login") {
                        LoginScreen(
                            authViewModel = authViewModel,
                            onNavigateToMain = {
                                currentScreen = "main"
                            },
                            modifier = Modifier.Companion.padding(innerPadding)
                        )
                    } else {
                        val mainViewModel: MainViewModel = hiltViewModel()

                        when (currentScreen) {

                            "input" -> {
                                SettlementScreen(
                                    mainViewModel = mainViewModel,
                                    onBack = { currentScreen = "main" },
                                    isReadOnly = false
                                )
                            }

                            "detail" -> {
                                // [과거 기록 상세] - 읽기 전용 모드
                                // 💡 화면이 뜰 때 해당 날짜 데이터를 불러오도록 설정
                                LaunchedEffect(selectedDate) {
                                    mainViewModel.loadDateData(selectedDate)
                                }

                                SettlementScreen(
                                    mainViewModel = mainViewModel,
                                    onBack = {
                                        currentScreen = "main"
                                    },
                                    isReadOnly = true
                                )
                            }

                            else -> {
                                MainScreen(
                                    mainViewModel = mainViewModel,
                                    onNavigateToInput = {
//                                        mainViewModel.prepareNewSettlement()
//                                        mainViewModel.resetStateForNewInput()
                                        mainViewModel.loadOrCreateTodayDraft() // 오늘 날짜 로드 후 이동
                                        currentScreen = "input"
                                    },
                                    onNavigateToDetail = { date ->
                                        selectedDate = date // 클릭한 날짜 저장
                                        currentScreen = "detail"
                                    },
                                    onNavigateToLogin = {
                                        authViewModel.resetUserState()
                                        currentScreen = "login"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}