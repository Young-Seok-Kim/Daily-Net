package com.youngs.dailynet.ui


import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.youngs.dailynet.ui.theme.DailyNetTheme
import com.youngs.dailynet.ui.view.AppUpdateDialog
import com.youngs.dailynet.ui.view.LoginScreen
import com.youngs.dailynet.ui.view.MainScreen
import com.youngs.dailynet.ui.view.MonthReportScreen
import com.youngs.dailynet.ui.view.DailyRecordScreen
import com.youngs.dailynet.ui.view.WeightTrendScreen
import com.youngs.dailynet.ui.view.SettingsScreen
import com.youngs.dailynet.ui.view.SplashScreen
import com.youngs.dailynet.ui.view.WithdrawScreen
import com.youngs.dailynet.ui.viewmodel.AuthViewModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import com.youngs.dailynet.util.AppUpdateChecker
import com.youngs.dailynet.util.AppUpdateInfo
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject


/**
 * 화면 한 칸. 뒤로가기 기록에 쌓인다.
 *
 * @param screen "main" / "input" / "detail" / "settings" / "withdraw" / "weight" / "month"
 * @param date   그 화면이 다루는 날짜.
 *               "detail"이면 보여줄 정산 날짜, "weight"면 그래프를 열 기준 날짜.
 *               "month"만 날짜가 아니라 "yyyy-MM" 형태의 달을 담는다.
 *               나머지 화면은 쓰지 않는다.
 */
private data class ScreenEntry(val screen: String, val date: String = "")

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()

    @Inject
    lateinit var appUpdateChecker: AppUpdateChecker

    /**
     * 밖에서(위젯 등) 특정 화면을 지정해 들어온 경우 그 화면 이름.
     *
     * 값을 한 번 쓰고 비우기 위해 상태로 둔다. 그러지 않으면 화면을 옮긴 뒤에도
     * 재구성될 때마다 다시 그 화면으로 끌려간다.
     */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 앱이 이미 떠 있는 상태에서 위젯을 누르면 onCreate가 아니라 여기로 들어온다
        setIntent(intent)
        pendingRoute = intent.getStringExtra(EXTRA_ROUTE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)
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

                // 위젯을 눌러 들어온 경우 지정된 화면으로 한 번만 이동한다.
                //
                // 로그인 전이면 아직 옮기지 않는다. 로그인 화면이 먼저 떠야 하고,
                // pendingRoute를 비우지 않은 채 두면 로그인을 마친 뒤(user가 채워지면)
                // 이 블록이 다시 돌아 원래 가려던 화면으로 이어진다.
                LaunchedEffect(pendingRoute, user) {
                    val route = pendingRoute ?: return@LaunchedEffect
                    if (user == null) return@LaunchedEffect

                    if (current.screen != route) navigateTo(ScreenEntry(route))
                    pendingRoute = null
                }

                // 서버와 형식이 맞지 않는 구버전을 걸러내기 위한 업데이트 안내.
                // 로그인 여부와 무관하게 앱을 켜자마자 한 번 확인한다.
                var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
                LaunchedEffect(Unit) {
                    updateInfo = appUpdateChecker.check()
                }


                // 앱을 켠 직후 한 번만 보여주는 스플래시.
                // 화면 회전 등으로 액티비티가 다시 만들어질 때 또 나오지 않도록 saveable로 둔다.
                // 위젯처럼 밖에서 특정 화면을 지정해 들어온 경우(pendingRoute가 있음)는
                // 그 화면을 바로 열어야 하므로 스플래시를 건너뛴다.
                var showSplash by rememberSaveable { mutableStateOf(pendingRoute == null) }

                Box(modifier = Modifier.Companion.fillMaxSize()) {
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
                                // 리컴포지션마다 다시 불러오면 입력 중인 내용/자동 기입된 걸음수가 날아가므로
                                // 화면에 진입할 때 한 번만 로드한다.
                                LaunchedEffect(Unit) {
                                    mainViewModel.prepareDailyRecordData(
                                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                    )
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
                                    isReadOnly = false,
                                    // 좌우 스와이프로 전날·다음날을 연다.
                                    // 기록에 쌓지 않고 현재 칸만 바꿔서, 여러 날을 넘긴 뒤 뒤로가기를
                                    // 눌러도 날짜를 하나씩 되짚지 않고 들어온 화면으로 바로 돌아간다.
                                    onSwipeDate = { date ->
                                        current = ScreenEntry("detail", date)
                                    }
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

                            "month" -> {
                                MonthReportScreen(
                                    mainViewModel = mainViewModel,
                                    yearMonth = current.date,
                                    onBack = { goBack() },
                                    onNavigateToDetail = { date ->
                                        navigateTo(ScreenEntry("detail", date))
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
                                    },
                                    onNavigateToMonth = { yearMonth ->
                                        navigateTo(ScreenEntry("month", yearMonth))
                                    }
                                )
                            }
                        }
                    }
                }

                if (showSplash) {
                    SplashScreen(onFinished = { showSplash = false })
                }
                }

                // 화면 종류와 상관없이 항상 위에 덮이도록 Scaffold 바깥에 둔다.
                // 스플래시가 끝나기 전에는 띄우지 않는다 (애니메이션 위로 대화상자가 튀어나오지 않게).
                updateInfo?.takeIf { !showSplash }?.let { info ->
                    AppUpdateDialog(
                        info = info,
                        onLater = { updateInfo = null },
                        onUpdate = { appUpdateChecker.openStore(this@MainActivity) }
                    )
                }
            }
        }
    }

    companion object {
        /** 밖에서 열 화면을 지정할 때 쓰는 인텐트 키. 값은 [ScreenEntry.screen]과 같은 이름이다. */
        const val EXTRA_ROUTE = "com.youngs.dailynet.extra.ROUTE"

        /** 오늘 정산 입력 화면 */
        const val ROUTE_INPUT = "input"
    }
}