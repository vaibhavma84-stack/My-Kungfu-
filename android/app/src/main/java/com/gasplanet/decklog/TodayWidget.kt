package com.gasplanet.decklog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** The day's jobs, as a scrolling list. Rolls over at midnight on its own. */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        // The rows are built by the factory, which only re-reads on notify --
        // without this the widget would still show yesterday's list after the
        // periodic update rolled past midnight.
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.today_list)
        ids.forEach { render(c, mgr, it) }
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        when (intent.action) {
            ACTION_REFRESH -> refreshAll(c)
            ACTION_TICK -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
                val done = intent.getBooleanExtra(EXTRA_DONE, false)
                AgendaStore.tick(c, jobId, !done)
                refreshAll(c)
                MonthWidget.refreshAll(c)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.gasplanet.decklog.TODAY_REFRESH"
        const val ACTION_TICK = "com.gasplanet.decklog.TODAY_TICK"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_DONE = "job_done"

        fun refreshAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val ids = mgr.getAppWidgetIds(ComponentName(c, TodayWidget::class.java))
            if (ids.isEmpty()) return
            // the list contents live in the factory, so it has to be told too
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.today_list)
            ids.forEach { render(c, mgr, it) }
        }

        private fun render(c: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(c.packageName, R.layout.widget_today)
            val cal = Calendar.getInstance()
            v.setTextViewText(
                R.id.today_title,
                SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(cal.time)
            )
            val ship = AgendaStore.shipName(c)
            v.setTextViewText(R.id.today_ship, if (ship.isBlank()) "DECK LOG" else ship)

            val svc = Intent(c, TodayWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                // the data uri makes each widget's adapter distinct, without
                // which Android hands them all the same cached factory
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            v.setRemoteAdapter(R.id.today_list, svc)
            v.setEmptyView(R.id.today_list, R.id.today_empty)

            val open = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.today_header, open)
            // Tapping a row ticks it off, the way the checklist on the home
            // screen is expected to work -- so the template is the tick
            // broadcast and each row fills in which job it is.
            val tickTemplate = PendingIntent.getBroadcast(
                c, id + 1000,
                Intent(c, TodayWidget::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            v.setPendingIntentTemplate(R.id.today_list, tickTemplate)

            val refresh = PendingIntent.getBroadcast(
                c, id,
                Intent(c, TodayWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.today_refresh, refresh)

            mgr.updateAppWidget(id, v)
        }
    }
}
