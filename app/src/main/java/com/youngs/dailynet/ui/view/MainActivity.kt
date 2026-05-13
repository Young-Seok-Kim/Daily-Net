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
                // AuthViewModel의 user 상태를 관찰
                val user by authViewModel.user.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (user == null) {
                        // 1. 로그인이 안 되어 있으면 로그인 화면
                        LoginScreen(
                            authViewModel = authViewModel,
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        // 2. 로그인 성공 시 메인 화면(식단 입력창)으로 교체!
                        val mainViewModel: MainViewModel = hiltViewModel()
                        MainScreen(
                            mainViewModel = mainViewModel,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}