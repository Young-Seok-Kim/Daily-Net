package com.youngs.dailynet.util

object Constants {
    const val BASE_URL = "https://analyzediet-muvblvcmkq-du.a.run.app/"

    /**
     * 음식 사진에서 메뉴를 읽어오는 함수 주소.
     *
     * Cloud Functions는 함수마다 URL이 달라 baseUrl로 묶을 수 없다.
     * analyzeDiet은 예전에 배포돼 run.app 형태지만 이 함수는 cloudfunctions.net 형태다.
     * 형태를 짐작하지 말고 `firebase deploy` 출력의 Function URL을 그대로 옮겨야 한다.
     */
    const val EXTRACT_MEAL_URL =
        "https://asia-northeast3-daily-net-95d28.cloudfunctions.net/extractMeal"

    /** 구매 토큰을 검증받는 함수 주소. 구독 여부는 서버가 Play에 물어봐서 정한다. */
    const val VERIFY_SUBSCRIPTION_URL =
        "https://asia-northeast3-daily-net-95d28.cloudfunctions.net/verifySubscription"

    /** 연결 수립 타임아웃 */
    const val CONNECT_TIMEOUT_MS = 15000L

    /**
     * 응답 대기 타임아웃.
     * 분석 Cloud Function은 timeoutSeconds=120 이고 내부적으로 최대 3회 재시도하므로,
     * 30초로 끊으면 정상 분석도 실패로 떨어진다. 서버 한도에 맞춘다.
     */
    const val REQUEST_TIMEOUT_MS = 120000L

    /**
     * Remote Config 응답 대기 한도.
     * 이걸 넘기면 업데이트 검사를 포기하고 앱을 그대로 연다.
     * (지하철처럼 느린 회선에서 앱 진입이 막히면 안 되므로 짧게 잡는다)
     */
    const val REMOTE_CONFIG_TIMEOUT_MS = 4000L

    /**
     * Remote Config 캐시 유효 시간.
     * SDK 기본값은 12시간이라 콘솔에서 값을 바꿔도 반영이 너무 늦다.
     * 긴급 차단이 필요한 용도라 1시간으로 줄인다.
     */
    const val REMOTE_CONFIG_FETCH_INTERVAL_SEC = 3600L

    // Remote Config 파라미터 키 (Firebase 콘솔의 이름과 동일해야 한다)
    const val RC_MIN_VERSION_CODE = "min_version_code"
    const val RC_RECOMMEND_VERSION_CODE = "recommend_version_code"
    const val RC_FORCE_UPDATE_MESSAGE = "force_update_message"
    const val RC_RECOMMEND_UPDATE_MESSAGE = "recommend_update_message"

    // SharedPreferences
    const val PREFS_NAME = "user_prefs"
    const val KEY_TREND_EXPANDED = "trend_expanded"

    /** 권장 업데이트 안내를 마지막으로 보여준 날짜(yyyy-MM-dd). 하루 한 번만 띄우기 위해 쓴다. */
    const val KEY_UPDATE_NOTICE_DATE = "update_notice_date"

    /** 정산 리마인더 알림 사용 여부 */
    const val KEY_REMINDER_ENABLED = "reminder_enabled"

    /** 마지막으로 정산을 끝낸 날짜(yyyy-MM-dd). 그날은 리마인더를 띄우지 않는다. */
    const val KEY_LAST_RECORDED_DATE = "last_recorded_date"
}