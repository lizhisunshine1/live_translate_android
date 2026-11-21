package io.github.ztfang.eye.ui.records

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.ztfang.eye.MainActivity
import io.github.ztfang.eye.R
import java.util.Calendar

/**
 * 每日复习提醒：AlarmManager 定时 + 通知。
 *
 * 不用 WorkManager（项目没引入该依赖，且这里只需要「每天固定时间叫一声」，
 * AlarmManager 的重复闹钟足够，体积为零）。
 */
object ReviewReminder {
    const val CHANNEL_ID = "review_reminder"
    const val REQUEST_CODE = 2001
    const val EXTRA_OPEN_RECORDS = "open_records"

    /** 默认提醒时间：每天 20:30（晚饭后，符合「当天再看一眼」的节奏） */
    const val DEFAULT_HOUR = 20
    const val DEFAULT_MINUTE = 30

    fun schedule(
        context: Context,
        hour: Int = DEFAULT_HOUR,
        minute: Int = DEFAULT_MINUTE,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent =
            Intent(context, ReviewReminderReceiver::class.java).apply {
                action = ACTION_REVIEW_REMIND
            }
        val pending =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val next =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        runCatching {
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                next,
                AlarmManager.INTERVAL_DAY,
                pending,
            )
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReviewReminderReceiver::class.java).apply { action = ACTION_REVIEW_REMIND }
        val pending =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        runCatching { alarmManager.cancel(pending) }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.record_review_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.record_review_channel_desc) },
        )
    }

    const val ACTION_REVIEW_REMIND = "io.github.ztfang.eye.ACTION_REVIEW_REMIND"
}

/** 到点弹出复习通知；点击直接打开记录窗口所在页面 */
class ReviewReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ReviewReminder.ACTION_REVIEW_REMIND) return
        ReviewReminder.ensureChannel(context)
        val open =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_RECORDS, true)
            }
        val pending =
            PendingIntent.getActivity(
                context,
                3001,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, ReviewReminder.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.record_review_notify_title))
                .setContentText(context.getString(R.string.record_review_notify_text))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                manager.areNotificationsEnabled()
            ) {
                manager.notify(4001, notification)
            }
        }
    }
}
