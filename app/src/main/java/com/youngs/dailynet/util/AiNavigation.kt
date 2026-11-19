package com.youngs.dailynet.util

import com.youngs.dailynet.data.model.AiScreenTypes

/**
 * AI 답변에 붙어 온 "이 화면으로 가 보세요"를 MainActivity의 화면 이름으로 바꾼다.
 *
 * 서버(askAiCoach.js splitNavigation)는 화면 종류(AiScreenTypes 값)와 선택적인 날짜 인자만 준다.
 * 어느 화면 이름(ScreenEntry.screen)과 날짜 칸으로 열지는 앱이 정한다 — 서버가 앱의 화면 이름을
 * 알 필요가 없고, 화면 구조를 바꿔도 서버는 그대로다.
 *
 * MainActivity 밖에 둔 이유는 순수 함수로 테스트하기 위해서다 (ScreenEntry는 MainActivity 안에 있다).
 */
object AiNavigation {

    /** AI가 가리킨 화면. [screen]은 AiScreenTypes 값, [arg]는 서버가 검증한 날짜 인자(없으면 null) */
    data class Target(val screen: String, val arg: String? = null)

    /** MainActivity의 ScreenEntry(screen, date)와 같은 꼴 */
    data class Route(val screen: String, val date: String = "")

    /**
     * @param today yyyy-MM-dd. 날짜가 필요한 화면에 인자가 없을 때 쓴다
     * @param currentWeekLabel 주 정산 화면의 label (getWeekIdentifier(today).toString())
     * @return 모르는 화면이면 null — 버튼이 눌렸어도 아무 데도 가지 않는다
     */
    fun resolve(target: Target, today: String, currentWeekLabel: String): Route? = when (target.screen) {
        AiScreenTypes.DAILY_LIST -> Route("main")
        AiScreenTypes.DAILY_RECORD -> Route("detail", target.arg ?: today)
        AiScreenTypes.MONTH_REPORT -> Route("month", target.arg ?: today.take(7))
        // 주·연 중 어디로 갈지 서버가 고르지 않으므로 이번 주로 연다. 연 정산은 목록에서 한 번 더 누르면 된다
        AiScreenTypes.PERIOD_REPORT -> Route("period", "week:$currentWeekLabel")
        AiScreenTypes.INBODY_LIST -> Route("inbody")
        AiScreenTypes.INBODY_TREND -> Route("inbody_trend")
        AiScreenTypes.WEIGHT_TREND -> Route("weight", today)
        else -> null
    }
}
