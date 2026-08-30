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

/**
 * The month, as a grid, with what is due written into the days.
 *
 * RemoteViews cannot inflate an arbitrary layout, so the grid is assembled the
 * one way it allows: a row layout added per week, and a cell layout added per
 * day. Each cell carries the day number, up to three job titles and a cake if
 * anyone aboard has a birthday.
 */
class MonthWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(c, mgr, it) }
    }

    override fun onDeleted(c: Context, ids: IntArray) {
        ids.forEach { AgendaStore.clearWidget(c, it) }
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_PREV, ACTION_NEXT, ACTION_TODAY -> {
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val now = AgendaStore.monthOffset(c, id)
                val next = when (intent.action) {
                    ACTION_PREV -> now - 1
                    ACTION_NEXT -> now + 1
                    else -> 0
                }
                // The page publishes one month back and two forward; paging
                // beyond that would show empty months that are not empty.
                AgendaStore.setMonthOffset(c, id, next.coerceIn(-1, 2))
                render(c, AppWidgetManager.getInstance(c), id)
            }
            ACTION_REFRESH -> refreshAll(c)
        }
    }

    companion object {
        const val ACTION_PREV = "com.gasplanet.decklog.MONTH_PREV"
        const val ACTION_NEXT = "com.gasplanet.decklog.MONTH_NEXT"
        const val ACTION_TODAY = "com.gasplanet.decklog.MONTH_TODAY"
        const val ACTION_REFRESH = "com.gasplanet.decklog.MONTH_REFRESH"

        private const val MAX_CHIPS = 3

        fun refreshAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            mgr.getAppWidgetIds(ComponentName(c, MonthWidget::class.java))
                .forEach { render(c, mgr, it) }
        }

        // Request codes must differ per widget AND per action, or the buttons
        // collapse onto whichever PendingIntent was created first. Hashing the
        // action is not good enough -- these are explicit and cannot collide.
        private fun codeFor(action: String): Int = when (action) {
            ACTION_PREV -> 1
            ACTION_NEXT -> 2
            ACTION_TODAY -> 3
            else -> 4
        }

        private fun pi(c: Context, action: String, id: Int): PendingIntent =
            PendingIntent.getBroadcast(
                c,
                (id * 10) + codeFor(action),
                Intent(c, MonthWidget::class.java).setAction(action)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun render(c: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(c.packageName, R.layout.widget_month)

            val offset = AgendaStore.monthOffset(c, id)
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, offset)
            }
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH)

            v.setTextViewText(
                R.id.month_title,
                SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
            )
            val ship = AgendaStore.shipName(c)
            v.setTextViewText(R.id.month_ship, if (ship.isBlank()) "DECK LOG" else ship)

            v.setOnClickPendingIntent(R.id.month_prev, pi(c, ACTION_PREV, id))
            v.setOnClickPendingIntent(R.id.month_next, pi(c, ACTION_NEXT, id))
            v.setOnClickPendingIntent(R.id.month_title, pi(c, ACTION_TODAY, id))
            v.setOnClickPendingIntent(
                R.id.month_open,
                PendingIntent.getActivity(
                    c, 0, Intent(c, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // Monday-first, to match the app's own month view
            val firstDow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val today = AgendaStore.todayIso()

            v.removeAllViews(R.id.month_grid)
            var day = 1
            var cell = 0
            while (day <= daysInMonth) {
                val row = RemoteViews(c.packageName, R.layout.widget_month_row)
                for (i in 0 until 7) {
                    if (cell < firstDow || day > daysInMonth) {
                        row.addView(R.id.row_cells, RemoteViews(c.packageName, R.layout.widget_month_blank))
                        cell++
                        continue
                    }
                    val iso = String.format("%04d-%02d-%02d", year, month + 1, day)
                    row.addView(R.id.row_cells, cellFor(c, iso, day, iso == today))
                    day++
                    cell++
                }
                v.addView(R.id.month_grid, row)
            }

            if (!AgendaStore.hasData(c)) {
                v.setViewVisibility(R.id.month_hint, android.view.View.VISIBLE)
            } else {
                v.setViewVisibility(R.id.month_hint, android.view.View.GONE)
            }

            mgr.updateAppWidget(id, v)
        }

        private fun cellFor(c: Context, iso: String, dayNum: Int, isToday: Boolean): RemoteViews {
            val cv = RemoteViews(c.packageName, R.layout.widget_month_cell)
            val d = AgendaStore.day(c, iso)
            cv.setTextViewText(R.id.cell_num, dayNum.toString())
            cv.setTextColor(R.id.cell_num, if (isToday) COLOR_TODAY else COLOR_DAY)
            cv.setViewVisibility(
                R.id.cell_today, if (isToday) android.view.View.VISIBLE else android.view.View.GONE
            )
            cv.setViewVisibility(
                R.id.cell_cake, if (d.birthdays.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            )

            cv.removeAllViews(R.id.cell_chips)
            var shown = 0
            d.birthdays.forEach { name ->
                if (shown >= MAX_CHIPS) return@forEach
                cv.addView(R.id.cell_chips, chip(c, name, COLOR_BIRTHDAY))
                shown++
            }
            d.jobs.forEach { j ->
                if (shown >= MAX_CHIPS) return@forEach
                cv.addView(R.id.cell_chips, chip(c, j.text, colorFor(j)))
                shown++
            }
            val extra = d.birthdays.size + d.jobs.size - shown
            if (extra > 0) {
                cv.setTextViewText(R.id.cell_more, "+$extra")
                cv.setViewVisibility(R.id.cell_more, android.view.View.VISIBLE)
            } else {
                cv.setViewVisibility(R.id.cell_more, android.view.View.GONE)
            }
            return cv
        }

        private fun chip(c: Context, text: String, colour: Int): RemoteViews {
            val r = RemoteViews(c.packageName, R.layout.widget_month_chip)
            r.setTextViewText(R.id.chip_text, text)
            r.setInt(R.id.chip_stripe, "setBackgroundColor", colour)
            return r
        }

        private fun colorFor(j: AgendaStore.Job): Int = when {
            j.done -> COLOR_DONE
            j.priority == "urgent" -> COLOR_URGENT
            j.priority == "important" -> COLOR_IMPORTANT
            else -> COLOR_NORMAL
        }

        private const val COLOR_URGENT = 0xFFC7452C.toInt()
        private const val COLOR_IMPORTANT = 0xFFD6963B.toInt()
        private const val COLOR_NORMAL = 0xFF3A5A78.toInt()
        private const val COLOR_DONE = 0xFF6E8B74.toInt()
        private const val COLOR_BIRTHDAY = 0xFF9B6BB5.toInt()
        private const val COLOR_TODAY = 0xFFFFFFFF.toInt()
        private const val COLOR_DAY = 0xFFB8C4CE.toInt()
    }
}
