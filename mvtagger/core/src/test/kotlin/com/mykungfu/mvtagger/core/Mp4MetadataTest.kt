package com.mykungfu.mvtagger.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Mp4MetadataTest {

    private val fullTags = VideoTags(
        mediaKind = MediaKind.MUSIC_VIDEO,
        title = "Kesariya",
        artist = "Arijit Singh",
        albumArtist = "Pritam",
        album = "Brahmastra (Original Motion Picture Soundtrack)",
        date = "2022-07-17",
        genre = "Bollywood",
        comment = "tagged by tests",
        description = "A short description",
        longDescription = "A much longer description ".repeat(20),
        artistBio = "Arijit Singh is an Indian playback singer.",
        albumInfo = "Brahmastra is a 2022 Hindi-language film.",
        composer = "Pritam",
        lyricist = "Amitabh Bhattacharya",
        lyrics = "Kesariya tera ishq hai piya",
        syncedLyrics = "[00:12.30]Kesariya tera ishq hai piya",
        trackNumber = 3,
        trackTotal = 12,
        language = "hi",
        source = "iTunes",
        sourceId = "1234567890",
    )

    @Test
    fun `an untagged file reads as empty`() {
        val tags = TestMp4.readTags(TestMp4.build().bytes)
        assertTrue("expected no tags, got $tags", tags.isEmpty)
    }

    @Test
    fun `every field survives a write and read`() {
        val tagged = TestMp4.writeToBytes(TestMp4.build().bytes, fullTags)
        val back = TestMp4.readTags(tagged)

        assertEquals("Kesariya", back.title)
        assertEquals("Arijit Singh", back.artist)
        assertEquals("Pritam", back.albumArtist)
        assertEquals(fullTags.album, back.album)
        assertEquals("2022-07-17", back.date)
        assertEquals("2022", back.year)
        assertEquals("Bollywood", back.genre)
        assertEquals("tagged by tests", back.comment)
        assertEquals("A short description", back.description)
        assertEquals(fullTags.longDescription, back.longDescription)
        assertEquals(fullTags.artistBio, back.artistBio)
        assertEquals(fullTags.albumInfo, back.albumInfo)
        assertEquals("Pritam", back.composer)
        assertEquals("Amitabh Bhattacharya", back.lyricist)
        assertEquals(fullTags.lyrics, back.lyrics)
        assertEquals(fullTags.syncedLyrics, back.syncedLyrics)
        assertEquals(3, back.trackNumber)
        assertEquals(12, back.trackTotal)
        assertEquals("hi", back.language)
        assertEquals("iTunes", back.source)
        assertEquals("1234567890", back.sourceId)
        assertEquals(MediaKind.MUSIC_VIDEO, back.mediaKind)
    }

    @Test
    fun `devanagari and other scripts survive the round trip`() {
        val tags = VideoTags(
            title = "केसरिया",
            artist = "अरिजीत सिंह",
            album = "ब्रह्मास्त्र",
            lyrics = "केसरिया तेरा इश्क़ है पिया\nदूसरी पंक्ति",
        )
        val back = TestMp4.readTags(TestMp4.writeToBytes(TestMp4.build().bytes, tags))
        assertEquals("केसरिया", back.title)
        assertEquals("अरिजीत सिंह", back.artist)
        assertEquals("ब्रह्मास्त्र", back.album)
        assertEquals(tags.lyrics, back.lyrics)
    }

    @Test
    fun `artwork survives the round trip byte for byte`() {
        val jpeg = TestMp4.jpegBytes(4096)
        val back = TestMp4.readTags(
            TestMp4.writeToBytes(TestMp4.build().bytes, VideoTags(artwork = Artwork(jpeg, "image/jpeg")))
        )
        assertNotNull("artwork was lost", back.artwork)
        assertEquals("image/jpeg", back.artwork!!.mime)
        assertArrayEquals(jpeg, back.artwork!!.bytes)
    }

    /**
     * The one that matters. Growing `moov` moves every byte of media, so if the
     * chunk offsets are not corrected the file still parses but plays nothing.
     */
    @Test
    fun `chunk offsets still point at their own chunks after tagging`() {
        for (moovFirst in listOf(true, false)) {
            val built = TestMp4.build(chunkCount = 6, moovFirst = moovFirst)
            val tagged = TestMp4.writeToBytes(built.bytes, fullTags)

            assertEquals(
                "chunk offsets wrong with moovFirst=$moovFirst",
                built.chunkMarkers,
                TestMp4.markersAtChunkOffsets(tagged),
            )
            for ((i, chunk) in TestMp4.chunkContents(tagged).withIndex()) {
                assertArrayEquals(
                    "chunk $i corrupted with moovFirst=$moovFirst",
                    ByteArray(TestMp4.CHUNK_SIZE) { built.chunkMarkers[i] },
                    chunk,
                )
            }
        }
    }

    @Test
    fun `a moov at the end is moved in front of the media`() {
        val built = TestMp4.build(moovFirst = false)
        val tagged = TestMp4.writeToBytes(built.bytes, fullTags)
        val types = Mp4.topLevelBoxes(TestMp4.source(tagged)).map { it.type }
        assertEquals(listOf("ftyp", "moov", "mdat"), types)
    }

    @Test
    fun `a free box is reclaimed and the offsets still hold`() {
        val built = TestMp4.build(freeBox = true, moovFirst = false)
        val tagged = TestMp4.writeToBytes(built.bytes, fullTags)
        val types = Mp4.topLevelBoxes(TestMp4.source(tagged)).map { it.type }
        assertTrue("free box should have been dropped, got $types", "free" !in types)
        assertEquals(built.chunkMarkers, TestMp4.markersAtChunkOffsets(tagged))
    }

    @Test
    fun `re-tagging replaces the metadata instead of piling it up`() {
        val built = TestMp4.build()
        val once = TestMp4.writeToBytes(built.bytes, fullTags)
        val twice = TestMp4.writeToBytes(once, fullTags)
        val thrice = TestMp4.writeToBytes(twice, fullTags)

        // Identical tags each time, so any growth is old metadata being kept
        // alongside the new rather than replaced by it.
        assertEquals("file grew on re-tagging", once.size, twice.size)
        assertEquals("file grew on re-tagging", twice.size, thrice.size)
        assertEquals(built.chunkMarkers, TestMp4.markersAtChunkOffsets(thrice))

        // And a genuine edit still takes effect.
        val edited = TestMp4.writeToBytes(thrice, fullTags.copy(title = "Edited"))
        assertEquals("Edited", TestMp4.readTags(edited).title)
        assertEquals(built.chunkMarkers, TestMp4.markersAtChunkOffsets(edited))
    }

    @Test
    fun `an existing udta is replaced cleanly`() {
        val built = TestMp4.build(tagged = true)
        val tagged = TestMp4.writeToBytes(built.bytes, VideoTags(title = "Replacement"))
        val back = TestMp4.readTags(tagged)
        assertEquals("Replacement", back.title)
        assertEquals(built.chunkMarkers, TestMp4.markersAtChunkOffsets(tagged))
    }

    @Test
    fun `movie and episode atoms round trip`() {
        val episode = VideoTags(
            mediaKind = MediaKind.TV_EPISODE,
            title = "Winter Is Coming",
            showName = "Game of Thrones",
            seasonNumber = 1,
            episodeNumber = 1,
            network = "HBO",
        )
        val back = TestMp4.readTags(TestMp4.writeToBytes(TestMp4.build().bytes, episode))
        assertEquals(MediaKind.TV_EPISODE, back.mediaKind)
        assertEquals("Game of Thrones", back.showName)
        assertEquals(1, back.seasonNumber)
        assertEquals(1, back.episodeNumber)
        assertEquals("HBO", back.network)

        val movie = TestMp4.readTags(
            TestMp4.writeToBytes(
                TestMp4.build().bytes,
                VideoTags(mediaKind = MediaKind.MOVIE, title = "Brahmastra"),
            )
        )
        assertEquals(MediaKind.MOVIE, movie.mediaKind)
        assertNull(movie.showName)
    }

    @Test
    fun `a file with no moov is refused rather than mangled`() {
        val notMp4 = "this is not a video file at all, not even close".toByteArray()
        try {
            TestMp4.writeToBytes(notMp4, fullTags)
            fail("expected UnsupportedContainer")
        } catch (e: Mp4Metadata.UnsupportedContainer) {
            assertTrue(e.message!!.contains("MP4-family"))
        }
    }

    @Test
    fun `the written file is still a well formed box tree`() {
        val tagged = TestMp4.writeToBytes(TestMp4.build().bytes, fullTags)
        val boxes = Mp4.topLevelBoxes(TestMp4.source(tagged))
        assertEquals(
            "top-level boxes must tile the file exactly",
            tagged.size.toLong(),
            boxes.sumOf { it.size },
        )
        assertEquals(0L, boxes.first().start)
    }
}
