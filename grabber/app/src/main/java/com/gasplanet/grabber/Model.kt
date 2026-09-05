package com.gasplanet.grabber

import org.json.JSONArray
import org.json.JSONObject

/**
 * How a VR video is laid out inside an ordinary rectangular file.
 *
 * A 360 or 180 video is not a special format -- it is a normal mp4 whose
 * frames happen to be an unwrapped sphere, sometimes with the left and right
 * eye packed side by side. Nothing in the file reliably says so once it has
 * been through a downloader, so VR players fall back on reading the layout
 * from the file name. These are the suffixes DeoVR, Skybox and Pigasus all
 * recognise, which is why they are attached to saved files.
 */
enum class VrProfile(val label: String, val hint: String) {
    NONE("Flat (not VR)", ""),
    MONO360("360° mono", "_360"),
    TB360("360° 3D, over-under", "_360_TB"),
    SBS360("360° 3D, side-by-side", "_360_LR"),
    SBS180("180° 3D, side-by-side", "_180x180_3dh"),
    TB180("180° 3D, over-under", "_180x180_3dv"),
    SBS_FLAT("Flat 3D, side-by-side", "_LR");

    val isVr: Boolean get() = this != NONE
}

/**
 * maxHeight null means "no ceiling, take the best there is" -- which is what
 * VR needs, since an 8K 360 video only looks like 2K once it is wrapped
 * around your head.
 */
enum class Quality(val label: String, val maxHeight: Int?, val audioOnly: Boolean = false) {
    BEST("Best available", null),
    P4320("8K · 4320p", 4320),
    P2160("4K · 2160p", 2160),
    P1440("1440p", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    AUDIO("Audio only · m4a", null, audioOnly = true);
}

enum class JobState {
    QUEUED, RESOLVING, DOWNLOADING, SAVING, DONE, FAILED, CANCELLED;

    val isFinished: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
    val isRunning: Boolean get() = this == RESOLVING || this == DOWNLOADING || this == SAVING
}

data class Job(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val quality: Quality = Quality.BEST,
    val vr: VrProfile = VrProfile.NONE,
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val etaSeconds: Long = -1L,
    val line: String = "",
    val savedTo: String? = null,
    val savedUri: String? = null,
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Playlist entries are queued before anything is known about them;
     *  the service probes those on the way past. */
    val resolved: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", title)
        put("thumbnail", thumbnail ?: JSONObject.NULL)
        put("quality", quality.name)
        put("vr", vr.name)
        // A job caught mid-flight by the app being killed is resumed as queued
        // rather than restored as a download that is no longer running.
        put("state", if (state.isRunning) JobState.QUEUED.name else state.name)
        put("progress", progress.toDouble())
        put("savedTo", savedTo ?: JSONObject.NULL)
        put("savedUri", savedUri ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("addedAt", addedAt)
        put("resolved", resolved)
    }

    companion object {
        fun fromJson(o: JSONObject): Job = Job(
            id = o.optString("id"),
            url = o.optString("url"),
            title = o.optString("title"),
            thumbnail = o.optStringOrNull("thumbnail"),
            quality = enumOr(o.optString("quality"), Quality.BEST),
            vr = enumOr(o.optString("vr"), VrProfile.NONE),
            state = enumOr(o.optString("state"), JobState.QUEUED),
            progress = o.optDouble("progress", 0.0).toFloat(),
            savedTo = o.optStringOrNull("savedTo"),
            savedUri = o.optStringOrNull("savedUri"),
            error = o.optStringOrNull("error"),
            addedAt = o.optLong("addedAt", System.currentTimeMillis()),
            resolved = o.optBoolean("resolved", true),
        )
    }
}

private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }

/** One entry of a playlist, before it has been resolved individually. */
data class PlaylistEntry(val url: String, val title: String)

/** What a probe of a link found, before anything is downloaded. */
data class Probe(
    val url: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Int,
    val thumbnail: String?,
    val extractor: String?,
    val isLive: Boolean,
    val width: Int,
    val height: Int,
    /** Distinct video heights on offer, tallest first. */
    val heights: List<Int>,
    val vr: VrProfile,
    /** False when VR was guessed from weak evidence and wants a human look. */
    val vrConfident: Boolean,
    val playlist: List<PlaylistEntry>,
) {
    val isPlaylist: Boolean get() = playlist.isNotEmpty()

    val durationLabel: String
        get() {
            if (durationSeconds <= 0) return ""
            val h = durationSeconds / 3600
            val m = (durationSeconds % 3600) / 60
            val s = durationSeconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
}

/**
 * Works out whether a video is VR, and in what layout.
 *
 * There is no dependable flag to read: YouTube strips spherical metadata from
 * the streams it serves, and most VR sites never set it. What survives is the
 * shape of the frame. An unwrapped sphere is always 2:1, and packing two eyes
 * side by side doubles that to 4:1, while stacking them makes it 1:1. Those
 * ratios are distinctive enough to lead on, with words from the title used to
 * break the ties -- a 2:1 frame is 360 mono or 180 side-by-side depending
 * only on which the uploader says it is.
 *
 * Anything uncertain is reported as such so the app can ask rather than
 * silently mislabel a file, and every guess can be overridden by hand.
 */
object VrDetect {

    private val WORD_360 = Regex("""\b360\b|\bequirect\w*|\bspherical\b""")
    private val WORD_180 = Regex("""\b180\b|\bvr180\b|\bfisheye\b""")
    private val WORD_VR = Regex("""\bvr\b|\bstereoscopic\b|\bimmersive\b|\bquest\b|\binsta360\b""")
    // These deliberately exclude the short forms that read as VR jargon to a
    // programmer and as ordinary text to everyone else. "lr", "tb" and "ou"
    // match inside perfectly normal descriptions -- "ou" is a common French
    // word -- and "stereo" almost always refers to the audio.
    private val WORD_SBS = Regex("""\bsbs\b|side.by.side|\b3dh\b""")
    private val WORD_TB = Regex("""over.under|top.bottom|\b3dv\b""")
    private val WORD_3D = Regex("""\b3d\b|\bstereoscopic\b""")

    private fun near(value: Double, target: Double): Boolean =
        kotlin.math.abs(value - target) < target * 0.04

    /** @return the layout, and whether the evidence was strong. */
    fun detect(width: Int, height: Int, text: String): Pair<VrProfile, Boolean> {
        if (width <= 0 || height <= 0) return VrProfile.NONE to false
        val t = text.lowercase()
        val has360 = WORD_360.containsMatchIn(t)
        val has180 = WORD_180.containsMatchIn(t)
        val hasVr = WORD_VR.containsMatchIn(t)
        val sbs = WORD_SBS.containsMatchIn(t)
        val tb = WORD_TB.containsMatchIn(t)
        val threeD = WORD_3D.containsMatchIn(t)
        val ratio = width.toDouble() / height.toDouble()
        val anyWord = has360 || has180 || hasVr

        // Frame shape alone is never enough to call something VR. A flat video
        // wrongly labelled is worse than a VR one missed: the player wraps it
        // around your head and it is unwatchable, whereas a missed clip is one
        // tap away in the override. So there has to be corroborating evidence
        // -- either the uploader says so, or the frame is far too large for
        // anything but VR.
        val hugeFrame = width >= 3840
        if (!anyWord && !hugeFrame) return VrProfile.NONE to false

        return when {
            // Two eyes side by side, each one a full sphere.
            near(ratio, 4.0) && anyWord -> VrProfile.SBS360 to (has360 || hasVr)

            // One unwrapped sphere, or two 180 eyes packed side by side --
            // both land on 2:1 and only the wording tells them apart.
            near(ratio, 2.0) && height >= 960 && (anyWord || hugeFrame) -> when {
                has180 -> VrProfile.SBS180 to true
                has360 -> VrProfile.MONO360 to true
                // 4K and up at a clean 2:1 is overwhelmingly 360 footage;
                // ordinary cinema is 2.39:1 and lands nowhere near.
                hasVr || width >= 3840 -> VrProfile.MONO360 to (width >= 3840)
                else -> VrProfile.NONE to false
            }

            // Two spheres stacked vertically.
            near(ratio, 1.0) && height >= 1440 && (has360 || hasVr) ->
                VrProfile.TB360 to has360

            // Two 180 eyes stacked, which is tall rather than wide.
            near(ratio, 0.5) && has180 -> VrProfile.TB180 to true

            // No shape evidence at all, but the uploader is shouting about VR.
            anyWord && threeD && sbs -> VrProfile.SBS180 to false
            anyWord && threeD && tb -> VrProfile.TB360 to false
            has360 && hasVr -> VrProfile.MONO360 to false

            else -> VrProfile.NONE to false
        }
    }

    /** Best guess at the layout from a file name alone, for imported files. */
    fun fromName(name: String): VrProfile {
        val t = name.lowercase()
        return when {
            t.contains("_360_tb") || t.contains("360_ou") -> VrProfile.TB360
            t.contains("_360_lr") -> VrProfile.SBS360
            t.contains("3dh") || t.contains("180x180") -> VrProfile.SBS180
            t.contains("3dv") -> VrProfile.TB180
            WORD_360.containsMatchIn(t) -> VrProfile.MONO360
            else -> VrProfile.NONE
        }
    }
}

/** A site tile on the Sites screen. */
data class Site(val name: String, val url: String, val category: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("url", url); put("category", category)
    }

    companion object {
        fun fromJson(o: JSONObject) =
            Site(o.optString("name"), o.optString("url"), o.optString("category", "Custom"))

        fun listToJson(sites: List<Site>): String {
            val a = JSONArray()
            sites.forEach { a.put(it.toJson()) }
            return a.toString()
        }

        fun listFromJson(text: String): List<Site> = runCatching {
            val a = JSONArray(text)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
