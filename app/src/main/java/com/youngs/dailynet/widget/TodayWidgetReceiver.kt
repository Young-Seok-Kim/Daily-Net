package com.youngs.dailynet.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 시스템이 위젯을 만들고 갱신할 때 부르는 진입점.
 *
 * 실제 화면은 [TodayWidget]이 그리고, 여기서는 연결만 한다.
 */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
