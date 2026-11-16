package com.youngs.dailynet.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * 브랜드 팔레트. 웹 랜딩(hosting/index.html)의 --mint / --ink / --sub / --coral 과 같은 값이다.
 *
 * 민트와 코랄은 흰 배경에서 각각 1.63:1, 3.31:1 이라 작은 글자에 쓸 수 없다.
 * 배지·막대·FAB·강조 카드처럼 "면적"에만 쓰고, kcal 숫자 같은 글자는 잉크 계열과 적자/흑자 색으로 처리한다.
 */
val Ink = Color(0xFF06303F)
val SubInk = Color(0xFF245C6C)
val Mint = Color(0xFF5FE1B0)
val Coral = Color(0xFFFF4D2E)
val CoralSoft = Color(0xFFFF8A6B)

// 라이트
val CanvasLight = Color(0xFFF6FAF8)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFEDF6F2)
val OutlineLight = Color(0xFF7F9C97)
val OutlineVariantLight = Color(0xFFC9DCD6)
val MintContainerLight = Color(0xFFD5F5E8)
val CoralContainerLight = Color(0xFFFFE3DC)
val OnCoralContainerLight = Color(0xFF7A1F0C)

/** 흰 배경 5.32:1. 적자(감량 방향) kcal 숫자 */
val DeficitLight = Color(0xFF0E7A5A)
/** 흰 배경 4.95:1. 흑자 kcal 숫자 */
val SurplusLight = Color(0xFFCE3A1B)

// 다크
val CanvasDark = Color(0xFF0B2A33)
val SurfaceDark = Color(0xFF10353F)
val SurfaceVariantDark = Color(0xFF16424D)
val OutlineDark = Color(0xFF4F7B84)
val OutlineVariantDark = Color(0xFF2C5A63)
val OnSurfaceDark = Color(0xFFE6F2EE)
val SubInkDark = Color(0xFF9FB8B3)
val MintContainerDark = Color(0xFF1E4F5C)
val CoralContainerDark = Color(0xFF5A2316)
val OnCoralContainerDark = Color(0xFFFFD9CF)
val DeficitDark = Color(0xFF7BF0C8)
val SurplusDark = Color(0xFFFF8A6B)

// 탄·단·지 (라이트·다크 공통. 막대·칩 면적에만 쓴다)
val MacroCarb = Color(0xFFF2B544)
val MacroProtein = Color(0xFF2E9E5B)
val MacroFat = Color(0xFF5B93F0)

/**
 * Material 3 에는 "적자/흑자" 슬롯이 없다. 화면마다 원색을 박아 두면(예전에 24곳) 한 곳만 고쳐도 어긋나므로
 * 여기 모아 CompositionLocal 로 내려보낸다. 색이 필요한 화면은 [DnTheme.net] 만 본다.
 */
@Immutable
data class NetColors(
    /** 순칼로리가 0 이하(감량 방향)인 값의 글자색 */
    val deficit: Color,
    /** 순칼로리가 양수인 값의 글자색 */
    val surplus: Color,
    /** 면적 전용 강조색 (막대·배지·히어로 카드 라벨) */
    val mint: Color,
    /** 면적 전용 경고·행동색 (FAB·초과 막대) */
    val coral: Color,
    /** 히어로 카드처럼 짙은 바탕 */
    val heroBackground: Color,
    /** 짙은 바탕 위의 글자 */
    val onHero: Color,
    /** 일요일·공휴일 날짜 글자 */
    val sunday: Color,
    /** 토요일 날짜 글자 */
    val saturday: Color,
    val carb: Color,
    val protein: Color,
    val fat: Color,
    /** 탄단지 칩 바탕 (글자는 각 영양소 색의 어두운 버전) */
    val carbContainer: Color,
    val proteinContainer: Color,
    val fatContainer: Color,
    val onCarbContainer: Color,
    val onProteinContainer: Color,
    val onFatContainer: Color
) {
    /** 순칼로리 부호에 따른 글자색. 0은 적자 쪽으로 친다 (앱 전체 규칙) */
    fun of(net: Int): Color = if (net <= 0) deficit else surplus
    fun of(net: Float): Color = if (net <= 0f) deficit else surplus
}

val LightNetColors = NetColors(
    deficit = DeficitLight,
    surplus = SurplusLight,
    mint = Mint,
    coral = Coral,
    heroBackground = Ink,
    onHero = Color.White,
    sunday = SurplusLight,
    saturday = Color(0xFF2F6FD6),
    carb = MacroCarb,
    protein = MacroProtein,
    fat = MacroFat,
    carbContainer = Color(0xFFFFF1D6),
    proteinContainer = Color(0xFFDDF3E4),
    fatContainer = Color(0xFFE2ECFF),
    onCarbContainer = Color(0xFF8A5A00),
    onProteinContainer = Color(0xFF1C6B3C),
    onFatContainer = Color(0xFF2A5BB8)
)

val DarkNetColors = NetColors(
    deficit = DeficitDark,
    surplus = SurplusDark,
    mint = Mint,
    coral = CoralSoft,
    heroBackground = Color(0xFF0F3D4C),
    onHero = Color.White,
    sunday = SurplusDark,
    saturday = Color(0xFF8FB5FF),
    carb = MacroCarb,
    protein = MacroProtein,
    fat = MacroFat,
    carbContainer = Color(0xFF4A3A14),
    proteinContainer = Color(0xFF1C4A32),
    fatContainer = Color(0xFF233E6B),
    onCarbContainer = Color(0xFFFFD98A),
    onProteinContainer = Color(0xFFA8E8C0),
    onFatContainer = Color(0xFFBBD0FF)
)

val LocalNetColors = staticCompositionLocalOf { LightNetColors }

/** 테마에서 꺼내 쓰는 입구. `DnTheme.net.deficit` 처럼 쓴다 */
object DnTheme {
    val net: NetColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNetColors.current
}
