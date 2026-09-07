package com.mykungfu.mvtagger.core

/**
 * What a video's size is called, in the words people actually use.
 *
 * Judged on the long edge rather than the height, which is the one decision
 * here worth explaining. Height looks like the obvious choice -- "1080p" is
 * 1080 lines, after all -- but films are not shot at 16:9. A 4K film is
 * routinely 3840x1600, and by height that is 1600 lines and would be called
 * 2K, which is wrong and would be wrong for most films in a collection. By the
 * long edge it is 3840, which is what it is.
 *
 * Taking the long edge rather than the width also handles video shot on a
 * phone: 1080x1920 held upright is a 1080p video, not a 2K one.
 *
 * A little under counts. Encodes get cropped by a few pixels and arrive as
 * 3808 or 1912, and calling those a lower grade than they are would be a
 * distinction without a difference.
 */
object Resolution {

    /** How far under a step still counts as that step. */
    private const val TOLERANCE = 0.95

    private val STEPS = listOf(
        7680 to "8K",
        3840 to "4K",
        2560 to "2K",
        1920 to "1080p",
        1280 to "720p",
        854 to "480p",
        640 to "360p",
    )

    /**
     * The name for these dimensions, or null when they are not known.
     *
     * Null rather than a guess: a file whose size cannot be read should say
     * nothing, not claim to be standard definition.
     */
    fun label(width: Int?, height: Int?): String? {
        val longEdge = maxOf(width ?: 0, height ?: 0)
        if (longEdge <= 0) return null
        for ((at, name) in STEPS) {
            if (longEdge >= at * TOLERANCE) return name
        }
        return "SD"
    }

    /** The dimensions as they are usually written, for somewhere with room. */
    fun exact(width: Int?, height: Int?): String? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        return width.toString() + "×" + height
    }
}
