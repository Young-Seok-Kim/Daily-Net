package com.youngs.dailynet.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.youngs.dailynet.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 매일 저녁 "오늘 정산 안 하셨어요" 알림.
 *
 * 매일 기록하는 앱인데 리마인더가 없으면 사용자가 앱을 여는 이유가 본인 의지뿐이라
 * 며칠만 지나도 잊힌다. 서버(FCM) 없이 기기 안에서만 처리한다.
 *
 * 이미 정산을 끝낸 날에는 알리지 않는다. 할 일이 없는데 울리는 알림은 바로 꺼진다.
 */
object DailyReminder {

    private const val CHANNEL_ID = "daily_record_reminder"
    private const val WORK_NAME = "daily_record_reminder_work"
    private const val NOTIFICATION_ID = 1001

    /** 알림을 띄울 시각 (24시간제) */
    private const val REMIND_HOUR = 21
    private const val REMIND_MINUTE = 0

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(Constants.KEY_REMINDER_ENABLED, false)

    /** 켜면 예약하고, 끄면 예약을 지운다. */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(Constants.KEY_REMINDER_ENABLED, enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    /** 정산을 끝낸 날짜를 기록해 둔다. 그날은 알림을 띄우지 않기 위해 쓴다. */
    fun markRecorded(context: Context, date: String) {
        prefs(context).edit().putString(Constants.KEY_LAST_RECORDED_DATE, date).apply()
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilNextRemindTime(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().build())
            .build()

        // UPDATE를 쓰면 이미 예약된 게 있어도 시각만 갱신되고 중복 예약되지 않는다
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** 지금부터 다음 알림 시각까지 남은 밀리초 */
    private fun delayUntilNextRemindTime(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, REMIND_HOUR)
            set(Calendar.MINUTE, REMIND_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 오늘 시각이 이미 지났으면 내일로 넘긴다
        if (!target.after(now)) target.add(Calendar.DAY_OF_MONTH, 1)
        return target.timeInMillis - now.timeInMillis
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    /**
     * 예약된 시각에 깨어나 오늘 정산 여부를 확인하고 알림을 띄운다.
     *
     * Worker에는 DB 대신 SharedPreferences에 남긴 마지막 정산 날짜만 본다.
     * 알림 하나 띄우자고 Room과 Hilt를 백그라운드로 끌고 들어갈 이유가 없다.
     */
    class ReminderWorker(
        private val context: Context,
        params: WorkerParameters
    ) : Worker(context, params) {

        override fun doWork(): Result {
            if (!isEnabled(context)) return Result.success()

            // 오늘 이미 정산했으면 조용히 넘어간다
            val lastRecorded = prefs(context).getString(Constants.KEY_LAST_RECORDED_DATE, "")
            if (lastRecorded == today()) return Result.success()

            notifyIfPermitted()
            return Result.success()
        }

        private fun notifyIfPermitted() {
            // 안드로이드 13부터는 권한이 없으면 알림이 조용히 무시된다.
            // 예외가 나지 않으므로 직접 확인해야 한다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }

            createChannel()

            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(context.getString(R.string.reminder_body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }

        private fun createChannel() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.reminder_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
