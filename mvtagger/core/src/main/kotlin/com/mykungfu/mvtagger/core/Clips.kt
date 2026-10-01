package com.mykungfu.mvtagger.core

/**
 * Taking a piece out of a video, and what to call it.
 *
 * The rule the whole feature rests on: **the original is not touched**. A clip
 * is a new file written beside the library, in a folder of its own, and the
 * video it came from is exactly as it was afterwards. That is the same promise
 * the tagger makes everywhere else, and it is the one that makes cutting worth
 * offering at all -- a trim that edits in place is a trim nobody dares use.
 *
 * The name says where the piece came from, because in a month "PERFECT" and
 * "PERFECT (2)" say nothing and `PERFECT 01m02s to 01m40s` says everything.
 */
object Clips {

    /** Where clips go, under the output folder. */
    const val FOLDER = "Clips"

    /** Shorter than this is a mis-tap rather than a clip. */
    const val MIN_MS = 500L

    /**
     * Why these two times will not do, or null when they will.
     *
     * [durationMs] may be zero when the length is not known yet, in which case
     * the end is not checked against it -- refusing a clip because the player
     * has not worked out how long the file is would be refusing for the wrong
     * reason.
     */
    fun refuse(fromMs: Long, toMs: Long, durationMs: Long = 0L): String? = when {
        fromMs < 0L -> "The start is before the beginning of the file."
        toMs <= fromMs -> "The end has to come after the start."
        toMs - fromMs < MIN_MS -> "That piece is too short to be worth cutting."
        durationMs > 0L && fromMs >= durationMs -> "The start is past the end of the file."
        else -> null
    }

    /**
     * `PERFECT 01m02s to 01m40s.mp4`.
     *
     * Seconds rather than the milliseconds a saved frame carries: a cut can
     * only begin on a keyframe, so a name claiming millisecond precision would
     * be claiming something the file does not have.
     */
    fun fileName(title: String?, fromMs: Long, toMs: Long): String {
        val base = RenameTemplate.sanitise(title.orEmpty()).ifBlank { "Clip" }
        return base + " " + stamp(fromMs) + " to " + stamp(toMs) + ".mp4"
    }

    /** `01m02s`, and `1h04m11s` once there are hours in it. */
    fun stamp(ms: Long): String {
        val at = ms.coerceAtLeast(0L)
        val hours = at / 3_600_000
        val minutes = (at % 3_600_000) / 60_000
        val seconds = (at % 60_000) / 1000
        return buildString {
            if (hours > 0) append(hours).append('h')
            append(minutes.toString().padStart(2, '0')).append('m')
            append(seconds.toString().padStart(2, '0')).append('s')
        }
    }
}
