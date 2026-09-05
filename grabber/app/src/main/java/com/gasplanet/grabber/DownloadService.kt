package com.gasplanet.grabber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the queue, one download at a time, in the foreground so that Android
 * does not kill it the moment the app is swiped away. A long VR download can
 * easily outlast the user's patience for staring at the screen.
 *
 * One at a time is deliberate: several 8K downloads in parallel just divide
 * the same connection between them while multiplying the ways it can fail.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: CoroutineJob? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var cancelledIds = mutableSetOf<String>()

    private lateinit var settings: Settings

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        Downloads.load(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_JOB_ID)
                if (id != null) cancelJob(id)
            }
            ACTION_CANCEL_ALL -> {
                Downloads.jobs.value.filterNot { it.state.isFinished }.forEach { cancelJob(it.id) }
            }
        }
        // Android 14 wants the notification up almost immediately, well before
        // the first download has anything to report.
        startForegroundSafely(buildNotification("Starting", null, 0f, true))
        ensureWorker()
        return START_STICKY
    }

    private fun cancelJob(id: String) {
        cancelledIds.add(id)
        Engine.cancel(id)
        Downloads.update(id) {
            if (it.state.isFinished) it else it.copy(state = JobState.CANCELLED, line = "Cancelled")
        }
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch { runQueue() }
    }

    private suspend fun runQueue() {
        acquireWakeLock()
        try {
            try {
                Engine.ensureInit(applicationContext)
            } catch (e: Throwable) {
                failAllQueued("The download engine could not start: ${e.message}")
                return
            }

            while (true) {
                val job = Downloads.nextQueued() ?: break
                if (cancelledIds.contains(job.id)) {
                    Downloads.update(job.id) { it.copy(state = JobState.CANCELLED) }
                    continue
                }
                runOne(job)
            }
        } finally {
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun failAllQueued(message: String) {
        Downloads.jobs.value.filter { it.state == JobState.QUEUED }.forEach { j ->
            Downloads.update(j.id) { it.copy(state = JobState.FAILED, error = message) }
        }
        notifyDone("Downloads failed", message)
    }

    private fun runOne(queued: Job) {
        var job = queued
        try {
            // A playlist entry arrives knowing only its link, so it is looked
            // up here -- which is also where its VR layout gets worked out.
            if (!job.resolved) {
                Downloads.update(job.id) { it.copy(state = JobState.RESOLVING) }
                updateNotification(job.title, 0f, true)
                val probe = Engine.probe(job.url, settings)
                job = job.copy(
                    title = probe.title,
                    thumbnail = probe.thumbnail,
                    vr = if (job.vr.isVr) job.vr else probe.vr,
                    resolved = true,
                )
                val resolved = job
                Downloads.update(job.id) {
                    it.copy(
                        title = resolved.title,
                        thumbnail = resolved.thumbnail,
                        vr = resolved.vr,
                        resolved = true,
                    )
                }
            }

            Downloads.update(job.id) { it.copy(state = JobState.DOWNLOADING, error = null) }
            updateNotification(job.title, 0f, false)

            val dir = Downloads.workDir(this, job)
            var lastNotified = 0L
            val file = Engine.download(job, dir, settings, job.id) { percent, eta, line ->
                val fraction = (percent / 100f).coerceIn(0f, 1f)
                Downloads.progress(job.id, fraction, eta, line.trim())
                val now = System.currentTimeMillis()
                if (now - lastNotified > 900) {
                    lastNotified = now
                    updateNotification(job.title, fraction, false)
                }
            }

            if (cancelledIds.contains(job.id)) {
                dir.deleteRecursively()
                return
            }

            Downloads.update(job.id) { it.copy(state = JobState.SAVING, progress = 1f) }
            updateNotification(job.title, 1f, true)

            val saved = MediaExport.save(
                context = this,
                file = file,
                title = job.title,
                vr = if (settings.vrNameHints) job.vr else VrProfile.NONE,
                audio = job.quality.audioOnly,
            )
            dir.deleteRecursively()

            Downloads.update(job.id) {
                it.copy(
                    state = JobState.DONE,
                    progress = 1f,
                    savedTo = saved.displayPath,
                    savedUri = saved.uri,
                    line = "Saved to ${saved.displayPath}",
                )
            }
            notifyDone("Saved: ${job.title}", saved.displayPath)
        } catch (e: Throwable) {
            if (cancelledIds.contains(job.id)) {
                Downloads.update(job.id) { it.copy(state = JobState.CANCELLED) }
                return
            }
            Log.e("DownloadService", "job failed", e)
            val message = friendlyError(e)
            Downloads.update(job.id) {
                it.copy(state = JobState.FAILED, error = message, line = message)
            }
            notifyDone("Could not download", message)
        }
    }

    /**
     * yt-dlp's own wording is the most accurate thing available, but its
     * commonest failures are worth saying plainly.
     */
    private fun friendlyError(e: Throwable): String {
        val all = (e.message ?: e.toString()).lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // An out-of-date engine is the single commonest reason a download that
        // ought to work does not, and it is the one the user can actually fix,
        // so it is checked before anything else and named as an instruction.
        if (all.any { it.contains("is older than", ignoreCase = true) } &&
            all.none { it.startsWith("ERROR:") && !it.contains("older than", true) }
        ) {
            return "The download engine is out of date, which is the usual reason " +
                "this happens. Go to Settings and tap Update engine, then try again."
        }

        // yt-dlp writes warnings and errors to the same stream and the library
        // hands back the lot, so the warnings -- which are almost never the
        // reason it failed -- bury the one line that is.
        val raw = (all.lastOrNull { it.startsWith("ERROR:") }
            ?: all.lastOrNull { !it.startsWith("WARNING:") }
            ?: all.lastOrNull()
            ?: "")
            .removePrefix("ERROR:")
            .trim()
        val lower = raw.lowercase()
        return when {
            lower.contains("drm") || lower.contains("this video is drm") ->
                "That video is DRM-protected and cannot be downloaded."
            lower.contains("sign in") || lower.contains("login") || lower.contains("cookies") ->
                "The site wants you signed in for this one."
            lower.contains("private") -> "That video is private."
            lower.contains("unavailable") -> "The site says that video is unavailable."
            lower.contains("unsupported url") || lower.contains("no suitable extractor") ->
                "No downloader exists for that site yet."
            lower.contains("no space") || lower.contains("enospc") ->
                "The phone ran out of storage."
            lower.contains("unable to download") && lower.contains("timed out") ->
                "The connection timed out."
            raw.length > 300 -> raw.take(300) + "…"
            else -> raw.ifBlank { "The download failed." }
        }
    }

    // -------------------------------------------------------- notifications

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Download progress"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun buildNotification(
        title: String,
        detail: String?,
        progress: Float,
        indeterminate: Boolean,
    ): Notification {
        val remaining = Downloads.pendingCount()
        val cancelAll = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail ?: if (remaining > 1) "$remaining in the queue" else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent())
            .setProgress(100, (progress * 100).toInt(), indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", cancelAll)
            .build()
    }

    private fun updateNotification(title: String, progress: Float, indeterminate: Boolean) {
        val notification = buildNotification(title, null, progress, indeterminate)
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyDone(title: String, detail: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(DONE_ID_BASE + (System.currentTimeMillis() % 1000).toInt(), notification)
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grabber:download").apply {
            setReferenceCounted(false)
            runCatching { acquire(6 * 60 * 60 * 1000L) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 1001
        const val DONE_ID_BASE = 2000
        const val ACTION_CANCEL = "com.gasplanet.grabber.CANCEL"
        const val ACTION_CANCEL_ALL = "com.gasplanet.grabber.CANCEL_ALL"
        const val EXTRA_JOB_ID = "jobId"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, jobId: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_JOB_ID, jobId)
            runCatching { context.startService(intent) }
        }
    }
}
