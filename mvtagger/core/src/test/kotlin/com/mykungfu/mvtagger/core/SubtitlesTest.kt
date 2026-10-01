package com.mykungfu.mvtagger.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFormatTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,500
        Hello there.

        2
        00:00:04,000 --> 00:00:06,000
        <i>Second line</i>
        over two rows.
    """.trimIndent()

    @Test
    fun `subrip is read with its timings`() {
        val cues = Subtitles.parseSrt(srt)
        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("Hello there.", cues[0].text)
    }

    @Test
    fun `markup is stripped but the line breaks are kept`() {
        val cues = Subtitles.parseSrt(srt)
        assertEquals("Second line\nover two rows.", cues[1].text)
    }

    @Test
    fun `webvtt timings with a full stop are read the same way`() {
        val vtt = """
            WEBVTT

            00:00:02.250 --> 00:00:04.000
            From a streaming site.
        """.trimIndent()
        val cues = Subtitles.parseSrt(vtt)
        assertEquals(1, cues.size)
        assertEquals(2_250L, cues[0].startMs)
        assertEquals("From a streaming site.", cues[0].text)
    }

    @Test
    fun `substation alpha is read and its override codes dropped`() {
        val ass = """
            [Script Info]
            Title: Test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\an8}Top line\NSecond line
        """.trimIndent()
        val cues = Subtitles.parse(ass)
        assertEquals(1, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals("Top line\nSecond line", cues[0].text)
    }

    @Test
    fun `writing subrip round trips`() {
        val cues = Subtitles.parseSrt(srt)
        val again = Subtitles.parseSrt(Subtitles.toSrt(cues))
        assertEquals(cues, again)
    }

    @Test
    fun `timestamps format the way subrip wants them`() {
        assertEquals("01:02:03,004", Subtitles.formatTimestamp(3_723_004))
        assertEquals("00:00:00,000", Subtitles.formatTimestamp(-5))
    }

    @Test
    fun `overlapping cues are separated, because a text track shows one at a time`() {
        val tidied = Subtitles.tidy(
            listOf(
                Cue(0, 5_000, "first"),
                Cue(3_000, 6_000, "second"),
            )
        )
        assertEquals(2, tidied.size)
        assertEquals(3_000L, tidied[0].endMs)
        assertEquals(3_000L, tidied[1].startMs)
    }

    @Test
    fun `empty and reversed cues are dropped rather than written`() {
        val tidied = Subtitles.tidy(
            listOf(Cue(1_000, 900, "backwards"), Cue(2_000, 3_000, "  "))
        )
        assertTrue(tidied.isEmpty())
    }

    @Test
    fun `the sidecar is named the way players look for it`() {
        assertEquals("Film.en.srt", Subtitles.sidecarName("Film", "en"))
        assertEquals("Film.srt", Subtitles.sidecarName("Film", null))
    }
}

/**
 * The subtitle track has to end up genuinely inside the MP4: a `sbtl` track
 * whose chunk offset points at real sample bytes. Getting the offset wrong
 * would leave a file that still opens and still plays, with subtitles that are
 * either missing or garbage -- exactly the failure the chunk-offset tests
 * already guard against for the video.
 */
class Mp4SubtitleTrackTest {

    private val track = SubtitleTrack(
        cues = listOf(
            Cue(1_000, 3_000, "Hello there."),
            Cue(4_000, 6_000, "Second line."),
        ),
        language = "en",
    )

    private fun tagged(): ByteArray =
        TestMp4.writeWithSubtitles(
            TestMp4.build(chunkCount = 4).bytes,
            VideoTags(title = "Episode", mediaKind = MediaKind.TV_EPISODE),
            track,
        )

    @Test
    fun `a subtitle track is added`() {
        val trak = TestMp4.trakWithHandler(tagged(), "sbtl")
        assertNotNull("no sbtl track was written", trak)
    }

    @Test
    fun `nothing is added when there are no subtitles`() {
        val plain = TestMp4.writeToBytes(TestMp4.build().bytes, VideoTags(title = "Episode"))
        assertNull(TestMp4.trakWithHandler(plain, "sbtl"))
    }

    @Test
    fun `the chunk offset points at the first subtitle sample`() {
        val bytes = tagged()
        val moov = TestMp4.moovOf(bytes)
        val trak = TestMp4.trakWithHandler(bytes, "sbtl")!!

        val mdia = Mp4.child(moov, trak.payloadStart.toInt(), trak.end.toInt(), "mdia")!!
        val minf = Mp4.child(moov, mdia.payloadStart.toInt(), mdia.end.toInt(), "minf")!!
        val stbl = Mp4.child(moov, minf.payloadStart.toInt(), minf.end.toInt(), "stbl")!!
        val co64 = Mp4.child(moov, stbl.payloadStart.toInt(), stbl.end.toInt(), "co64")!!

        val offset = Mp4.be64(moov, co64.payloadStart.toInt() + 8).toInt()
        assertTrue("offset $offset is outside the file", offset in 1 until bytes.size)

        // The first cue starts at 1s, so the first sample is an empty one that
        // holds the screen clear until then.
        assertEquals(0, Mp4.be16(bytes, offset))

        // Then the text of the first cue, length-prefixed.
        val text = "Hello there.".toByteArray(Charsets.UTF_8)
        assertEquals(text.size, Mp4.be16(bytes, offset + 2))
        assertArrayEquals(
            text,
            bytes.copyOfRange(offset + 4, offset + 4 + text.size),
        )
    }

    @Test
    fun `the video chunks are still where the video track says they are`() {
        val built = TestMp4.build(chunkCount = 4)
        val bytes = TestMp4.writeWithSubtitles(
            built.bytes, VideoTags(title = "Episode"), track,
        )
        assertEquals(built.chunkMarkers, TestMp4.markersAtChunkOffsets(bytes))
        for ((i, chunk) in TestMp4.chunkContents(bytes).withIndex()) {
            assertArrayEquals(
                "chunk $i was corrupted by adding subtitles",
                ByteArray(TestMp4.CHUNK_SIZE) { built.chunkMarkers[i] },
                chunk,
            )
        }
    }

    @Test
    fun `adding a track claims the next track number`() {
        val moov = TestMp4.moovOf(tagged())
        val mvhd = Mp4.child(moov, 8, moov.size, "mvhd")!!
        // The builder starts at 2; the new track takes it, so 3 is next.
        assertEquals(3, Mp4.be32(moov, mvhd.payloadStart.toInt() + 96))
    }

    @Test
    fun `the track says which language it is in`() {
        val bytes = tagged()
        val moov = TestMp4.moovOf(bytes)
        val trak = TestMp4.trakWithHandler(bytes, "sbtl")!!
        val mdia = Mp4.child(moov, trak.payloadStart.toInt(), trak.end.toInt(), "mdia")!!
        val mdhd = Mp4.child(moov, mdia.payloadStart.toInt(), mdia.end.toInt(), "mdhd")!!

        // Five bits per letter, offset from 0x60: "eng".
        val packed = Mp4.be16(moov, mdhd.payloadStart.toInt() + 20)
        val letters = (0..2).map { (((packed shr ((2 - it) * 5)) and 0x1F) + 0x60).toChar() }
        assertEquals("eng", letters.joinToString(""))
    }

    @Test
    fun `the tags still survive alongside the subtitles`() {
        val back = TestMp4.readTags(tagged())
        assertEquals("Episode", back.title)
        assertEquals(MediaKind.TV_EPISODE, back.mediaKind)
    }

    @Test
    fun `the file is still a well formed box tree`() {
        val bytes = tagged()
        val boxes = Mp4.topLevelBoxes(TestMp4.source(bytes))
        assertEquals(
            "top-level boxes must tile the file exactly",
            bytes.size.toLong(),
            boxes.sumOf { it.size },
        )
        // ftyp, moov, the original mdat, then the subtitle samples.
        assertEquals(listOf("ftyp", "moov", "mdat", "mdat"), boxes.map { it.type })
    }
}
