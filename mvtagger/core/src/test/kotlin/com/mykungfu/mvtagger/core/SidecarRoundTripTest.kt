package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sidecar is the only record a file that cannot hold tags has of what it
 * is, so what goes in has to come back out. It was write-only until now, which
 * is why a corrected MKV episode looked as though it had not saved: the details
 * were written, and then read from nowhere.
 */
class SidecarRoundTripTest {

    @Test
    fun `an episode survives the trip, season and number included`() {
        val tags = VideoTags(
            mediaKind = MediaKind.TV_EPISODE,
            title = "The Bicameral Mind",
            showName = "Westworld",
            seasonNumber = 1,
            episodeNumber = 10,
            date = "2016-12-04",
            network = "HBO",
            genre = "Drama",
        )
        val back = Sidecar.parse(Sidecar.json(tags, "Westworld - S01E10.mkv"))!!

        assertEquals(MediaKind.TV_EPISODE, back.mediaKind)
        assertEquals("Westworld", back.showName)
        assertEquals(1, back.seasonNumber)
        assertEquals(10, back.episodeNumber)
        assertEquals("The Bicameral Mind", back.title)
        assertEquals("HBO", back.network)
        assertEquals("2016", back.year)
    }

    @Test
    fun `a music video keeps the fields that identify it`() {
        val tags = VideoTags(
            mediaKind = MediaKind.MUSIC_VIDEO,
            title = "Besharam Rang",
            artist = "Shilpa Rao",
            album = "Pathaan",
            language = "hi",
            date = "2022",
        )
        val back = Sidecar.parse(Sidecar.json(tags))!!
        assertEquals(MediaKind.MUSIC_VIDEO, back.mediaKind)
        assertEquals("Shilpa Rao", back.artist)
        assertEquals("Pathaan", back.album)
        assertEquals("hi", back.language)
    }

    @Test
    fun `text that would break the JSON comes back intact`() {
        val tags = VideoTags(
            mediaKind = MediaKind.MOVIE,
            title = "He said \"stop\" \\ then left",
            albumInfo = "Line one\nline two\ttabbed",
        )
        val back = Sidecar.parse(Sidecar.json(tags))!!
        assertEquals("He said \"stop\" \\ then left", back.title)
        assertEquals("Line one\nline two\ttabbed", back.albumInfo)
    }

    /** A stray .json beside a video must not be able to invent an entry. */
    @Test
    fun `nothing usable reads as nothing`() {
        assertNull(Sidecar.parse("{}"))
        assertNull(Sidecar.parse("not json at all"))
        assertNull(Sidecar.parse(""))
    }
}
