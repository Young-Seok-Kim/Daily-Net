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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.youngs.dailynet.ui.theme.DailyNetTheme
import com.youngs.dailynet.ui.view.LoginScreen
import com.youngs.dailynet.ui.view.MainScreen
import com.youngs.dailynet.ui.view.DailyRecordScreen
import com.youngs.dailynet.ui.view.WeightTrendScreen
import com.youngs.dailynet.ui.view.SettingsScreen
import com.youngs.dailynet.ui.view.WithdrawScreen
import com.youngs.dailynet.ui.viewmodel.AuthViewModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * 화면 한 칸. 뒤로가기 기록에 쌓인다.
 *
 * @param screen "main" / "input" / "detail" / "settings" / "withdraw" / "weight"
 * @param date   그 화면이 다루는 날짜.
 *               "detail"이면 보여줄 정산 날짜, "weight"면 그래프를 열 기준 날짜.
 *               나머지 화면은 쓰지 않는다.
 */
private data class ScreenEntry(val screen: String, val date: String = "")

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
                val mainViewModel: MainViewModel = hiltViewModel()

                // 지나온 화면을 쌓아두고 뒤로가기 때 직전 화면으로 되돌린다.
                // (그래프 → 정산 상세 → 뒤로가기 = 그래프 처럼, 들어온 경로 그대로 되짚기 위함)
                //
                // 화면 이름뿐 아니라 그 화면이 보고 있던 날짜까지 함께 쌓는다.
                // 날짜를 공용 변수 하나로 두면, 그래프에서 다른 날짜를 열었을 때 그 값이 덮어써져
                // 뒤로 돌아왔을 때 원래 보던 날짜가 아니라 나중에 연 날짜가 나온다.
                var current by remember { mutableStateOf(ScreenEntry("main")) }
                val backStack = remember { mutableStateListOf<ScreenEntry>() }

                fun navigateTo(entry: ScreenEntry) {
                    backStack.add(current)
                    current = entry
                }

                fun goBack() {
                    current = backStack.removeLastOrNull() ?: ScreenEntry("main")
                }

                // 로그인/로그아웃처럼 흐름이 끊기는 이동은 기록을 비운다
                fun resetTo(entry: ScreenEntry) {
                    backStack.clear()
                    current = entry
                }

                LaunchedEffect(user) {
                    if (user == null) {
                        current = ScreenEntry("login")
                    }
                }


                Scaffold(modifier = Modifier.Companion.fillMaxSize()) { innerPadding ->
                    if (user == null || current.screen == "login") {
                        LoginScreen(
                            authViewModel = authViewModel,
                            onNavigateToMain = {
                                resetTo(ScreenEntry("main"))
                            },
                            modifier = Modifier.Companion.padding(innerPadding)
                        )
                    } else {
                        when (current.screen) {

                            "input" -> {
                                mainViewModel.prepareDailyRecordData(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))

                                DailyRecordScreen(
                                    mainViewModel = mainViewModel,
                                    onBack = { goBack() },
                                    onNavigateToWeightTrend = { date ->
                                        navigateTo(ScreenEntry("weight", date))
                                    },
                                    isReadOnly = false
                                )
                            }

                            "detail" -> {
                                // 뒤로가기로 이 화면에 돌아오면 current.date가 원래 날짜로 복원되므로,
                                // 그때 그 날짜의 정산을 다시 불러온다
                                val detailDate = current.date
                                LaunchedEffect(detailDate) {
                                    if (detailDate.isNotEmpty()) {
                                        mainViewModel.prepareDailyRecordData(detailDate)
                                    }
                                }

                                DailyRecordScreen(
                                    mainViewModel = mainViewModel,
                                    onBack = { goBack() },
                                    onNavigateToWeightTrend = { date ->
                                        navigateTo(ScreenEntry("weight", date))
                                    },
                                    isReadOnly = false
                                )
                            }

                            "settings" -> {
                                SettingsScreen(
                                    mainViewModel = mainViewModel,
                                    onBack = { goBack() },
                                    onNavigateToLogin = {
                                        authViewModel.resetUserState()
                                        resetTo(ScreenEntry("login"))
                                    },
                                    onNavigateToWithdraw = { navigateTo(ScreenEntry("withdraw")) }
                                )
                            }

                            "withdraw" -> {
                                WithdrawScreen(
                                    mainViewModel = mainViewModel,
                                    onBack = { goBack() },
                                    onNavigateToLogin = {
                                        authViewModel.resetUserState()
                                        resetTo(ScreenEntry("login"))
                                    }
                                )
                            }

                            "weight" -> {
                                WeightTrendScreen(
                                    mainViewModel = mainViewModel,
                                    focusDate = current.date,
                                    onBack = { goBack() },
                                    onNavigateToDetail = { date ->
                                        navigateTo(ScreenEntry("detail", date))
                                    }
                                )
                            }

                            else -> {
                                MainScreen(
                                    mainViewModel = mainViewModel,
                                    onNavigateToInput = {
                                        navigateTo(ScreenEntry("input"))
                                    },
                                    onNavigateToDetail = { date ->
                                        navigateTo(ScreenEntry("detail", date))
                                    },
                                    onNavigateToLogin = {
                                        authViewModel.resetUserState()
                                        resetTo(ScreenEntry("login"))
                                    },
                                    onNavigateToSettings = {
                                        navigateTo(ScreenEntry("settings"))
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