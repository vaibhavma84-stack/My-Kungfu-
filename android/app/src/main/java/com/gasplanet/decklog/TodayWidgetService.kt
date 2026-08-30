package com.gasplanet.decklog

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodayFactory(applicationContext)
}

/**
 * One row per birthday, then one per job. The list is rebuilt from the store on
 * every notifyAppWidgetViewDataChanged, and "today" is read at that moment, so
 * the widget follows the calendar rather than whatever day it was built on.
 */
private class TodayFactory(private val c: Context) : RemoteViewsService.RemoteViewsFactory {

    private sealed class Row {
        data class Birthday(val text: String) : Row()
        data class Job(val job: AgendaStore.Job) : Row()
    }

    private var rows: List<Row> = emptyList()

    override fun onCreate() {}
    override fun onDataSetChanged() {
        val day = AgendaStore.day(c, AgendaStore.todayIso())
        rows = day.birthdays.map { Row.Birthday(it) } + day.jobs.map { Row.Job(it) }
    }
    override fun onDestroy() { rows = emptyList() }
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_today_item)
        when (val row = rows.getOrNull(position)) {
            is Row.Birthday -> {
                v.setTextViewText(R.id.item_mark, "🎂")
                v.setTextViewText(R.id.item_text, row.text)
                v.setTextViewText(R.id.item_note, "birthday")
                v.setInt(R.id.item_stripe, "setBackgroundColor", COLOR_BIRTHDAY)
                v.setTextColor(R.id.item_text, TEXT_LIVE)
            }
            is Row.Job -> {
                val j = row.job
                v.setTextViewText(R.id.item_mark, if (j.done) "☑" else "☐")
                v.setTextViewText(R.id.item_text, if (j.done) struck(j.text) else j.text)
                v.setTextViewText(R.id.item_note, if (j.repeat) "recurring" else j.priority)
                v.setInt(R.id.item_stripe, "setBackgroundColor", colorFor(j))
                v.setTextColor(R.id.item_text, if (j.done) TEXT_DONE else TEXT_LIVE)
                // a projection is not a real job yet, so it cannot be ticked
                if (!j.repeat && j.id.isNotBlank()) {
                    v.setOnClickFillInIntent(
                        R.id.item_root,
                        Intent()
                            .putExtra(TodayWidget.EXTRA_JOB_ID, j.id)
                            .putExtra(TodayWidget.EXTRA_DONE, j.done)
                    )
                }
            }
            else -> {
                v.setTextViewText(R.id.item_text, "")
                v.setTextViewText(R.id.item_mark, "")
                v.setTextViewText(R.id.item_note, "")
            }
        }
        return v
    }

    private fun colorFor(j: AgendaStore.Job): Int = when {
        j.done -> COLOR_DONE
        j.priority == "urgent" -> COLOR_URGENT
        j.priority == "important" -> COLOR_IMPORTANT
        else -> COLOR_NORMAL
    }

    companion object {
        private const val COLOR_URGENT = 0xFFC7452C.toInt()
        private const val COLOR_IMPORTANT = 0xFFD6963B.toInt()
        private const val COLOR_NORMAL = 0xFF3A5A78.toInt()
        private const val COLOR_DONE = 0xFF6E8B74.toInt()
        private const val COLOR_BIRTHDAY = 0xFF9B6BB5.toInt()
        private const val TEXT_LIVE = 0xFFF0F4F7.toInt()
        private const val TEXT_DONE = 0xFF77848F.toInt()
        /** Struck through the way a job is crossed off a paper list. */
        private fun struck(text: String): CharSequence =
            SpannableString(text).apply {
                setSpan(StrikethroughSpan(), 0, length, android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
    }
}
