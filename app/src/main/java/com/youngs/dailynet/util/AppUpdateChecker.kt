package com.youngs.dailynet.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.net.toUri
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 업데이트 안내가 필요한 상태.
 *
 * @param forced true면 닫을 수 없는 강제 업데이트. false면 "나중에"로 넘길 수 있는 권장 업데이트.
 */
data class AppUpdateInfo(
    val forced: Boolean,
    val message: String
)

/**
 * 앱을 켤 때 "지금 이 버전을 계속 써도 되는지"를 판단한다.
 *
 * 비교 대상은 두 가지다.
 * - [BuildConfig.VERSION_CODE] : APK에 박혀서 배포된 값이라 사용자 기기에서 바뀌지 않는다.
 * - Remote Config 의 min/recommend_version_code : Firebase 콘솔에서 직접 정하는 값.
 *
 * 서버(Cloud Functions)가 자기 버전을 알려주는 구조가 아니라,
 * "이 버전 미만은 막겠다"는 판단을 콘솔에서 사람이 내리고 앱이 그걸 따르는 구조다.
 * 덕분에 서버 응답 형식을 바꿔야 할 때 앱 배포 없이 구버전 사용자를 차단할 수 있다.
 */
@Singleton
class AppUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteConfig: FirebaseRemoteConfig
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private var initialized = false

    /**
     * 업데이트가 필요하면 [AppUpdateInfo]를, 아니면 null을 돌려준다.
     *
     * 어떤 이유로든 실패하면 null을 돌려준다. 업데이트 검사 때문에 앱을 못 켜는 상황이
     * 검사를 못 하는 상황보다 훨씬 나쁘기 때문에, 막지 않는 쪽으로 실패한다.
     */
    suspend fun check(): AppUpdateInfo? {
        return try {
            ensureInitialized()

            // 네트워크가 느려도 앱 진입을 막지 않는다.
            // 시간 안에 못 받아도 직전 실행 때 받아둔 값이 남아 있으므로 그대로 판단을 이어간다.
            try {
                withTimeout(Constants.REMOTE_CONFIG_TIMEOUT_MS) {
                    remoteConfig.fetchAndActivate().await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Remote Config fetch 실패 - 캐시된 값으로 판단한다", e)
            }

            val current = BuildConfig.VERSION_CODE.toLong()
            val minVersion = remoteConfig.getLong(Constants.RC_MIN_VERSION_CODE)
            val recommendVersion = remoteConfig.getLong(Constants.RC_RECOMMEND_VERSION_CODE)
            Log.d(TAG, "업데이트 검사: current=$current, min=$minVersion, recommend=$recommendVersion")

            when {
                current < minVersion -> AppUpdateInfo(forced = true, message = resolveMessage(true))

                // 권장 안내는 앱을 켤 때마다 뜨면 성가시므로 하루 한 번으로 제한한다
                current < recommendVersion && !isNoticeShownToday() -> {
                    markNoticeShownToday()
                    AppUpdateInfo(forced = false, message = resolveMessage(false))
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "업데이트 검사 실패 - 그대로 진행한다", e)
            null
        }
    }

    /** 플레이스토어의 앱 상세 화면을 연다. 스토어 앱이 없으면 브라우저로 넘긴다. */
    fun openStore(activity: Activity) {
        val packageName = context.packageName
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
            )
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://play.google.com/store/apps/details?id=$packageName".toUri()
                )
            )
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return

        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                // 디버그 빌드는 콘솔에서 값을 바꾸자마자 확인할 수 있어야 테스트가 된다
                .setMinimumFetchIntervalInSeconds(
                    if (BuildConfig.DEBUG) 0 else Constants.REMOTE_CONFIG_FETCH_INTERVAL_SEC
                )
                .build()
        ).await()

        remoteConfig.setDefaultsAsync(R.xml.remote_config_defaults).await()
        initialized = true
    }

    /**
     * 다이얼로그 본문을 정한다.
     * 강제와 권장은 상황이 전혀 다르므로 콘솔 키를 따로 두고, 비어 있으면 각각의 기본 문구를 쓴다.
     */
    private fun resolveMessage(forced: Boolean): String {
        val key = if (forced) Constants.RC_FORCE_UPDATE_MESSAGE else Constants.RC_RECOMMEND_UPDATE_MESSAGE
        val fromConsole = remoteConfig.getString(key)
        if (fromConsole.isNotBlank()) return fromConsole

        return if (forced) {
            "지금 버전은 더 이상 지원되지 않습니다.\n최신 버전으로 업데이트해 주세요."
        } else {
            "새로운 버전이 나왔습니다.\n업데이트하면 더 나은 기능을 쓸 수 있습니다."
        }
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    /**
     * 권장 안내를 오늘 이미 보여줬는지.
     *
     * 디버그 빌드에서는 항상 false다. 하루 한 번 제한이 걸리면 테스트할 때마다
     * 앱 데이터를 지워야 해서, 개발 중에는 매 실행마다 뜨게 둔다.
     */
    private fun isNoticeShownToday(): Boolean {
        if (BuildConfig.DEBUG) return false
        return prefs.getString(Constants.KEY_UPDATE_NOTICE_DATE, "") == today()
    }

    private fun markNoticeShownToday() {
        prefs.edit().putString(Constants.KEY_UPDATE_NOTICE_DATE, today()).apply()
    }

    companion object {
        private const val TAG = "AppUpdateChecker"
    }
}
