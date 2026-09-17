package com.mykungfu.mvtagger.core

/**
 * The arithmetic behind stepping through a video a frame at a time.
 *
 * Stepping frames is looking closely at one moment: which is why it comes with
 * zooming, and why a frame worth stopping on is usually a frame worth keeping.
 * The parts of that with an answer that can be checked live here rather than in
 * the player, where nothing can be run.
 */
object FrameShot {

    /** Life size. Below this there is nothing to see that the screen does not already show. */
    const val MIN_ZOOM = 1f

    /**
     * Far enough in to see individual pixels on a 1080p file, and not so far
     * that a small movement of the finger loses the picture entirely.
     */
    const val MAX_ZOOM = 8f

    /** Multiplied rather than added, because a pinch is a ratio. */
    fun zoom(current: Float, by: Float): Float = (current * by).coerceIn(MIN_ZOOM, MAX_ZOOM)

    /**
     * How far the picture may be pushed before its edge comes into view.
     *
     * At life size the answer is nowhere, which is why zooming back out puts
     * the picture straight rather than leaving it hanging off the screen.
     *
     * [span] is the width or height being panned along.
     */
    fun pan(offset: Float, span: Float, zoom: Float): Float {
        val limit = (zoom - 1f) * span / 2f
        if (limit <= 0f || span <= 0f) return 0f
        return offset.coerceIn(-limit, limit)
    }

    /**
     * A position written so it survives being a filename and still reads as a
     * time: `01m02s240`, and `1h04m11s000` once there are hours in it.
     *
     * Milliseconds are in it because the whole point of the mode is that two
     * saved frames a fortieth of a second apart are different pictures, and two
     * files called `01m02s` would be the same name twice.
     */
    fun stamp(positionMs: Long): String {
        val ms = positionMs.coerceAtLeast(0L)
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1000
        val millis = ms % 1000
        return buildString {
            if (hours > 0) append(hours).append('h')
            append(minutes.toString().padStart(2, '0')).append('m')
            append(seconds.toString().padStart(2, '0')).append('s')
            append(millis.toString().padStart(3, '0'))
        }
    }

    /**
     * What a saved frame is called.
     *
     * The title leads, so frames from one film sit together wherever the
     * gallery sorts them by name, and it is sanitised the same way the app
     * sanitises everything else it writes -- a title with a slash in it is a
     * failed write rather than an odd name.
     */
    fun fileName(title: String?, positionMs: Long): String {
        val base = RenameTemplate.sanitise(title.orEmpty()).ifBlank { "Frame" }
        return base + " " + stamp(positionMs) + ".jpg"
    }
}
