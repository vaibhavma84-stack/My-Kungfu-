package com.gasplanet.grabber

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The download engine: yt-dlp, running on a bundled Python, with ffmpeg
 * alongside it to join separate video and audio streams back together.
 *
 * Everything here blocks and must be called off the main thread.
 */
object Engine {

    private const val TAG = "Engine"

    @Volatile
    private var started = false

    @Volatile
    var initError: String? = null
        private set

    val isReady: Boolean get() = started

    /**
     * Unpacks the Python runtime and ffmpeg on first launch. Takes a few
     * seconds the very first time and is close to instant afterwards.
     */
    @Synchronized
    fun ensureInit(context: Context) {
        if (started) return
        try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            started = true
            initError = null
        } catch (e: Throwable) {
            initError = e.message ?: e.toString()
            Log.e(TAG, "engine init failed", e)
            throw e
        }
    }

    fun version(context: Context): String =
        runCatching { YoutubeDL.getInstance().version(context) ?: "unknown" }
            .getOrDefault("unknown")

    /**
     * How old the engine is, in days.
     *
     * yt-dlp names its releases after the date they were cut -- 2025.11.12 --
     * so the version string is also its age. The copy bundled inside the app
     * is only as fresh as the library release it came from, which is usually
     * months behind by the time anyone installs it.
     *
     * java.time would be the obvious way to do this and is not available on
     * Android 7, which this app still supports.
     */
    fun ageDays(version: String): Long? {
        val match = Regex("""(\d{4})\.(\d{1,2})\.(\d{1,2})""").find(version) ?: return null
        val (year, month, day) = match.destructured
        return runCatching {
            val released = java.util.Calendar.getInstance().apply {
                clear()
                set(year.toInt(), month.toInt() - 1, day.toInt())
            }
            (System.currentTimeMillis() - released.timeInMillis) / 86_400_000L
        }.getOrNull()?.takeIf { it >= 0 }
    }

    /**
     * Pulls a newer yt-dlp. Worth doing regularly: sites change how they serve
     * video constantly, and a months-old engine is the usual reason a link
     * that used to work suddenly does not.
     */
    fun update(context: Context): String {
        val status = YoutubeDL.getInstance()
            .updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
        return when (status) {
            YoutubeDL.UpdateStatus.DONE -> "Updated to ${version(context)}"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already current (${version(context)})"
            else -> "Update finished (${version(context)})"
        }
    }

    // ---------------------------------------------------------------- probe

    /**
     * Asks yt-dlp what is behind a link without downloading anything.
     *
     * The mapped VideoInfo the library offers drops the fields VR detection
     * needs, so the raw JSON is parsed here instead.
     */
    fun probe(url: String, settings: Settings): Probe {
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", "20")
        if (settings.grabWholePlaylist) {
            req.addOption("--flat-playlist")
        } else {
            req.addOption("--no-playlist")
        }
        applyExtraArgs(req, settings)

        val response = YoutubeDL.getInstance().execute(req)
        val text = response.out
        val start = text.indexOf('{')
        if (start < 0) {
            throw IllegalStateException(
                response.err.trim().ifBlank { "Nothing readable came back for that link" }
            )
        }
        return parseProbe(url, JSONObject(text.substring(start)))
    }

    private fun parseProbe(url: String, root: JSONObject): Probe {
        if (root.optString("_type") == "playlist") {
            val entries = root.optJSONArray("entries") ?: JSONArray()
            val items = (0 until entries.length()).mapNotNull { i ->
                val e = entries.optJSONObject(i) ?: return@mapNotNull null
                val entryUrl = e.optStringOrNull("url")
                    ?: e.optStringOrNull("webpage_url")
                    ?: return@mapNotNull null
                PlaylistEntry(entryUrl, e.optStringOrNull("title") ?: entryUrl)
            }
            return Probe(
                url = url,
                title = root.optStringOrNull("title") ?: "Playlist",
                uploader = root.optStringOrNull("uploader"),
                durationSeconds = 0,
                thumbnail = root.optStringOrNull("thumbnail"),
                extractor = root.optStringOrNull("extractor_key"),
                isLive = false,
                width = 0,
                height = 0,
                heights = emptyList(),
                vr = VrProfile.NONE,
                vrConfident = false,
                playlist = items,
            )
        }

        val formats = root.optJSONArray("formats") ?: JSONArray()
        var bestWidth = 0
        var bestHeight = 0
        var bestPixels = 0L
        val heights = sortedSetOf<Int>()
        val notes = StringBuilder()
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            if (f.optString("vcodec", "none") == "none") continue
            val w = f.optInt("width")
            val h = f.optInt("height")
            if (h > 0) heights.add(h)
            val pixels = w.toLong() * h.toLong()
            if (pixels > bestPixels) {
                bestPixels = pixels
                bestWidth = w
                bestHeight = h
            }
            f.optStringOrNull("format_note")?.let { notes.append(' ').append(it) }
        }
        // A single pre-merged stream has its size on the root object instead.
        if (bestPixels == 0L) {
            bestWidth = root.optInt("width")
            bestHeight = root.optInt("height")
            if (bestHeight > 0) heights.add(bestHeight)
        }

        val text = buildString {
            append(root.optString("title")).append(' ')
            append(root.optString("description").take(2000)).append(' ')
            append(joinArray(root.optJSONArray("tags"))).append(' ')
            append(joinArray(root.optJSONArray("categories"))).append(' ')
            append(notes)
        }
        val (profile, confident) = VrDetect.detect(bestWidth, bestHeight, text)

        val duration = root.optDouble("duration", 0.0)
        return Probe(
            url = root.optStringOrNull("webpage_url") ?: url,
            title = root.optStringOrNull("title") ?: "Video",
            uploader = root.optStringOrNull("uploader") ?: root.optStringOrNull("channel"),
            durationSeconds = if (duration.isNaN()) 0 else duration.toInt(),
            thumbnail = root.optStringOrNull("thumbnail"),
            extractor = root.optStringOrNull("extractor_key"),
            isLive = root.optBoolean("is_live", false),
            width = bestWidth,
            height = bestHeight,
            heights = heights.sortedDescending(),
            vr = profile,
            vrConfident = confident,
            playlist = emptyList(),
        )
    }

    private fun joinArray(a: JSONArray?): String {
        if (a == null) return ""
        return (0 until a.length()).joinToString(" ") { a.optString(it) }
    }

    // ------------------------------------------------------------- download

    /**
     * Runs one download to completion and returns the finished file.
     *
     * Files land in the app's own folder under a name based on the video id,
     * which sidesteps every question of what characters a title may contain.
     * The readable name is applied later, when the file is published to the
     * gallery.
     */
    fun download(
        job: Job,
        into: File,
        settings: Settings,
        processId: String,
        onProgress: (Float, Long, String) -> Unit,
    ): File {
        into.mkdirs()
        val req = YoutubeDLRequest(job.url)
        req.addOption("--newline")
        req.addOption("--no-mtime")
        req.addOption("--no-playlist")
        req.addOption("--retries", "10")
        req.addOption("--fragment-retries", "10")
        req.addOption("--socket-timeout", "20")
        // VR files run to many gigabytes; fetching fragments in parallel is
        // the difference between minutes and most of an hour.
        req.addOption("--concurrent-fragments", "4")
        req.addOption("-o", File(into, "%(id)s.%(ext)s").absolutePath)

        if (job.quality.audioOnly) {
            req.addOption("-f", "ba/b")
            req.addOption("-x")
            req.addOption("--audio-format", "m4a")
        } else {
            req.addOption("-f", formatSelector(job, settings))
            req.addOption("--merge-output-format", "mp4")
            req.addOption("-S", sortOrder(settings))
        }

        if (settings.embedMetadata) req.addOption("--embed-metadata")
        if (settings.embedThumbnail) req.addOption("--embed-thumbnail")
        if (settings.writeSubtitles) {
            req.addOption("--write-subs")
            req.addOption("--sub-langs", settings.subtitleLanguages)
            req.addOption("--embed-subs")
        }
        applyExtraArgs(req, settings)

        val response = YoutubeDL.getInstance().execute(req, processId, onProgress)
        if (response.exitCode != 0) {
            throw IllegalStateException(
                response.err.trim().lines().lastOrNull { it.isNotBlank() }
                    ?: "yt-dlp exited with code ${response.exitCode}"
            )
        }
        return newestOutput(into)
            ?: throw IllegalStateException("The download finished but produced no file")
    }

    /**
     * A VR clip is stretched across your whole field of view, so the usual
     * resolution ceiling is lifted for it: 4K wrapped around a sphere looks
     * roughly like 1080p does on a screen.
     */
    private fun formatSelector(job: Job, settings: Settings): String {
        val lift = job.vr.isVr && settings.vrAlwaysBest
        val cap = if (lift) null else job.quality.maxHeight
        return if (cap == null) {
            "bv*+ba/b"
        } else {
            "bv*[height<=$cap]+ba/bv*[height<=$cap]/b[height<=$cap]/bv*+ba/b"
        }
    }

    private fun sortOrder(settings: Settings): String =
        if (settings.preferH264) "res,fps,vcodec:h264,acodec:aac" else "res,fps"

    private fun applyExtraArgs(req: YoutubeDLRequest, settings: Settings) {
        splitArgs(settings.extraArgs).forEach { req.addOption(it) }
    }

    /** Splits a command line, keeping quoted runs together. */
    fun splitArgs(text: String): List<String> {
        val out = mutableListOf<String>()
        val token = StringBuilder()
        var quote = ' '
        for (c in text) {
            when {
                quote != ' ' -> if (c == quote) quote = ' ' else token.append(c)
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> if (token.isNotEmpty()) {
                    out.add(token.toString()); token.clear()
                }
                else -> token.append(c)
            }
        }
        if (token.isNotEmpty()) out.add(token.toString())
        return out
    }

    /**
     * yt-dlp names the finished file itself, and the name depends on which
     * container the merge settled on, so the folder is read back rather than
     * predicted. Part files are still in progress and are skipped.
     */
    private fun newestOutput(dir: File): File? = dir.listFiles()
        ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
        ?.maxByOrNull { it.length() }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }
}
