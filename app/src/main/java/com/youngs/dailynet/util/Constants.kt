package com.youngs.dailynet.util

object Constants {
    const val BASE_URL = "https://analyzediet-muvblvcmkq-du.a.run.app/"

    /** 연결 수립 타임아웃 */
    const val CONNECT_TIMEOUT_MS = 15000L

    /**
     * 응답 대기 타임아웃.
     * 분석 Cloud Function은 timeoutSeconds=120 이고 내부적으로 최대 3회 재시도하므로,
     * 30초로 끊으면 정상 분석도 실패로 떨어진다. 서버 한도에 맞춘다.
     */
    const val REQUEST_TIMEOUT_MS = 120000L

    // SharedPreferences
    const val PREFS_NAME = "user_prefs"
    const val KEY_TREND_EXPANDED = "trend_expanded"
}