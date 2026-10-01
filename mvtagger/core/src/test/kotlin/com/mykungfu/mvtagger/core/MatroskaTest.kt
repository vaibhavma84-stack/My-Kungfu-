package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover art the app could never put inside an MKV.
 *
 * These build a small but structurally real Matroska file, append to it the way
 * the app does, and then walk the result the way a player would. There is no
 * MKV to hand here and no player to try it in, so walking the elements back and
 * insisting they end exactly on the last byte is what stands in for that.
 */
class MatroskaTest {

    /** An EBML header and a Segment holding one Void element. */
    private fun file(segmentSizeWidth: Int = 8): ByteArray {
        val header = Matroska.element(
            byteArrayOf(0x1A, 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()),
            // DocType "matroska"
            Matroska.element(byteArrayOf(0x42, 0x82.toByte()), "matroska".toByteArray()),
        )
        // A Void element standing in for everything a real file holds.
        val body = Matroska.element(byteArrayOf(0xEC.toByte()), ByteArray(64))
        val size = Matroska.sizeBytes(body.size.toLong(), segmentSizeWidth)!!
        val id = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)
        return header + id + size + body
    }

    private val art = Artwork(bytes = ByteArray(200) { (it % 251).toByte() }, mime = "image/jpeg")

    private val tags = VideoTags(
        mediaKind = MediaKind.TV_EPISODE,
        title = "Queen's Landing",
        showName = "House of the Dragon",
        seasonNumber = 3,
        episodeNumber = 2,
        artwork = art,
    )

    @Test
    fun `the segment is found and measured`() {
        val data = file()
        val segment = Matroska.segmentOf(data)
        assertNotNull(segment)
        assertEquals(data.size.toLong(), (segment!!.dataAt + segment.size))
    }

    @Test
    fun `something that is not matroska is refused rather than mangled`() {
        assertNull(Matroska.segmentOf(ByteArray(64)))
        assertNull(Matroska.segmentOf("not a media file at all".toByteArray()))
        assertNull(Matroska.segmentOf(ByteArray(3)))
    }

    /**
     * The point of the whole exercise: the file still parses afterwards, and
     * every element runs exactly to the end with nothing left over.
     */
    @Test
    fun `after appending, the elements still end on the last byte`() {
        val original = file()
        val added = Matroska.additions(tags)
        assertTrue("nothing was produced to add", added.isNotEmpty())

        val segment = Matroska.segmentOf(original)!!
        val newSize = Matroska.resized(segment, added.size.toLong())!!
        assertEquals(
            "the length field changed width, which would shift the file",
            segment.sizeWidth, newSize.size,
        )

        val out = original.copyOf(original.size + added.size)
        added.copyInto(out, original.size)
        newSize.copyInto(out, segment.sizeAt)

        val after = Matroska.segmentOf(out)!!
        assertEquals(out.size.toLong(), after.dataAt + after.size)
        assertEquals(
            "an element runs past the end, or stops short of it",
            out.size, Matroska.topLevelIdsEndAt(out, after, out.size),
        )
    }

    @Test
    fun `the cover is attached under the name players look for`() {
        val added = Matroska.additions(tags)
        val text = String(added, Charsets.ISO_8859_1)
        assertTrue(text.contains("cover.jpg"))
        assertTrue(text.contains("image/jpeg"))
        assertTrue("the picture itself is missing", added.size > art.bytes.size)
    }

    @Test
    fun `a png cover is named as one`() {
        val png = Matroska.additions(tags.copy(artwork = Artwork(ByteArray(10), "image/png")))
        val text = String(png, Charsets.ISO_8859_1)
        assertTrue(text.contains("cover.png"))
        assertTrue(text.contains("image/png"))
    }

    @Test
    fun `the details go in as tags`() {
        val text = String(Matroska.additions(tags), Charsets.ISO_8859_1)
        assertTrue(text.contains("TITLE"))
        assertTrue(text.contains("Queen's Landing"))
        assertTrue(text.contains("TVSHOW"))
        assertTrue(text.contains("House of the Dragon"))
    }

    @Test
    fun `nothing to say means nothing to write`() {
        assertEquals(0, Matroska.additions(VideoTags()).size)
    }

    /**
     * A narrow length field cannot be widened in place -- doing so would move
     * every byte after it and break every position recorded in the file. The
     * only safe answer is to decline.
     */
    @Test
    fun `a length field too narrow for the new size is refused`() {
        val data = file(segmentSizeWidth = 1)
        val segment = Matroska.segmentOf(data)!!
        assertEquals(1, segment.sizeWidth)
        assertNull("this should have been refused", Matroska.resized(segment, 5_000L))
        assertNotNull("a small addition still fits", Matroska.resized(segment, 4L))
    }

    @Test
    fun `lengths round trip at every width`() {
        for (width in 1..8) {
            val encoded = Matroska.sizeBytes(1000L, width) ?: continue
            assertEquals(width, encoded.size)
            val read = Matroska.readSize(encoded, 0)!!
            assertEquals(1000L, read.value)
            assertEquals(width, read.width)
        }
    }

    /** All-ones is "length unknown", never a number to add to. */
    @Test
    fun `an unknown length is reported as unknown and never patched`() {
        val unknown = byteArrayOf(0xFF.toByte())
        assertEquals(-1L, Matroska.readSize(unknown, 0)!!.value)
    }

    @Test
    fun `only matroska extensions are attempted`() {
        assertTrue(Matroska.isMatroska("Episode.mkv"))
        assertTrue(Matroska.isMatroska("Song.webm"))
        assertTrue(!Matroska.isMatroska("Film.mp4"))
        assertTrue(!Matroska.isMatroska("Clip.avi"))
    }

    /**
     * Writing a cover was only half of it. Nothing could read one back, so a
     * cover written into an MKV stayed invisible to the app that wrote it.
     */
    @Test
    fun `a cover written in can be read back out`() {
        val added = Matroska.additions(tags)
        val back = Matroska.coverIn(added)
        assertNotNull("the cover could not be found again", back)
        assertTrue(back!!.bytes.contentEquals(art.bytes))
        assertEquals("image/jpeg", back.mime)
    }

    /** It is found in the tail of a file, which is where this app puts it. */
    @Test
    fun `a cover is found among everything else in the tail`() {
        val noise = ByteArray(5000) { (it % 97).toByte() }
        val tail = noise + Matroska.additions(tags) + ByteArray(0)
        assertNotNull(Matroska.coverIn(tail))
        assertTrue(Matroska.hasAttachments(tail))
    }

    @Test
    fun `a file with no attachment says so rather than inventing one`() {
        assertNull(Matroska.coverIn(ByteArray(4096)))
        assertTrue(!Matroska.hasAttachments(ByteArray(4096)))
        // Details but no picture: there is a Tags element and no cover.
        val tagsOnly = Matroska.additions(tags.copy(artwork = null))
        assertNull(Matroska.coverIn(tagsOnly))
    }

    @Test
    fun `a png cover keeps its type on the way back`() {
        val png = Matroska.additions(tags.copy(artwork = Artwork(ByteArray(64) { 7 }, "image/png")))
        assertEquals("image/png", Matroska.coverIn(png)!!.mime)
    }
}

/**
 * Replacing a cover in an MKV rather than adding a second one.
 *
 * Appending was safe and only half an answer: tag a file twice and it carried
 * two `cover.jpg` attachments, players took the first, and the correction had
 * no effect. The app knew and refused to try, so a cover could never be
 * changed. Matroska's own Void element is the way out -- padding that every
 * parser skips, written over the old element in exactly its bytes, so nothing
 * moves and no recorded position is invalidated.
 */
class MatroskaReplaceTest {

    private fun jpeg(size: Int, fill: Byte): Artwork {
        val bytes = ByteArray(size) { fill }
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        return Artwork(bytes, "image/jpeg")
    }

    private val first = VideoTags(title = "First", artwork = jpeg(400, 0x11))
    private val second = VideoTags(title = "Second", artwork = jpeg(300, 0x22))

    @Test
    fun `a void element fills exactly the bytes it is given`() {
        for (total in 2L..300L) {
            val filler = Matroska.voidOf(total)
            assertNotNull("no void for " + total, filler)
            assertEquals("wrong length for " + total, total.toInt(), filler!!.size)
            assertEquals("not a void", 0xEC, filler[0].toInt() and 0xFF)
        }
    }

    @Test
    fun `a single byte cannot be blanked and says so`() {
        assertNull(Matroska.voidOf(1))
        assertNull(Matroska.voidOf(0))
    }

    @Test
    fun `a void declares the length it actually occupies`() {
        // The whole point: a parser steps over exactly this element and lands
        // on whatever follows, so the declared length has to be the real one.
        for (total in longArrayOf(2, 3, 100, 128, 129, 200, 20_000)) {
            val filler = Matroska.voidOf(total)!!
            val size = Matroska.readSize(filler, 1)
            assertNotNull("unreadable length for " + total, size)
            assertEquals(
                "declared length wrong for " + total,
                total, (1 + size!!.width + size.value),
            )
        }
    }

    @Test
    fun `what a previous run wrote is found so it can be replaced`() {
        val additions = Matroska.additions(first)
        val found = Matroska.replaceable(additions, 1000L)
        // The attachments and the tags, both of them.
        assertEquals(found.toString(), 2, found.size)
        // Between them they account for every byte written.
        assertEquals(1000L, found.first().at)
        assertEquals(
            additions.size.toLong(),
            found.sumOf { it.length },
        )
    }

    @Test
    fun `voiding the old one leaves the new cover the only one readable`() {
        val before = Matroska.additions(first)
        val work = before.copyOf()
        for (region in Matroska.replaceable(work, 0L)) {
            Matroska.voidOf(region.length)!!.copyInto(work, region.at.toInt())
        }
        // The old picture is gone from the blanked stretch...
        assertNull(Matroska.coverIn(work))
        // ...and a file that is the blanked stretch plus the new additions has
        // exactly the new cover in it.
        val after = work + Matroska.additions(second)
        val cover = Matroska.coverIn(after)
        assertNotNull(cover)
        assertEquals(300, cover!!.bytes.size)
        assertEquals(0x22.toByte(), cover.bytes[10])
    }

    /**
     * The dangerous case. Reading a false positive gives a nonsense picture;
     * voiding one writes padding over somebody's video.
     */
    @Test
    fun `four bytes of video that look like an element id are not believed`() {
        // A cluster's worth of noise with the Attachments id sitting in it and
        // a plausible length after it, which is what a long file will contain
        // by accident sooner or later.
        val noise = ByteArray(4096) { (it * 37 % 251).toByte() }
        noise[500] = 0x19; noise[501] = 0x41; noise[502] = 0xA4.toByte(); noise[503] = 0x69
        noise[504] = 0x88.toByte()  // a one-byte length saying eight
        assertTrue(
            "noise was taken for an element",
            Matroska.replaceable(noise, 0L).isEmpty(),
        )
    }

    @Test
    fun `an element running off the end of the window is left alone`() {
        val additions = Matroska.additions(first)
        // Cut it short: what is here no longer tiles, so it is not believed.
        val clipped = additions.copyOf(additions.size / 2)
        assertTrue(
            Matroska.replaceable(clipped, 0L).toString(),
            Matroska.replaceable(clipped, 0L).none { it.at + it.length > clipped.size },
        )
    }

    @Test
    fun `a file with no additions has nothing to replace`() {
        assertTrue(Matroska.replaceable(ByteArray(1024), 0L).isEmpty())
        assertTrue(Matroska.replaceable(ByteArray(0), 0L).isEmpty())
    }

    @Test
    fun `blanking changes no byte outside the stretch it was given`() {
        val before = Matroska.additions(first)
        val padded = ByteArray(64) { 0x5A } + before + ByteArray(64) { 0x5A }
        val work = padded.copyOf()
        for (region in Matroska.replaceable(work, 0L)) {
            Matroska.voidOf(region.length)!!.copyInto(work, region.at.toInt())
        }
        for (i in 0 until 64) assertEquals("front byte " + i, 0x5A.toByte(), work[i])
        for (i in padded.size - 64 until padded.size) {
            assertEquals("tail byte " + i, 0x5A.toByte(), work[i])
        }
        assertEquals("length changed", padded.size, work.size)
    }
}
