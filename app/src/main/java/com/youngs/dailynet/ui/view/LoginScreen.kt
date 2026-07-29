package com.youngs.dailynet.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.youngs.dailynet.R // 본인 패키지명에 맞게 확인
import com.youngs.dailynet.ui.viewmodel.AuthViewModel

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onNavigateToMain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 앱 로고나 타이틀
        Text(
            text = "DailyNet",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.login_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(48.dp))

        Image(
            painter = painterResource(id = R.drawable.android_light_rd_si),
            contentDescription = stringResource(R.string.cd_google_login),
            modifier = Modifier
                .fillMaxWidth(0.8f) // 원하는 너비 조절
                .height(56.dp)      // 공식 이미지 비율에 맞춰 조절
                .padding(horizontal = 8.dp)
                .clickable {
                    authViewModel.signIn(context) { success ->
                        if (success) {
                            onNavigateToMain() // 성공 시 이동!
                        }
                    }
                } // 이미지 자체를 클릭 가능하게!
        )
    }
}