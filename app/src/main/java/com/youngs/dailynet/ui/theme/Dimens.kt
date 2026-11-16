package com.youngs.dailynet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * 모양 치수. 라운드는 네 단만 쓴다 — 칩·입력 12 / 타일 16 / 카드 20 / 히어로·시트 28.
 * 예전에는 3~24dp 사이 11종이 섞여 같은 목록 안에서 카드 20 / 일별 18 / 타일 16 이 나란히 놓였다.
 */
object DnDimens {
    val RadiusChip = 12.dp
    val RadiusTile = 16.dp
    val RadiusCard = 20.dp
    val RadiusHero = 28.dp

    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 20.dp
    val SpaceXxl = 24.dp

    /** 카드 안쪽 여백 */
    val CardPadding = 20.dp

    /** 탄단지 스택 바·진행 바 두께 */
    val BarThin = 6.dp
    val BarThick = 12.dp
}

val DailyNetShapes = Shapes(
    extraSmall = RoundedCornerShape(DnDimens.RadiusChip),
    small = RoundedCornerShape(DnDimens.RadiusChip),
    medium = RoundedCornerShape(DnDimens.RadiusTile),
    large = RoundedCornerShape(DnDimens.RadiusCard),
    extraLarge = RoundedCornerShape(DnDimens.RadiusHero)
)
