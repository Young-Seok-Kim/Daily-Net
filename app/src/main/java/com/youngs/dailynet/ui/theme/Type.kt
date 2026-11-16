package com.youngs.dailynet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * 폰트는 시스템 기본을 쓰고 크기·굵기·자간으로만 위계를 잡는다.
 * (웹은 Pretendard 지만 번들하면 APK 가 2MB 이상 늘어 1차에서는 보류)
 *
 * 전역 자간은 0. 숫자는 [tabular] 로 고정폭 자릿수를 켜서 목록에서 kcal 자릿수가 흔들리지 않게 한다.
 */
private val Base = FontFamily.Default

val DailyNetTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, lineHeight = 26.sp, letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Base, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp
    )
)

/** 숫자용. 자릿수 폭을 고정(tnum)해 세로로 나열된 kcal 이 흔들리지 않는다 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
