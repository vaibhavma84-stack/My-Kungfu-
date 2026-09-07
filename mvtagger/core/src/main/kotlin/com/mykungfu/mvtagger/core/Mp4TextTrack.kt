package com.mykungfu.mvtagger.core

import java.io.ByteArrayOutputStream

/**
 * Builds a `tx3g` subtitle track to go inside an MP4.
 *
 * Subtitles normally travel as a `.srt` file next to the video, and that works
 * everywhere -- but it is a second file, and the whole point of this app is
 * that the details stay attached when the video is copied somewhere else.
 * `tx3g` is the timed-text format MP4 defines for exactly this, and it is what
 * Apple's own players read, so a file tagged here arrives on an iPad with its
 * subtitles already in it.
 *
 * Android's `MediaMuxer` cannot write subtitle tracks, which is why this is
 * built by hand on top of the writer already used for the tags.
 *
 * ### The shape of a text track
 *
 * A sample is a 16-bit length followed by that many bytes of UTF-8. The track
 * has to be continuous, so the gaps between subtitles are filled with empty
 * samples -- a length of zero, which clears the screen. Timing lives in the
 * sample table, not in the text, which is why the cues have to be put in order
 * and stripped of overlaps first.
 */
internal object Mp4TextTrack {

    /** Milliseconds, so cue times go in without conversion. */
    private const val MEDIA_TIMESCALE = 1000

    class Built(
        val trak: ByteArray,
        /** The sample data, to be written as its own `mdat`. */
        val samples: ByteArray,
        /** Index in [trak] of the 8-byte chunk offset, patched once the layout is known. */
        val chunkOffsetAt: Int,
        val durationMs: Long,
    )

    /**
     * @param movieTimescale from `mvhd`, because a track header states its
     *   duration in the movie's units rather than its own.
     * @param width and [height] of the video, so the subtitle box is the size
     *   of the picture rather than a stripe in the corner.
     */
    fun build(
        cues: List<Cue>,
        trackId: Int,
        movieTimescale: Int,
        language: String?,
        width: Int,
        height: Int,
    ): Built? {
        val tidy = Subtitles.tidy(cues)
        if (tidy.isEmpty()) return null

        val samples = ByteArrayOutputStream()
        val durations = ArrayList<Int>()
        val sizes = ArrayList<Int>()
        var cursor = 0L

        for (cue in tidy) {
            if (cue.startMs > cursor) {
                // Nothing on screen between one subtitle and the next.
                samples.write(byteArrayOf(0, 0))
                durations += (cue.startMs - cursor).toInt()
                sizes += 2
            }
            val text = cue.text.toByteArray(Charsets.UTF_8)
            val length = minOf(text.size, 0xFFFF)
            val sample = ByteArray(2 + length)
            Mp4.putBe16(sample, 0, length)
            System.arraycopy(text, 0, sample, 2, length)
            samples.write(sample)
            durations += cue.durationMs.toInt().coerceAtLeast(1)
            sizes += sample.size
            cursor = cue.endMs
        }

        val mediaDuration = cursor
        val trackDuration = mediaDuration * movieTimescale / MEDIA_TIMESCALE

        val stbl = buildStbl(durations, sizes, width, height)
        val minf = box(
            "minf",
            box("nmhd", ByteArray(4)),
            dinf(),
            stbl,
        )
        val mdia = box(
            "mdia",
            mdhd(mediaDuration, language),
            hdlr(),
            minf,
        )
        val trak = box("trak", tkhd(trackId, trackDuration, width, height), mdia)

        // Found by walking the finished box tree rather than by counting bytes
        // through the builders above: a miscount here would put the offset in
        // the wrong place and corrupt the file silently.
        val located = locateChunkOffset(trak)
            ?: throw IllegalStateException("built a text track with no co64")

        return Built(
            trak = trak,
            samples = samples.toByteArray(),
            chunkOffsetAt = located,
            durationMs = mediaDuration,
        )
    }

    /** Walks the finished trak to find the one `co64` value. */
    private fun locateChunkOffset(trak: ByteArray): Int? {
        val mdia = Mp4.child(trak, 8, trak.size, "mdia") ?: return null
        val minf = Mp4.child(trak, mdia.payloadStart.toInt(), mdia.end.toInt(), "minf") ?: return null
        val stbl = Mp4.child(trak, minf.payloadStart.toInt(), minf.end.toInt(), "stbl") ?: return null
        val co64 = Mp4.child(trak, stbl.payloadStart.toInt(), stbl.end.toInt(), "co64") ?: return null
        // version+flags(4), entry_count(4), then the single 8-byte offset.
        return co64.payloadStart.toInt() + 8
    }

    private fun buildStbl(
        durations: List<Int>,
        sizes: List<Int>,
        width: Int,
        height: Int,
    ): ByteArray {
        val stsd = box(
            "stsd",
            ByteArray(4) + beInt(1),
            tx3g(width, height),
        )

        // stts, run-length encoded: consecutive samples of the same length
        // share one entry, which for subtitles collapses long stretches of gap.
        val runs = ArrayList<Pair<Int, Int>>()
        for (d in durations) {
            val last = runs.lastOrNull()
            if (last != null && last.second == d) runs[runs.size - 1] = last.first + 1 to d
            else runs += 1 to d
        }
        val sttsBody = ByteArrayOutputStream()
        sttsBody.write(ByteArray(4))
        sttsBody.write(beInt(runs.size))
        for ((count, delta) in runs) {
            sttsBody.write(beInt(count))
            sttsBody.write(beInt(delta))
        }
        val stts = box("stts", sttsBody.toByteArray())

        // One chunk holding every sample.
        val stscBody = ByteArrayOutputStream()
        stscBody.write(ByteArray(4))
        stscBody.write(beInt(1))
        stscBody.write(beInt(1))            // first_chunk
        stscBody.write(beInt(sizes.size))   // samples_per_chunk
        stscBody.write(beInt(1))            // sample_description_index
        val stsc = box("stsc", stscBody.toByteArray())

        val stszBody = ByteArrayOutputStream()
        stszBody.write(ByteArray(4))
        stszBody.write(beInt(0))            // varying sizes, listed below
        stszBody.write(beInt(sizes.size))
        for (size in sizes) stszBody.write(beInt(size))
        val stsz = box("stsz", stszBody.toByteArray())

        // 64-bit offsets: the subtitle samples go after the media, and a long
        // film is comfortably past the 4 GB a 32-bit stco can address.
        val co64Body = ByteArrayOutputStream()
        co64Body.write(ByteArray(4))
        co64Body.write(beInt(1))
        co64Body.write(ByteArray(8))        // patched once the layout is known
        val co64 = box("co64", co64Body.toByteArray())

        return box("stbl", stsd, stts, stsc, stsz, co64)
    }

    /** The sample description: how the text should be drawn. */
    private fun tx3g(width: Int, height: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(ByteArray(6))                 // reserved
        body.write(beShort(1))                   // data_reference_index
        body.write(beInt(0))                     // displayFlags
        body.write(1)                            // horizontal justification: centre
        body.write(0xFF)                         // vertical justification: bottom
        body.write(byteArrayOf(0, 0, 0, 0))      // background: transparent
        // Text box covering the picture, so centred text lands in the middle.
        body.write(beShort(0))
        body.write(beShort(0))
        body.write(beShort(height))
        body.write(beShort(width))
        // Style: white, 18pt, plain.
        body.write(beShort(0))                   // startChar
        body.write(beShort(0))                   // endChar
        body.write(beShort(1))                   // font id
        body.write(0)                            // face style
        body.write(18)                           // size
        body.write(byteArrayOf(-1, -1, -1, -1))  // opaque white
        // The font table the style refers to.
        val fontName = "Serif".toByteArray(Charsets.US_ASCII)
        val ftab = ByteArrayOutputStream()
        ftab.write(beShort(1))                   // one entry
        ftab.write(beShort(1))                   // font id
        ftab.write(fontName.size)
        ftab.write(fontName)
        body.write(box("ftab", ftab.toByteArray()))
        return box("tx3g", body.toByteArray())
    }

    private fun tkhd(trackId: Int, duration: Long, width: Int, height: Int): ByteArray {
        val body = ByteArrayOutputStream()
        // Enabled, in the movie, in the preview.
        body.write(byteArrayOf(0, 0, 0, 7))
        body.write(beInt(0))                     // creation
        body.write(beInt(0))                     // modification
        body.write(beInt(trackId))
        body.write(beInt(0))                     // reserved
        body.write(beInt(duration.coerceAtMost(0xFFFFFFFFL).toInt()))
        body.write(ByteArray(8))                 // reserved
        body.write(beShort(0))                   // layer
        body.write(beShort(0))                   // alternate group
        body.write(beShort(0))                   // volume: silent
        body.write(beShort(0))                   // reserved
        body.write(UNITY_MATRIX)
        body.write(beInt(width shl 16))          // 16.16 fixed point
        body.write(beInt(height shl 16))
        return box("tkhd", body.toByteArray())
    }

    private fun mdhd(durationMs: Long, language: String?): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(ByteArray(4))                 // version 0, no flags
        body.write(beInt(0))                     // creation
        body.write(beInt(0))                     // modification
        body.write(beInt(MEDIA_TIMESCALE))
        body.write(beInt(durationMs.coerceAtMost(0xFFFFFFFFL).toInt()))
        body.write(beShort(packLanguage(language)))
        body.write(beShort(0))                   // quality
        return box("mdhd", body.toByteArray())
    }

    private fun hdlr(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(ByteArray(4))                 // version + flags
        body.write(beInt(0))                     // pre_defined
        body.write(Mp4.typeBytes("sbtl"))        // this is a subtitle track
        body.write(ByteArray(12))                // reserved
        body.write("Subtitle".toByteArray(Charsets.US_ASCII))
        body.write(0)                            // null terminator
        return box("hdlr", body.toByteArray())
    }

    /** Says the media is in this same file rather than referenced elsewhere. */
    private fun dinf(): ByteArray {
        val url = box("url ", byteArrayOf(0, 0, 0, 1))
        val dref = box("dref", ByteArray(4) + beInt(1), url)
        return box("dinf", dref)
    }

    /**
     * ISO 639-2 packed into fifteen bits, five per letter, as `mdhd` wants it.
     * Unknown or missing becomes "und", which is what players expect to see.
     */
    private fun packLanguage(code: String?): Int {
        val three = Languages.iso639_2(code)
        var packed = 0
        for (ch in three) {
            packed = (packed shl 5) or ((ch.code - 0x60) and 0x1F)
        }
        return packed and 0x7FFF
    }

    private val UNITY_MATRIX: ByteArray = run {
        val out = ByteArray(36)
        Mp4.putBe32(out, 0, 0x00010000)   // a
        Mp4.putBe32(out, 16, 0x00010000)  // d
        Mp4.putBe32(out, 32, 0x40000000)  // w
        out
    }

    private fun beInt(value: Int): ByteArray {
        val out = ByteArray(4)
        Mp4.putBe32(out, 0, value)
        return out
    }

    private fun beShort(value: Int): ByteArray {
        val out = ByteArray(2)
        Mp4.putBe16(out, 0, value)
        return out
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.sumOf { it.size }
        val out = ByteArray(8 + payload)
        Mp4.putBe32(out, 0, out.size)
        System.arraycopy(Mp4.typeBytes(type), 0, out, 4, 4)
        var at = 8
        for (part in parts) {
            System.arraycopy(part, 0, out, at, part.size)
            at += part.size
        }
        return out
    }
}
