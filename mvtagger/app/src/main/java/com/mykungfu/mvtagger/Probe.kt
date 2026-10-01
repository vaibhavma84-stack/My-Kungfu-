package com.mykungfu.mvtagger

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.mykungfu.mvtagger.core.ApplePlayback
import com.mykungfu.mvtagger.core.Resolution

/**
 * What a file actually contains, read from the file.
 *
 * Everything here could be guessed from the filename and every guess would be
 * wrong often enough to matter. "4K" in a name is a claim by whoever uploaded
 * it, and an upscaled 1080p carries it just as readily; a name says nothing at
 * all about which codec is inside, which is the thing that decides whether an
 * iPad can play it.
 *
 * One pass over the tracks answers both, so the scan pays for it once.
 */
object Probe {

    class Streams(
        val width: Int?,
        val height: Int?,
        val video: String?,
        val audio: String?,
        /** H.264 in its ten-bit profile, which no Apple device decodes. */
        val tenBit: Boolean,
    ) {
        val quality: String? get() = Resolution.label(width, height)
        val longEdge: Int get() = maxOf(width ?: 0, height ?: 0)

        /** The short warning for an iPad, or null when there is nothing to say. */
        val appleWarning: String?
            get() = ApplePlayback.check(video, audio, tenBit, longEdge).warning

        val appleReason: String
            get() = ApplePlayback.check(video, audio, tenBit, longEdge).reason
    }

    /**
     * Best effort. A container Android cannot open contributes nothing rather
     * than failing the scan it is part of.
     */
    fun of(context: Context, uri: Uri): Streams? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var width: Int? = null
            var height: Int? = null
            var video: String? = null
            var audio: String? = null
            var tenBit = false

            for (i in 0 until extractor.trackCount) {
                val format = runCatching { extractor.getTrackFormat(i) }.getOrNull() ?: continue
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && video == null -> {
                        video = mime
                        width = format.intOrNull(MediaFormat.KEY_WIDTH)
                        height = format.intOrNull(MediaFormat.KEY_HEIGHT)
                        // Not every extractor reports a profile. When it does
                        // not, this reads as eight-bit -- under-warning rather
                        // than crying wolf, which is the safer way to be wrong.
                        if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                            val profile = format.intOrNull(MediaFormat.KEY_PROFILE)
                            tenBit = profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
                        }
                    }
                    mime.startsWith("audio/") && audio == null -> audio = mime
                }
            }

            if (video == null && audio == null) null
            else Streams(width, height, video, audio, tenBit)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** [MediaFormat.getInteger] throws when the key is absent rather than saying so. */
    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}
