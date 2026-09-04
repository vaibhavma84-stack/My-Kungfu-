package com.mykungfu.mvtagger

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import java.nio.ByteBuffer

/**
 * Repackaging a video into MP4 without re-encoding it.
 *
 * This exists because of where the metadata goes. Only the MP4 family has a
 * standard place for the artwork, artist, album and year, so an `.mkv` episode
 * ends up with its cover in a file *beside* the video rather than inside it --
 * which defeats the point, since the whole reason for embedding is that the
 * details survive being copied to an iPad.
 *
 * Remuxing moves the existing audio and video streams into an MP4 container
 * untouched. **Nothing is re-encoded**: not one pixel is decoded or
 * recompressed, so there is no quality loss and it runs at disk speed rather
 * than taking half an hour. It is the same operation as `ffmpeg -c copy`.
 *
 * ### What it cannot do
 *
 * Android's `MediaMuxer` will only write certain codecs into MP4. H.264 and
 * H.265 video with AAC audio -- which is most television and most downloaded
 * music video -- goes straight across. VP9 or AV1 video, or Opus or Vorbis
 * audio, which is what a `.webm` from YouTube usually holds, cannot: MP4 has no
 * slot for them here, and the only way across would be to decode and re-encode,
 * which does cost quality and time. Those files are reported honestly and left
 * as they are.
 *
 * Subtitle tracks are dropped, because MediaMuxer cannot write them into MP4.
 */
object Remux {

    /** Video codecs MediaMuxer will put in an MP4. */
    private val VIDEO_SUPPORTED = setOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaFormat.MIMETYPE_VIDEO_MPEG4,
        MediaFormat.MIMETYPE_VIDEO_H263,
    )

    /** Audio codecs MediaMuxer will put in an MP4. */
    private val AUDIO_SUPPORTED = setOf(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaFormat.MIMETYPE_AUDIO_AMR_NB,
        MediaFormat.MIMETYPE_AUDIO_AMR_WB,
    )

    /** Names a person recognises, for the message when this cannot be done. */
    private val FRIENDLY = mapOf(
        "video/avc" to "H.264",
        "video/hevc" to "H.265",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/av01" to "AV1",
        "video/mp4v-es" to "MPEG-4",
        "audio/mp4a-latm" to "AAC",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/ac3" to "Dolby Digital",
        "audio/eac3" to "Dolby Digital Plus",
        "audio/vnd.dts" to "DTS",
        "audio/flac" to "FLAC",
        "audio/mpeg" to "MP3",
    )

    private fun friendly(mime: String?): String =
        mime?.let { FRIENDLY[it] ?: it } ?: "none"

    data class Verdict(
        val possible: Boolean,
        /** Plain-language explanation, shown to the user when it cannot be done. */
        val reason: String,
        val videoMime: String? = null,
        val audioMime: String? = null,
    )

    /** Whether this file's streams can move into an MP4 as they are. */
    fun inspect(context: Context, uri: Uri): Verdict {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Verdict(false, "Converting needs Android 8 or newer.")
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var video: String? = null
            var audio: String? = null
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && video == null -> video = mime
                    mime.startsWith("audio/") && audio == null -> audio = mime
                }
            }
            when {
                video == null ->
                    Verdict(false, "No video track found.", video, audio)
                video !in VIDEO_SUPPORTED -> Verdict(
                    false,
                    "The video is " + friendly(video) + ", which cannot go into an MP4 " +
                            "without re-encoding it. Re-downloading this one as MP4 is " +
                            "quicker and keeps the quality.",
                    video, audio,
                )
                audio != null && audio !in AUDIO_SUPPORTED -> Verdict(
                    false,
                    "The audio is " + friendly(audio) + ", which cannot go into an MP4 " +
                            "without re-encoding it.",
                    video, audio,
                )
                else -> Verdict(
                    true,
                    friendly(video) + " video and " + friendly(audio) +
                            " audio move across untouched.",
                    video, audio,
                )
            }
        } catch (e: Exception) {
            Verdict(false, "Could not read the file: " + (e.message ?: e.toString()))
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Copies the streams of [source] into an MP4 written to [target].
     *
     * Returns the number of samples written. Throws if anything goes wrong, so
     * the caller can delete a half-written file rather than keep it.
     */
    fun toMp4(context: Context, source: Uri, target: Uri): Long {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "Converting needs Android 8 or newer"
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        val descriptor = context.contentResolver.openFileDescriptor(target, "rw")
            ?: throw java.io.IOException("could not open the new file for writing")

        try {
            extractor.setDataSource(context, source, null)
            muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = HashMap<Int, Int>()
            var bufferSize = MIN_BUFFER

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val keep = mime in VIDEO_SUPPORTED || mime in AUDIO_SUPPORTED
                if (!keep) continue

                extractor.selectTrack(i)
                trackMap[i] = muxer.addTrack(format)
                bufferSize = maxOf(bufferSize, bufferFor(format))

                // Portrait video is stored rotated with a flag; losing it would
                // leave the picture on its side.
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    runCatching { muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION)) }
                }
            }
            if (trackMap.isEmpty()) throw java.io.IOException("nothing in this file can go into an MP4")

            muxer.start()

            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            var samples = 0L

            while (true) {
                val from = extractor.sampleTrackIndex
                if (from < 0) break
                val to = trackMap[from]
                if (to == null) {
                    extractor.advance()
                    continue
                }
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and
                    MediaExtractor.SAMPLE_FLAG_SYNC != 0
                ) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

                muxer.writeSampleData(to, buffer, info)
                samples++
                extractor.advance()
            }

            muxer.stop()
            return samples
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            runCatching { descriptor.close() }
        }
    }

    private const val MIN_BUFFER = 2 * 1024 * 1024

    /**
     * A buffer big enough for the largest sample.
     *
     * The track usually declares this. When it does not, the guess follows the
     * frame size, because a 4K keyframe is a great deal bigger than a 720p one
     * and a short buffer fails the whole conversion partway through.
     */
    private fun bufferFor(format: MediaFormat): Int {
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            val declared = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(0)
            if (declared > 0) return declared + 1024
        }
        val height = runCatching {
            if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT)
            else 0
        }.getOrDefault(0)
        return when {
            height >= 2160 -> 24 * 1024 * 1024
            height >= 1080 -> 12 * 1024 * 1024
            else -> 6 * 1024 * 1024
        }
    }
}
