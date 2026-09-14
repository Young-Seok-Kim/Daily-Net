package com.youngs.dailynet.util

import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 분석이 끝난 뒤 걸음 수가 기준을 넘으면 서울시 손목닥터9988 앱을 띄운다.
 *
 * 손목닥터는 앱을 열어야 그날 걸음 수가 동기화되어 포인트가 쌓인다.
 * 정산까지 마친 사용자가 매번 따로 열지 않아도 되도록 대신 열어주는 것이다.
 *
 * - 무제한 사용자(`unlimited_users`)에게만 적용한다. 호출하는 쪽에서 거른다
 * - 오늘 기록을 분석했을 때만. 지난 날짜의 걸음은 지금 열어봐야 의미가 없다
 * - 하루에 한 번만. 같은 날 다시 분석해도 두 번 뜨지 않는다
 * - 앱이 없으면 아무것도 하지 않는다. 안내도 띄우지 않는다
 *
 * 패키지명은 서울시가 직접 배포하는 현재 앱 하나만 본다. 안드로이드 11부터는 매니페스트
 * `<queries>`에 적어둔 패키지만 보이므로 여기를 바꾸면 매니페스트도 같이 고쳐야 한다.
 */
object WristDoctorLauncher {
    private const val TAG = "WristDoctor"

    /** 이 걸음 수 이상이면 연다 */
    const val STEP_THRESHOLD = 8000

    /** 손목닥터9988 (서울특별시) 패키지 */
    const val PACKAGE = "kr.go.seoul.healthcare"

    /**
     * 조건이 맞으면 손목닥터를 띄운다.
     *
     * @param recordDate 방금 분석한 기록의 날짜(yyyy-MM-dd)
     * @param steps 그 기록의 걸음 수
     * @return 실제로 띄웠으면 true
     */
    fun launchIfEligible(context: Context, recordDate: String, steps: Int): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (recordDate != today) return false
        if (steps < STEP_THRESHOLD) return false

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(Constants.KEY_WRIST_DOCTOR_LAUNCH_DATE, "") == today) return false

        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: run {
            Log.d(TAG, "손목닥터 앱이 설치되어 있지 않아 건너뜀")
            return false
        }

        return try {
            // ViewModel의 Application 컨텍스트에서 띄우므로 NEW_TASK가 필요하다
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            prefs.edit().putString(Constants.KEY_WRIST_DOCTOR_LAUNCH_DATE, today).apply()
            true
        } catch (e: Exception) {
            // 설치돼 있어도 비활성화 등으로 못 열 수 있다. 정산은 이미 끝났으니 조용히 넘긴다
            Log.w(TAG, "손목닥터 실행 실패", e)
            false
        }
    }
}
