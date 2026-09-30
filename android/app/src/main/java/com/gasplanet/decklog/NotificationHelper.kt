package com.gasplanet.decklog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/**
 * The one-a-day nudge: what is still outstanding for today, read straight
 * from the same agenda the home-screen widgets already use. No new data path
 * -- if the widget is showing it, this can see it too, and both go stale the
 * same way when the app has not been opened in a while.
 */
object NotificationHelper {
    private const val CHANNEL_ID = "daily_jobs"
    private const val NOTIFICATION_ID = 1001
    private const val PREFS = "decklog_notify"
    private const val KEY_LAST_NOTIFIED = "last_notified_date"

    // 05:30 local time -- before the day's work starts, so the whole list is
    // in hand going in rather than caught up on partway through. Shared with
    // DailyCheckReceiver's alarm.
    const val HOUR = 5
    const val MINUTE = 30

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Today's jobs", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "One reminder a day of what is outstanding -- the same list the home-screen widget shows."
        }
        mgr.createNotificationChannel(channel)
    }

    /** Once a day only -- an inexact alarm can land more than once close to
        the boundary, and nobody wants two copies of the same reminder. */
    fun checkAndNotify(c: Context) {
        val today = AgendaStore.todayIso()
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_NOTIFIED, "") == today) return

        val outstanding = AgendaStore.day(c, today).jobs.filter { !it.done }
        if (outstanding.isEmpty()) return

        val overdue = outstanding.count { it.note == "overdue" }
        val title = if (overdue > 0)
            "${outstanding.size} jobs today, $overdue overdue"
        else
            "${outstanding.size} job${if (outstanding.size == 1) "" else "s"} due today"
        val body = outstanding.take(3).joinToString(", ") { it.text } +
            if (outstanding.size > 3) ", …" else ""

        ensureChannel(c)
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(c).notify(NOTIFICATION_ID, notification)
            prefs.edit().putString(KEY_LAST_NOTIFIED, today).apply()
        } catch (e: SecurityException) {
            // Permission not granted -- the phone stays silent, nothing else to do.
        }
    }

    /**
     * Runs whenever the page publishes a fresh agenda, so a phone that was off
     * at 05:30 still gets today's reminder once it is switched back on and the
     * app is opened -- rather than waiting for tomorrow's alarm. Gated on the
     * time so publishing at 3am does not trigger "today"'s reminder early.
     */
    fun catchUpIfDue(c: Context) {
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (nowMinutes < HOUR * 60 + MINUTE) return
        checkAndNotify(c)
    }
}
