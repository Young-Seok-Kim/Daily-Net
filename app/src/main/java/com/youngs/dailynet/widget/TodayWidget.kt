package com.youngs.dailynet.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.youngs.dailynet.R
import com.youngs.dailynet.data.local.entity.dao.DailyRecordDao
import com.youngs.dailynet.ui.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 홈 화면에 오늘의 순칼로리를 띄우는 2x1 위젯.
 *
 * 저녁 9시에 한 번 울리는 리마인더와 목적이 다르다. 리마인더는 놓치면 끝이지만
 * 위젯은 홈 화면을 볼 때마다 눈에 들어와, 앱을 여는 이유를 하루 종일 남겨둔다.
 *
 * 상태는 둘뿐이다.
 * - **정산 전**: 순칼로리는 분석을 마쳐야 생기는 값이라 보여줄 게 없다.
 *   대신 "정산하기"를 띄워 그 자리를 입력 유도로 쓴다.
 * - **정산 완료**: 순칼로리를 적자(초록) / 흑자(빨강)로 보여준다.
 *   색 기준은 정산 화면([DailyRecordScreen])과 같아야 해서 값을 맞춰 뒀다.
 *
 * 데이터는 Room만 읽는다. 위젯이 네트워크를 타면 홈 화면을 넘길 때마다
 * Firestore를 왕복하게 되고, 오프라인에서는 빈 위젯이 된다.
 */
class TodayWidget : GlanceAppWidget() {

    /**
     * 크기별로 다른 레이아웃을 준비한다.
     *
     * 기본은 두 칸(2x1)이다. 값이 숫자 하나뿐이라 한 칸으로도 줄여봤는데,
     * 폰의 좁은 셀에서는 "정산하기"조차 다 보이지 않았다.
     *
     * 대신 줄이는 것 자체는 막지 않는다. 한 칸까지 좁아지면 설명 문구를 빼고
     * 숫자와 단위만 남긴다. 두 크기를 미리 그려두므로 크기를 바꿔도 다시 그리는 지연이 없다.
     */
    override val sizeMode = SizeMode.Responsive(setOf(CompactSize, WideSize))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = todayDate()
        val dao = dao(context)

        // 첫 화면에 쓸 값을 미리 읽어둔다.
        // 흐름의 초기값을 null로 두면 위젯을 그릴 때마다 "정산 전"이 한 번 스쳐 지나간다.
        val initial = runCatching { dao.getDailyRecordByDate(today) }.getOrNull()

        provideContent {
            // 위젯이 살아 있는 동안 Room을 직접 지켜본다.
            //
            // 밖에서 updateAll()로 밀어넣는 경로만 두면, 이미 돌고 있는 세션이 끝나기를
            // 기다리느라 분석을 끝내고도 1분 넘게 옛 값이 남는다. (실제로 겪은 증상이다)
            // 여기서 구독하면 기록이 저장되는 순간 곧바로 다시 그려진다.
            //
            // updateAll() 경로도 그대로 둔다. 위젯이 잠들어 세션이 없을 때는
            // 이 구독이 돌지 않으므로 둘 다 필요하다.
            val record by remember(today) { dao.observeDailyRecordByDate(today) }
                .collectAsState(initial = initial)

            // 분석을 마친 기록만 값을 갖는다. 없으면 정산 전으로 본다.
            WidgetContent(context, record?.takeIf { it.finalized }?.netCalories)
        }
    }

    @Composable
    private fun WidgetContent(context: Context, netCalories: Int?) {
        // 탭하면 오늘 정산 화면으로 바로 들어간다.
        // 메인으로 보내면 위젯을 누른 이유(오늘 기록)를 한 번 더 찾아 들어가야 한다.
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_INPUT)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // 한 칸짜리인지. 폭으로만 판단한다 — 세로로 늘려도 글자가 들어갈 가로 공간은 그대로다.
        val compact = LocalSize.current.width < WideSize.width

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(BackgroundColor)
                .cornerRadius(16.dp)
                .clickable(actionStartActivity(intent))
                .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.Start
        ) {
            if (netCalories == null) {
                Text(
                    text = context.getString(R.string.widget_not_recorded),
                    style = TextStyle(
                        color = AccentColor,
                        fontSize = if (compact) 14.sp else 17.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                // 한 칸에서는 안내 문구를 넣을 자리가 없다. "정산하기"만으로 뜻이 통한다.
                if (!compact) {
                    Text(
                        text = context.getString(R.string.widget_not_recorded_hint),
                        style = TextStyle(color = SubTextColor, fontSize = 11.sp),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    // 천 단위 구분이 없으면 -1240이 한 덩어리로 읽혀 자릿수를 놓친다
                    text = String.format(Locale.getDefault(), "%,d", netCalories),
                    style = TextStyle(
                        // 정산 화면과 같은 기준: 0 이하는 적자(초록), 초과는 흑자(빨강)
                        color = if (netCalories <= 0) DeficitColor else SurplusColor,
                        fontSize = if (compact) 18.sp else 24.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                Text(
                    // 단위는 한 칸에서도 남긴다. 숫자만 있으면 무슨 값인지 알 수 없다.
                    text = context.getString(
                        if (compact) R.string.widget_kcal_short else R.string.widget_kcal_today
                    ),
                    style = TextStyle(color = SubTextColor, fontSize = if (compact) 10.sp else 11.sp),
                    maxLines = 1
                )
            }
        }
    }

    companion object {
        /**
         * 한 칸(1x1) 크기. 런처 셀 계산식 70*n - 30 에 따라 40dp.
         * 이 크기에서는 숫자와 단위만 남긴다.
         */
        private val CompactSize = DpSize(40.dp, 40.dp)

        /** 두 칸(2x1) 이상. 설명 문구까지 들어간다. */
        private val WideSize = DpSize(110.dp, 40.dp)

        /**
         * 위젯 색은 전부 색 리소스로 둔다.
         *
         * Glance 1.1.1에는 day/night를 코드에서 함께 지정하는 ColorProvider가 없다.
         * 리소스로 두면 `values-night/colors.xml`이 다크모드를 알아서 처리하고,
         * 위젯이 그려지는 시점에 시스템 테마에 맞는 값이 해석된다.
         *
         * 배경을 투명하게 두지 않는 이유는, 밝은 배경화면 위에서 숫자가 사라지기 때문이다.
         */
        private val BackgroundColor = ColorProvider(R.color.widget_background)
        private val SubTextColor = ColorProvider(R.color.widget_sub_text)
        private val AccentColor = ColorProvider(R.color.widget_accent)
        private val DeficitColor = ColorProvider(R.color.widget_deficit)
        private val SurplusColor = ColorProvider(R.color.widget_surplus)

        /**
         * 앱과 같은 방식으로 오늘 날짜를 구한다.
         *
         * 위젯이 그릴 때마다 새로 계산하므로, 앱을 켜둔 채 자정을 넘겨도 위젯은 날짜가 바뀐다.
         */
        private fun todayDate(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        private fun dao(context: Context): DailyRecordDao =
            EntryPointAccessors
                .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                .dailyRecordDao()

        /**
         * 홈 화면에 붙어 있는 모든 위젯을 다시 그린다.
         *
         * 기록이 바뀌는 지점(분석 완료 / 기록 삭제 / 로그아웃)에서 호출한다.
         * 위젯이 스스로 갱신되는 주기는 30분이라, 이 호출이 없으면 방금 분석한 결과가
         * 최대 30분 동안 위젯에 안 나타난다.
         */
        suspend fun refresh(context: Context) {
            runCatching { TodayWidget().updateAll(context.applicationContext) }
        }
    }
}

/**
 * 위젯은 Hilt가 생성자 주입을 해주지 않으므로(시스템이 만든다) EntryPoint로 꺼내 쓴다.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun dailyRecordDao(): DailyRecordDao
}
