package com.mykungfu.mvtagger.core

import java.io.File
import java.io.RandomAccessFile

/**
 * ISO base media file format primitives -- the box (atom) layout shared by
 * `.mp4`, `.m4v` and `.mov`.
 *
 * A box is `size(4) type(4)` and then its payload. `size == 1` means the real
 * size is a 64-bit value that follows the type; `size == 0` means the box runs
 * to the end of the file (only ever seen on the last one, usually `mdat`).
 */
object Mp4 {

    /** Boxes whose payload is nothing but more boxes. */
    val CONTAINERS = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "edts", "udta",
        "dinf", "mvex", "moof", "traf", "mfra", "skip", "ilst"
    )

    /** A box located inside some buffer or file. */
    data class BoxRef(
        val type: String,
        /** Offset of the box header, relative to whatever range it was read from. */
        val start: Long,
        /** 8, or 16 when the box carries a 64-bit largesize. */
        val headerSize: Int,
        /** Header plus payload. */
        val size: Long,
    ) {
        val end: Long get() = start + size
        val payloadStart: Long get() = start + headerSize
        val payloadSize: Long get() = size - headerSize
    }

    /** Somewhere bytes can be read from at an arbitrary offset. */
    interface ByteSource : AutoCloseable {
        val length: Long
        fun readAt(position: Long, dest: ByteArray, offset: Int, count: Int): Int

        fun readFully(position: Long, count: Int): ByteArray {
            val out = ByteArray(count)
            var done = 0
            while (done < count) {
                val n = readAt(position + done, out, done, count - done)
                if (n <= 0) throw java.io.EOFException(
                    "wanted $count bytes at $position, got $done"
                )
                done += n
            }
            return out
        }
    }

    class FileSource(private val file: File) : ByteSource {
        private val raf = RandomAccessFile(file, "r")
        override val length: Long get() = raf.length()
        override fun readAt(position: Long, dest: ByteArray, offset: Int, count: Int): Int {
            raf.seek(position)
            return raf.read(dest, offset, count)
        }

        override fun close() = raf.close()
    }

    /**
     * The top-level boxes of a file, in the order they physically appear.
     *
     * Stops rather than throws on a garbled tail: a partly-downloaded video
     * should still list what it does have, so the library can show it.
     */
    fun topLevelBoxes(src: Mp4.ByteSource): List<BoxRef> {
        val boxes = ArrayList<BoxRef>()
        var pos = 0L
        val end = src.length
        while (pos + 8 <= end) {
            val header = try {
                src.readFully(pos, 8)
            } catch (e: Exception) {
                break
            }
            val type = latin1(header, 4, 4)
            if (!isPlausibleType(type)) break
            var size = be32(header, 0).toLong() and 0xFFFFFFFFL
            var headerSize = 8
            if (size == 1L) {
                if (pos + 16 > end) break
                size = be64(src.readFully(pos + 8, 8), 0)
                headerSize = 16
            } else if (size == 0L) {
                // Runs to the end of the file.
                size = end - pos
            }
            if (size < headerSize || pos + size > end) {
                // Truncated final box: keep it, clamped, so the file still reads.
                size = end - pos
                if (size < headerSize) break
            }
            boxes += BoxRef(type, pos, headerSize, size)
            pos += size
        }
        return boxes
    }

    /**
     * The direct children of a container box already held in memory.
     *
     * [from] is the offset of the first child and [until] the end of the
     * parent's payload, both indices into [buf].
     */
    fun children(buf: ByteArray, from: Int, until: Int): List<BoxRef> {
        val out = ArrayList<BoxRef>()
        var pos = from
        while (pos + 8 <= until) {
            var size = (be32(buf, pos).toLong() and 0xFFFFFFFFL)
            val type = latin1(buf, pos + 4, 4)
            var headerSize = 8
            if (size == 1L) {
                if (pos + 16 > until) break
                size = be64(buf, pos + 8)
                headerSize = 16
            } else if (size == 0L) {
                size = (until - pos).toLong()
            }
            if (size < headerSize || pos + size > until) break
            out += BoxRef(type, pos.toLong(), headerSize, size)
            pos += size.toInt()
        }
        return out
    }

    /** The first direct child of [type], or null. */
    fun child(buf: ByteArray, from: Int, until: Int, type: String): BoxRef? =
        children(buf, from, until).firstOrNull { it.type == type }

    /**
     * A four-character type is real if it is printable ASCII. Used to notice
     * that a "box" is actually junk and stop, rather than walking off into the
     * middle of a video stream.
     */
    private fun isPlausibleType(type: String): Boolean =
        type.length == 4 && type.all { it.code in 0x20..0x7E || it.code == 0xA9 }

    // --- big-endian helpers -------------------------------------------------

    fun be16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    fun be32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or
                ((b[i + 1].toInt() and 0xFF) shl 16) or
                ((b[i + 2].toInt() and 0xFF) shl 8) or
                (b[i + 3].toInt() and 0xFF)

    fun be64(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    fun putBe16(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v ushr 8) and 0xFF).toByte()
        b[i + 1] = (v and 0xFF).toByte()
    }

    fun putBe32(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v ushr 24) and 0xFF).toByte()
        b[i + 1] = ((v ushr 16) and 0xFF).toByte()
        b[i + 2] = ((v ushr 8) and 0xFF).toByte()
        b[i + 3] = (v and 0xFF).toByte()
    }

    fun putBe64(b: ByteArray, i: Int, v: Long) {
        for (k in 0 until 8) b[i + k] = ((v ushr ((7 - k) * 8)) and 0xFF).toByte()
    }

    /**
     * Box types are Latin-1, not UTF-8: the iTunes atoms begin with byte 0xA9,
     * which is the copyright sign in Latin-1 and half a character in UTF-8.
     */
    fun latin1(b: ByteArray, offset: Int, count: Int): String =
        String(b, offset, count, Charsets.ISO_8859_1)

    fun typeBytes(type: String): ByteArray {
        require(type.length == 4) { "box type must be 4 characters: $type" }
        return type.toByteArray(Charsets.ISO_8859_1)
    }
}
