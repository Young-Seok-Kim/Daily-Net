package com.youngs.dailynet.ui.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.youngs.dailynet.ui.theme.DailyNetTheme
import com.youngs.dailynet.ui.viewmodel.AuthViewModel
import com.youngs.dailynet.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DailyNetTheme {
                val user by authViewModel.user.collectAsState()

                // 화면 전환을 위한 상태 관리 (임포트한 remember, getValue, setValue 사용)
                var showInputScreen by remember { mutableStateOf(false) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (user == null) {
                        LoginScreen(authViewModel = authViewModel, modifier = Modifier.padding(innerPadding))
                    } else {
                        val mainViewModel: MainViewModel = hiltViewModel()

                        if (showInputScreen) {
                            // 'onBack' 파라미터가 SettlementScreen 정의부에 추가되어야 에러가 사라집니다.
                            SettlementScreen(
                                mainViewModel = mainViewModel,
                                onBack = { showInputScreen = false },
                                modifier = Modifier.padding(innerPadding)
                            )
                        } else {
                            MainScreen(
                                mainViewModel = mainViewModel,
                                onNavigateToInput = { showInputScreen = true },
                                onNavigateToDetail = { /* 상세 이동 로직 */ },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }
}