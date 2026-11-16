package com.youngs.dailynet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/*
 * 동적 색(배경화면 색)은 쓰지 않는다.
 *
 * 예전에는 dynamicColor=true 라 안드로이드 12 이상에서 기기 벽지 색이 primary 를 덮어써
 * 기기마다 앱 색이 달랐고, 웹·스토어 스크린샷과 맞출 방법이 없었다.
 * 이제 라이트·다크 모두 브랜드 팔레트(Color.kt)로 전면 지정한다.
 */
private val LightColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = MintContainerLight,
    onPrimaryContainer = Ink,
    secondary = SubInk,
    onSecondary = Color.White,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = Ink,
    tertiary = Coral,
    onTertiary = Color.White,
    tertiaryContainer = CoralContainerLight,
    onTertiaryContainer = OnCoralContainerLight,
    background = CanvasLight,
    onBackground = Ink,
    surface = SurfaceLight,
    onSurface = Ink,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = SubInk,
    surfaceTint = Ink,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = Ink,
    inverseOnSurface = CanvasLight,
    inversePrimary = Mint,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = CanvasLight,
    surfaceContainer = SurfaceVariantLight,
    surfaceContainerHigh = Color(0xFFE4F0EB),
    surfaceContainerHighest = Color(0xFFDCEBE5),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDCEBE5)
)

private val DarkColorScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = MintContainerDark,
    onPrimaryContainer = MintContainerLight,
    secondary = SubInkDark,
    onSecondary = Ink,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = OnSurfaceDark,
    tertiary = CoralSoft,
    onTertiary = Color(0xFF3A0E05),
    tertiaryContainer = CoralContainerDark,
    onTertiaryContainer = OnCoralContainerDark,
    background = CanvasDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = SubInkDark,
    surfaceTint = Mint,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = OnSurfaceDark,
    inverseOnSurface = Ink,
    inversePrimary = Ink,
    surfaceContainerLowest = Color(0xFF082129),
    surfaceContainerLow = Color(0xFF0E303A),
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceVariantDark,
    surfaceContainerHighest = Color(0xFF1C4A56),
    surfaceBright = Color(0xFF1C4A56),
    surfaceDim = CanvasDark
)

@Composable
fun DailyNetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val netColors = if (darkTheme) DarkNetColors else LightNetColors

    CompositionLocalProvider(LocalNetColors provides netColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DailyNetTypography,
            shapes = DailyNetShapes,
            content = content
        )
    }
}
