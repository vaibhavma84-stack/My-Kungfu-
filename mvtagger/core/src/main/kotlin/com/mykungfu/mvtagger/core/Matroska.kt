package com.mykungfu.mvtagger.core

import java.io.ByteArrayOutputStream

/**
 * Cover art and details written into a Matroska file.
 *
 * MKV has been the hole in this app since the beginning. It has no place for
 * the iTunes-style atoms an MP4 carries, so the answer so far was to repackage
 * into MP4 -- and that only works when Android's muxer accepts the streams,
 * which for a television rip it usually does not: AC3, E-AC3 and DTS are all
 * refused, and those are what television comes in. The file was copied
 * unchanged and the cover written to a .jpg beside it, which is not what
 * "embedded" means and does not travel with the file.
 *
 * But MKV is not actually short of somewhere to put these. It has attachments,
 * and a picture attached as `cover.jpg` is the convention every player follows
 * -- Infuse, Plex, Jellyfin, Kodi and VLC all read it. It has a Tags element
 * too, which carries a title and the rest.
 *
 * ## Appending rather than rewriting
 *
 * Both elements are added at the *end* of the Segment, after the clusters, and
 * that choice is the whole reason this is safe to attempt. Every absolute
 * position already recorded in the file -- the SeekHead, the Cues, which is
 * what a player uses to jump about -- points at something before the end, and
 * appending moves none of them. Inserting anywhere else would shift them all
 * and every one would have to be found and corrected, which is the same
 * problem the MP4 writer solves for chunk offsets and is far harder here.
 *
 * The cost is that the SeekHead does not list what was added. A player that
 * reads only the SeekHead will not see the cover; one that walks the Segment
 * will. The ffmpeg family walks it, and that is what the apps above are built
 * on, so this reaches the ones that matter.
 *
 * One number does have to change: the Segment's own length. That is rewritten
 * in place, in the same number of bytes it already occupies, and if the new
 * length will not fit in that width the whole attempt is abandoned rather than
 * shifting the file to make room.
 */
object Matroska {

    /** Files this can be tried on. */
    private val EXTENSIONS = setOf("mkv", "mka", "mks", "webm")

    fun isMatroska(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS

    // Element ids, written exactly as they appear in the file.
    private val EBML_HEADER = bytes(0x1A, 0x45, 0xDF, 0xA3)
    private val SEGMENT = bytes(0x18, 0x53, 0x80, 0x67)
    private val ATTACHMENTS = bytes(0x19, 0x41, 0xA4, 0x69)
    private val ATTACHED_FILE = bytes(0x61, 0xA7)
    private val FILE_DESCRIPTION = bytes(0x46, 0x7E)
    private val FILE_NAME = bytes(0x46, 0x6E)
    private val FILE_MIME = bytes(0x46, 0x60)
    private val FILE_DATA = bytes(0x46, 0x5C)
    private val FILE_UID = bytes(0x46, 0xAE)
    private val TAGS = bytes(0x12, 0x54, 0xC3, 0x67)
    private val TAG = bytes(0x73, 0x73)
    private val TARGETS = bytes(0x63, 0xC0)
    private val TARGET_TYPE_VALUE = bytes(0x68, 0xCA)
    private val SIMPLE_TAG = bytes(0x67, 0xC8)
    private val TAG_NAME = bytes(0x45, 0xA3)
    private val TAG_STRING = bytes(0x44, 0x87)

    /** How much of the front of a file is needed to find the Segment. */
    const val HEAD_BYTES = 256

    /**
     * Where the Segment's length is written, and what it currently says.
     *
     * [size] is -1 when the file states an unknown length, which some tools
     * write while recording. That case needs no patching at all.
     */
    class Segment(val sizeAt: Int, val sizeWidth: Int, val size: Long, val dataAt: Int)

    /** Finds the Segment, or null if this does not look like Matroska. */
    fun segmentOf(head: ByteArray): Segment? {
        if (head.size < 8 || !startsWith(head, 0, EBML_HEADER)) return null

        // Step over the EBML header to reach the Segment behind it.
        val headerSize = readSize(head, EBML_HEADER.size) ?: return null
        var at = EBML_HEADER.size + headerSize.width + headerSize.value.toInt()
        if (headerSize.value < 0 || at + SEGMENT.size + 1 > head.size) return null
        if (!startsWith(head, at, SEGMENT)) return null

        at += SEGMENT.size
        val size = readSize(head, at) ?: return null
        return Segment(
            sizeAt = at,
            sizeWidth = size.width,
            size = size.value,
            dataAt = at + size.width,
        )
    }

    /**
     * The Segment's new length, in exactly the width it already uses.
     *
     * Null when it will not fit, which is the signal to leave the file alone:
     * widening the field would move every byte after it and invalidate every
     * position recorded in the file.
     */
    fun resized(segment: Segment, added: Long): ByteArray? {
        if (segment.size < 0) return null
        return sizeBytes(segment.size + added, segment.sizeWidth)
    }

    /**
     * The bytes to append: the cover, and the details, as far as there are any.
     *
     * Empty when there is nothing worth adding, so the caller can skip the
     * whole exercise rather than rewriting a file to add nothing.
     */
    fun additions(tags: VideoTags): ByteArray {
        val out = ByteArrayOutputStream()
        tags.artwork?.let { out.write(attachments(it)) }
        tagsElement(tags)?.let { out.write(it) }
        return out.toByteArray()
    }

    private fun attachments(art: Artwork): ByteArray {
        val name = if (art.isPng) "cover.png" else "cover.jpg"
        val file = ByteArrayOutputStream()
        file.write(element(FILE_DESCRIPTION, "Cover".toByteArray(Charsets.UTF_8)))
        file.write(element(FILE_NAME, name.toByteArray(Charsets.UTF_8)))
        // The picture's own type, not a guess from the name it was given.
        file.write(element(FILE_MIME, art.mime.toByteArray(Charsets.UTF_8)))
        file.write(element(FILE_DATA, art.bytes))
        // A unique id is required and must not be zero. The picture's own size
        // and first bytes make one that is stable for the same picture.
        file.write(element(FILE_UID, uid(art.bytes)))
        return element(ATTACHMENTS, element(ATTACHED_FILE, file.toByteArray()))
    }

    private fun tagsElement(tags: VideoTags): ByteArray? {
        val simple = ArrayList<Pair<String, String>>()
        fun put(name: String, value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { simple += name to it }
        }

        put("TITLE", tags.title)
        put("ARTIST", tags.artist)
        put("ALBUM", tags.album)
        put("DATE_RELEASED", tags.date)
        put("GENRE", tags.genre)
        put("DESCRIPTION", tags.description ?: tags.albumInfo)
        put("COMMENT", tags.comment)
        put("COMPOSER", tags.composer)
        put("LYRICIST", tags.lyricist)
        put("LANGUAGE", tags.language)
        // Television, where the series is the collection and the episode the
        // part of it. Written flat rather than nested, which readers accept and
        // which keeps this to one level.
        put("TVSHOW", tags.showName)
        put("PART_NUMBER", tags.episodeNumber?.toString())
        put("SEASON", tags.seasonNumber?.toString())
        put("NETWORK", tags.network)

        // Nothing known means nothing to write. The encoder name is added only
        // once something real is going in beside it -- on its own it would make
        // an empty file look worth rewriting, which is a gigabyte of copying to
        // record that this app touched it.
        if (simple.isEmpty()) return null
        put("ENCODER", Mp4Metadata.ENCODER_NAME)

        val tag = ByteArrayOutputStream()
        // 50 is the "album, film, episode" level -- the whole file, which is
        // what everything here describes.
        tag.write(element(TARGETS, element(TARGET_TYPE_VALUE, uintBytes(50))))
        for ((name, value) in simple) {
            val inner = ByteArrayOutputStream()
            inner.write(element(TAG_NAME, name.toByteArray(Charsets.UTF_8)))
            inner.write(element(TAG_STRING, value.toByteArray(Charsets.UTF_8)))
            tag.write(element(SIMPLE_TAG, inner.toByteArray()))
        }
        return element(TAGS, element(TAG, tag.toByteArray()))
    }

    /**
     * Walks the top-level elements of the Segment, for checking the result.
     *
     * A file whose elements do not run exactly to its end has been written
     * wrongly, and the point of walking it is to find that out here rather than
     * on a player.
     */
    fun topLevelIdsEndAt(data: ByteArray, segment: Segment, limit: Int): Int {
        var at = segment.dataAt
        while (at < limit) {
            val idWidth = idWidthAt(data, at) ?: return -1
            if (at + idWidth >= limit) return -1
            val size = readSize(data, at + idWidth) ?: return -1
            if (size.value < 0) return -1
            val next = at + idWidth + size.width + size.value
            if (next > limit || next <= at) return -1
            at = next.toInt()
        }
        return at
    }

    // --- EBML ----------------------------------------------------------------

    class Size(val value: Long, val width: Int)

    /** A length as EBML writes it: the width is in the leading bits. */
    fun readSize(data: ByteArray, at: Int): Size? {
        if (at >= data.size) return null
        val first = data[at].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            width++
            mask = mask shr 1
        }
        if (at + width > data.size) return null

        var value = (first and (mask - 1)).toLong()
        var allOnes = (first and (mask - 1)) == (mask - 1)
        for (i in 1 until width) {
            val b = data[at + i].toInt() and 0xFF
            if (b != 0xFF) allOnes = false
            value = (value shl 8) or b.toLong()
        }
        // All ones means "length unknown", which is a real thing in a file
        // still being recorded and never a length to do arithmetic on.
        return Size(if (allOnes) -1L else value, width)
    }

    /** The width of the element id at this position. */
    private fun idWidthAt(data: ByteArray, at: Int): Int? {
        if (at >= data.size) return null
        val first = data[at].toInt() and 0xFF
        return when {
            first and 0x80 != 0 -> 1
            first and 0x40 != 0 -> 2
            first and 0x20 != 0 -> 3
            first and 0x10 != 0 -> 4
            else -> null
        }
    }

    /** A length in the narrowest form that holds it. */
    fun sizeBytes(value: Long): ByteArray {
        for (width in 1..8) {
            sizeBytes(value, width)?.let { return it }
        }
        throw IllegalArgumentException("length too large for EBML: " + value)
    }

    /** A length in exactly this many bytes, or null if it will not fit. */
    fun sizeBytes(value: Long, width: Int): ByteArray? {
        if (value < 0 || width !in 1..8) return null
        val bits = 7 * width
        // The largest value is reserved for "unknown", so it cannot be used.
        val max = (1L shl bits) - 2
        if (value > max) return null

        val out = ByteArray(width)
        var v = value
        for (i in width - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        out[0] = (out[0].toInt() or (1 shl (8 - width))).toByte()
        return out
    }

    fun element(id: ByteArray, payload: ByteArray): ByteArray {
        val size = sizeBytes(payload.size.toLong())
        val out = ByteArray(id.size + size.size + payload.size)
        id.copyInto(out, 0)
        size.copyInto(out, id.size)
        payload.copyInto(out, id.size + size.size)
        return out
    }

    /** An unsigned integer, in the fewest bytes that carry it. */
    private fun uintBytes(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        var length = 8
        while (length > 1 && (value shr ((length - 1) * 8)) and 0xFF == 0L) length--
        val out = ByteArray(length)
        for (i in 0 until length) {
            out[length - 1 - i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
        return out
    }

    private fun uid(bytes: ByteArray): ByteArray {
        var value = bytes.size.toLong() * 2_654_435_761L
        for (i in 0 until minOf(bytes.size, 64)) {
            value = value * 31 + (bytes[i].toInt() and 0xFF)
        }
        // Never zero, which is not a valid id.
        return uintBytes((value and 0x7FFF_FFFF_FFFFL).coerceAtLeast(1L))
    }

    private fun startsWith(data: ByteArray, at: Int, id: ByteArray): Boolean {
        if (at + id.size > data.size) return false
        for (i in id.indices) if (data[at + i] != id[i]) return false
        return true
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }
}
