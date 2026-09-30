package com.gasplanet.decklog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Fires once a day, whether or not the app or a widget is on screen, so the
 * reminder still reaches a phone that has not had the app opened. An alarm
 * does not survive a reboot or an app update, so both are caught here and
 * rescheduled the same way MainActivity schedules it on first launch.
 */
class DailyCheckReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> schedule(c)
            else -> NotificationHelper.checkAndNotify(c)
        }
    }

    companion object {
        const val ACTION_CHECK = "com.gasplanet.decklog.DAILY_CHECK"

        fun schedule(c: Context) {
            val mgr = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = PendingIntent.getBroadcast(
                c, 0,
                Intent(c, DailyCheckReceiver::class.java).setAction(ACTION_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, NotificationHelper.HOUR)
                set(Calendar.MINUTE, NotificationHelper.MINUTE)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
            }
            // Inexact: a reminder that lands within an hour of 05:30 is exactly
            // as useful, and it lets Android batch the wakeup with other apps'
            // instead of forcing the radio up on the dot -- kinder to a phone
            // that is also trying to save battery at sea.
            mgr.setInexactRepeating(
                AlarmManager.RTC_WAKEUP, next.timeInMillis,
                AlarmManager.INTERVAL_DAY, pending
            )
        }
    }
}
