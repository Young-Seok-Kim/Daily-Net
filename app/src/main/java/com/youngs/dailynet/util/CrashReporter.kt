package com.youngs.dailynet.util

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * 잡아서 삼킨 예외를 Crashlytics로 올려보낸다.
 *
 * 앱이 죽는 크래시는 Crashlytics가 알아서 잡지만, 이 앱에서 정말 자주 일어나는 문제는
 * **죽지 않고 조용히 실패하는 쪽**이다. 분석이 안 되거나, 프로필을 못 불러오거나,
 * 사진 인식이 실패해도 사용자에겐 토스트 한 줄이고 우리는 아무것도 알 수 없었다.
 *
 * 그런 자리에서 이걸 호출해두면 "몇 명에게 무슨 예외가 났는지"가 콘솔에 쌓인다.
 */
object CrashReporter {

    /**
     * @param where 어디서 났는지 알아볼 수 있는 짧은 이름 (예: "analyzeDiet")
     */
    fun report(where: String, e: Throwable) {
        Log.w(TAG, "[$where] ${e.message}", e)
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                // 스택만으로는 어느 흐름이었는지 알기 어려워 위치를 함께 남긴다
                setCustomKey(KEY_WHERE, where)
                recordException(e)
            }
        }
    }

    private const val TAG = "DailyNet"
    private const val KEY_WHERE = "where"
}
