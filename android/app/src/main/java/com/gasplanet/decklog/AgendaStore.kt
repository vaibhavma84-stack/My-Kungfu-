package com.gasplanet.decklog

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * What the home-screen widgets read.
 *
 * A widget cannot reach the page's localStorage, so the page hands the whole
 * agenda across the JavaScript bridge whenever anything changes and it is kept
 * here as plain text. The widgets then render from this, which means they stay
 * correct — and roll over at midnight — whether or not the app is running.
 *
 * Only days that have something on them are stored. A missing key means an
 * empty day, never "unknown".
 */
object AgendaStore {

    private const val PREFS = "decklog_agenda"
    private const val KEY_JSON = "agenda_json"
    private const val KEY_MONTH_OFFSET = "month_offset_"
    private const val KEY_TICKS = "pending_ticks"

    data class Job(
        val id: String,
        val text: String,
        val priority: String,
        val done: Boolean,
        val repeat: Boolean
    )
    data class Day(val jobs: List<Job>, val birthdays: List<String>)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(c: Context, json: String) {
        prefs(c).edit().putString(KEY_JSON, json).apply()
    }

    private fun root(c: Context): JSONObject? =
        prefs(c).getString(KEY_JSON, null)?.let {
            try { JSONObject(it) } catch (e: Exception) { null }
        }

    fun shipName(c: Context): String = root(c)?.optString("ship", "") ?: ""

    /** Never null: a day with nothing on it is an empty Day, not an absent one. */
    fun day(c: Context, iso: String): Day {
        val days = root(c)?.optJSONObject("days") ?: return Day(emptyList(), emptyList())
        val d = days.optJSONObject(iso) ?: return Day(emptyList(), emptyList())
        val overrides = ticks(c)
        val jobs = ArrayList<Job>()
        d.optJSONArray("jobs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val jobId = o.optString("i", "")
                // a tick not yet seen by the page still shows here
                val done = if (overrides.has(jobId)) overrides.optBoolean(jobId)
                           else o.optBoolean("d", false)
                jobs.add(
                    Job(
                        jobId,
                        o.optString("t", ""),
                        o.optString("p", "normal"),
                        done,
                        o.optBoolean("r", false)
                    )
                )
            }
        }
        val bd = ArrayList<String>()
        d.optJSONArray("bdays")?.let { arr ->
            for (i in 0 until arr.length()) bd.add(arr.optString(i, ""))
        }
        return Day(jobs, bd.filter { it.isNotBlank() })
    }

    fun hasData(c: Context): Boolean = root(c) != null

    // --- ticks made on the home screen --------------------------------------
    // The page owns the job list; a widget cannot write to it. So a tick is
    // queued here and the page applies it next time it runs. The widget also
    // shows it straight away, which is what makes the checkbox feel answered
    // rather than ignored -- see overrides in day().
    private fun ticks(c: Context): JSONObject =
        try { JSONObject(prefs(c).getString(KEY_TICKS, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun tick(c: Context, jobId: String, done: Boolean) {
        if (jobId.isBlank()) return
        val t = ticks(c)
        t.put(jobId, done)
        prefs(c).edit().putString(KEY_TICKS, t.toString()).apply()
    }

    fun pendingTicks(c: Context): String = ticks(c).toString()

    fun clearTicks(c: Context) {
        prefs(c).edit().remove(KEY_TICKS).apply()
    }

    // --- which month a given month-widget instance is showing -----------------
    // Paging is per widget, so two copies of the widget can sit on different
    // months. The offset is in whole months from the current one.
    fun monthOffset(c: Context, widgetId: Int): Int =
        prefs(c).getInt(KEY_MONTH_OFFSET + widgetId, 0)

    fun setMonthOffset(c: Context, widgetId: Int, offset: Int) {
        prefs(c).edit().putInt(KEY_MONTH_OFFSET + widgetId, offset).apply()
    }

    fun clearWidget(c: Context, widgetId: Int) {
        prefs(c).edit().remove(KEY_MONTH_OFFSET + widgetId).apply()
    }

    // --- dates, in the device's own timezone ---------------------------------
    fun todayIso(): String = isoOf(Calendar.getInstance())

    fun isoOf(cal: Calendar): String = String.format(
        "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
    )
}
