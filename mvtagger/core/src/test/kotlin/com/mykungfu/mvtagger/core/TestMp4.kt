package com.mykungfu.mvtagger.core

import java.io.File

/**
 * Builds small but structurally real MP4 files for the tests.
 *
 * Not playable -- the sample tables are empty and `stsd` describes nothing --
 * but it has everything the tagger navigates: a `moov` with a `trak > mdia >
 * minf > stbl > stco`, chunk offsets that genuinely point at bytes inside
 * `mdat`, and the ability to put `moov` before or after the media.
 *
 * That last part is what makes the tests worth having. Each chunk is filled
 * with a recognisable byte, so after a rewrite the test can follow the new
 * chunk offsets and check they still land on the same chunk. If the offset
 * fixups were wrong, playback in a real player would break, and here that
 * shows up as a failed assertion instead.
 */
object TestMp4 {

    const val CHUNK_SIZE = 16

    class Built(
        val bytes: ByteArray,
        /** The byte each chunk is filled with, in order. */
        val chunkMarkers: List<Byte>,
    )

    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.sumOf { it.size }
        val out = ByteArray(8 + payload)
        Mp4.putBe32(out, 0, out.size)
        System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, out, 4, 4)
        var at = 8
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    private fun filler(size: Int): ByteArray = ByteArray(size) { 0 }

    /**
     * @param moovFirst whether `moov` comes before `mdat`, as a fast-start file
     *   has it. When false the file is the far more common "moov at the end"
     *   shape that a download produces.
     * @param freeBox adds a `free` box, which the writer is expected to drop.
     * @param tagged gives the file a `udta` already, so re-tagging is covered.
     */
    fun build(
        chunkCount: Int = 4,
        moovFirst: Boolean = true,
        freeBox: Boolean = false,
        tagged: Boolean = false,
    ): Built {
        val markers = (0 until chunkCount).map { (0x41 + it).toByte() }
        val mediaPayload = ByteArray(chunkCount * CHUNK_SIZE)
        for (i in 0 until chunkCount) {
            java.util.Arrays.fill(
                mediaPayload, i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE, markers[i]
            )
        }
        val mdat = box("mdat", mediaPayload)

        val ftyp = box(
            "ftyp",
            "isom".toByteArray(Charsets.ISO_8859_1),
            byteArrayOf(0, 0, 2, 0),
            "isomiso2avc1mp41".toByteArray(Charsets.ISO_8859_1),
        )

        // stco with placeholder offsets; the real values need the final layout.
        val stcoPayload = ByteArray(8 + chunkCount * 4)
        Mp4.putBe32(stcoPayload, 4, chunkCount)
        val stco = box("stco", stcoPayload)

        val stbl = box(
            "stbl",
            box("stsd", ByteArray(8)),
            box("stts", ByteArray(8)),
            box("stsc", ByteArray(8)),
            box("stsz", ByteArray(12)),
            stco,
        )
        // A real tkhd, because the subtitle track copies the picture size out
        // of it and a field of zeroes would silently produce a zero-sized
        // subtitle box.
        val tkhd = ByteArray(84)
        Mp4.putBe32(tkhd, 12, 1)          // track_id
        Mp4.putBe32(tkhd, 20, 10_000)     // duration
        Mp4.putBe32(tkhd, 76, 1920 shl 16)
        Mp4.putBe32(tkhd, 80, 1080 shl 16)

        // A video handler, so videoSize() finds this track.
        val hdlr = ByteArray(25)
        System.arraycopy("vide".toByteArray(Charsets.ISO_8859_1), 0, hdlr, 8, 4)

        val trak = box(
            "trak",
            box("tkhd", tkhd),
            box("mdia", box("mdhd", filler(24)), box("hdlr", hdlr), box("minf", stbl)),
        )
        val udta = if (tagged) {
            box("udta", box("meta", ByteArray(4), box("hdlr", filler(25)),
                box("ilst", box("©nam", box("data", ByteArray(8) + "Old title".toByteArray())))))
        } else ByteArray(0)

        // A real mvhd: the timescale converts the subtitle duration into movie
        // units, and next_track_id is the number a new track is given.
        val mvhd = ByteArray(100)
        Mp4.putBe32(mvhd, 12, 1000)       // timescale
        Mp4.putBe32(mvhd, 16, 10_000)     // duration
        Mp4.putBe32(mvhd, 96, 2)          // next_track_id

        val moov = box("moov", box("mvhd", mvhd), trak, udta)

        // Where the stco table sits inside moov, so the offsets can be filled in
        // once the layout is known.
        val stcoTableInMoov = indexOfStcoTable(moov)

        val free = if (freeBox) box("free", filler(32)) else ByteArray(0)

        val order = if (moovFirst) listOf(ftyp, moov, free, mdat)
        else listOf(ftyp, free, mdat, moov)

        var mdatStart = 0L
        for (b in order) {
            if (b === mdat) break
            mdatStart += b.size
        }
        val mediaStart = mdatStart + 8

        for (i in 0 until chunkCount) {
            Mp4.putBe32(moov, stcoTableInMoov + i * 4, (mediaStart + i * CHUNK_SIZE).toInt())
        }

        val out = java.io.ByteArrayOutputStream()
        for (b in order) out.write(b)
        return Built(out.toByteArray(), markers)
    }

    /** Index in [moov] of the first `stco` entry. */
    private fun indexOfStcoTable(moov: ByteArray): Int {
        val trak = Mp4.child(moov, 8, moov.size, "trak")!!
        val mdia = Mp4.child(moov, trak.payloadStart.toInt(), trak.end.toInt(), "mdia")!!
        val minf = Mp4.child(moov, mdia.payloadStart.toInt(), mdia.end.toInt(), "minf")!!
        val stbl = Mp4.child(moov, minf.payloadStart.toInt(), minf.end.toInt(), "stbl")!!
        val stco = Mp4.child(moov, stbl.payloadStart.toInt(), stbl.end.toInt(), "stco")!!
        return stco.payloadStart.toInt() + 8
    }

    /** An in-memory [Mp4.ByteSource], so tests need no temporary files. */
    class ArraySource(private val data: ByteArray) : Mp4.ByteSource {
        override val length: Long get() = data.size.toLong()
        override fun readAt(position: Long, dest: ByteArray, offset: Int, count: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(count.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), dest, offset, n)
            return n
        }

        override fun close() {}
    }

    fun source(bytes: ByteArray) = ArraySource(bytes)

    /** The trak carrying a given handler, e.g. "vide" or "sbtl". */
    fun trakWithHandler(bytes: ByteArray, handler: String): Mp4.BoxRef? {
        val src = ArraySource(bytes)
        val moovRef = Mp4.topLevelBoxes(src).first { it.type == "moov" }
        val moov = src.readFully(moovRef.start, moovRef.size.toInt())
        for (trak in Mp4.children(moov, 8, moov.size).filter { it.type == "trak" }) {
            val mdia = Mp4.child(moov, trak.payloadStart.toInt(), trak.end.toInt(), "mdia")
                ?: continue
            val h = Mp4.child(moov, mdia.payloadStart.toInt(), mdia.end.toInt(), "hdlr") ?: continue
            val type = Mp4.latin1(moov, h.payloadStart.toInt() + 8, 4)
            if (type == handler) return trak
        }
        return null
    }

    /** The whole moov of a finished file, for poking at its boxes. */
    fun moovOf(bytes: ByteArray): ByteArray {
        val src = ArraySource(bytes)
        val moovRef = Mp4.topLevelBoxes(src).first { it.type == "moov" }
        return src.readFully(moovRef.start, moovRef.size.toInt())
    }

    fun writeWithSubtitles(
        input: ByteArray,
        tags: VideoTags,
        track: SubtitleTrack,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        source(input).use { Mp4Metadata.write(it, tags, out, track) }
        return out.toByteArray()
    }

    /**
     * Reads the chunk offsets out of a finished file and returns the first byte
     * found at each -- which should still be that chunk's marker.
     */
    fun markersAtChunkOffsets(bytes: ByteArray): List<Byte> {
        val src = ArraySource(bytes)
        val moovRef = Mp4.topLevelBoxes(src).first { it.type == "moov" }
        val moov = src.readFully(moovRef.start, moovRef.size.toInt())
        val trak = Mp4.child(moov, 8, moov.size, "trak")!!
        val mdia = Mp4.child(moov, trak.payloadStart.toInt(), trak.end.toInt(), "mdia")!!
        val minf = Mp4.child(moov, mdia.payloadStart.toInt(), mdia.end.toInt(), "minf")!!
        val stbl = Mp4.child(moov, minf.payloadStart.toInt(), minf.end.toInt(), "stbl")!!
        val stco = Mp4.child(moov, stbl.payloadStart.toInt(), stbl.end.toInt(), "stco")!!
        val table = stco.payloadStart.toInt() + 8
        val count = Mp4.be32(moov, stco.payloadStart.toInt() + 4)
        return (0 until count).map { i ->
            val at = Mp4.be32(moov, table + i * 4).toLong() and 0xFFFFFFFFL
            bytes[at.toInt()]
        }
    }

    /** Every byte of every chunk, to prove the media itself was copied intact. */
    fun chunkContents(bytes: ByteArray): List<ByteArray> {
        val src = ArraySource(bytes)
        val moovRef = Mp4.topLevelBoxes(src).first { it.type == "moov" }
        val moov = src.readFully(moovRef.start, moovRef.size.toInt())
        val trak = Mp4.child(moov, 8, moov.size, "trak")!!
        val mdia = Mp4.child(moov, trak.payloadStart.toInt(), trak.end.toInt(), "mdia")!!
        val minf = Mp4.child(moov, mdia.payloadStart.toInt(), mdia.end.toInt(), "minf")!!
        val stbl = Mp4.child(moov, minf.payloadStart.toInt(), minf.end.toInt(), "stbl")!!
        val stco = Mp4.child(moov, stbl.payloadStart.toInt(), stbl.end.toInt(), "stco")!!
        val table = stco.payloadStart.toInt() + 8
        val count = Mp4.be32(moov, stco.payloadStart.toInt() + 4)
        return (0 until count).map { i ->
            val at = (Mp4.be32(moov, table + i * 4).toLong() and 0xFFFFFFFFL).toInt()
            bytes.copyOfRange(at, at + CHUNK_SIZE)
        }
    }

    fun writeToBytes(input: ByteArray, tags: VideoTags): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        source(input).use { Mp4Metadata.write(it, tags, out) }
        return out.toByteArray()
    }

    fun readTags(bytes: ByteArray): VideoTags = source(bytes).use { Mp4Metadata.read(it) }

    /** A tiny but real JPEG, for artwork round-trips. */
    fun jpegBytes(size: Int = 64): ByteArray {
        val b = ByteArray(size) { (it % 251).toByte() }
        b[0] = 0xFF.toByte()
        b[1] = 0xD8.toByte()
        b[2] = 0xFF.toByte()
        b[3] = 0xE0.toByte()
        b[size - 2] = 0xFF.toByte()
        b[size - 1] = 0xD9.toByte()
        return b
    }
}
