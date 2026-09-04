package com.gasplanet.grabber

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The download queue and the history, which are the same list: a job stays in
 * place once it finishes so the file it produced can still be opened from it.
 *
 * Held in one place rather than in a view model because the foreground service
 * writes to it while the interface reads from it.
 */
object Downloads {

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private var store: File? = null

    @Synchronized
    fun load(context: Context) {
        if (store != null) return
        val f = File(context.filesDir, "jobs.json")
        store = f
        if (!f.exists()) return
        runCatching {
            val a = JSONArray(f.readText())
            _jobs.value = (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { Job.fromJson(it) }
            }
        }
    }

    private fun persist() {
        val f = store ?: return
        runCatching {
            val a = JSONArray()
            _jobs.value.forEach { a.put(it.toJson()) }
            f.writeText(a.toString())
        }
    }

    @Synchronized
    fun add(job: Job): Job {
        _jobs.value = _jobs.value + job
        persist()
        return job
    }

    @Synchronized
    fun addAll(newJobs: List<Job>) {
        if (newJobs.isEmpty()) return
        _jobs.value = _jobs.value + newJobs
        persist()
    }

    @Synchronized
    fun update(id: String, transform: (Job) -> Job) {
        var changed = false
        _jobs.value = _jobs.value.map {
            if (it.id == id) { changed = true; transform(it) } else it
        }
        if (changed) persist()
    }

    @Synchronized
    fun remove(id: String) {
        _jobs.value = _jobs.value.filterNot { it.id == id }
        persist()
    }

    @Synchronized
    fun clearFinished() {
        _jobs.value = _jobs.value.filterNot { it.state.isFinished }
        persist()
    }

    /** Progress updates arrive many times a second; they are not worth a
     *  disk write each, so they bypass persistence. */
    @Synchronized
    fun progress(id: String, fraction: Float, eta: Long, line: String) {
        _jobs.value = _jobs.value.map {
            if (it.id == id) it.copy(progress = fraction, etaSeconds = eta, line = line) else it
        }
    }

    fun nextQueued(): Job? = _jobs.value.firstOrNull { it.state == JobState.QUEUED }

    fun running(): Job? = _jobs.value.firstOrNull { it.state.isRunning }

    fun pendingCount(): Int = _jobs.value.count { !it.state.isFinished }

    fun newId(): String = UUID.randomUUID().toString()

    /** Where a job's partly-downloaded files live while it runs. */
    fun workDir(context: Context, job: Job): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads/${job.id}")
}
