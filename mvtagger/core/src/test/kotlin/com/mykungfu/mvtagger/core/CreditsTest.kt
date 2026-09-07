package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmTitleTest {

    @Test
    fun `the film comes out of the track title`() {
        assertEquals("Brahmastra", FilmTitle.fromTrackTitle("Kesariya (From \"Brahmastra\")"))
        assertEquals("Jawan", FilmTitle.fromTrackTitle("Chaleya [From Jawan]"))
        assertEquals("Pathaan", FilmTitle.fromTrackTitle("Jhoome Jo Pathaan (From the film \"Pathaan\")"))
    }

    @Test
    fun `the song keeps only its own name`() {
        assertEquals("Kesariya", FilmTitle.songTitle("Kesariya (From \"Brahmastra\")"))
        assertEquals("Hello", FilmTitle.songTitle("Hello"))
    }

    @Test
    fun `soundtrack boilerplate is stripped to leave the film`() {
        assertEquals(
            "Brahmastra",
            FilmTitle.cleanAlbum("Brahmastra (Original Motion Picture Soundtrack)"),
        )
        assertEquals("Jawan", FilmTitle.cleanAlbum("Jawan - Original Motion Picture Soundtrack"))
        assertEquals("Rockstar", FilmTitle.cleanAlbum("Rockstar [Original Soundtrack]"))
    }

    @Test
    fun `a bracket that is not boilerplate is left alone`() {
        assertEquals("Jawan (Deluxe)", FilmTitle.cleanAlbum("Jawan (Deluxe)"))
        assertEquals("25", FilmTitle.cleanAlbum("25"))
    }

    @Test
    fun `nothing is invented when the title says nothing`() {
        assertNull(FilmTitle.fromTrackTitle("Hello"))
        assertEquals("", FilmTitle.cleanAlbum(null))
    }

    @Test
    fun `a candidate puts the film in the album and the song in the title`() {
        val c = Candidate(
            source = "iTunes", id = "1",
            title = "Kesariya (From \"Brahmastra\")",
            artist = "Pritam, Arijit Singh & Amitabh Bhattacharya",
            album = "Brahmastra (Original Motion Picture Soundtrack)",
        )
        val tags = c.toTags()
        assertEquals("Kesariya", tags.title)
        assertEquals("Brahmastra", tags.album)
    }
}

class CreditNamesTest {

    @Test
    fun `a run-together credit splits into people`() {
        assertEquals(
            listOf("Pritam", "Arijit Singh", "Amitabh Bhattacharya"),
            CreditNames.split("Pritam, Arijit Singh & Amitabh Bhattacharya"),
        )
        assertEquals(
            listOf("Arijit Singh", "Shreya Ghoshal"),
            CreditNames.split("Arijit Singh feat. Shreya Ghoshal"),
        )
    }

    @Test
    fun `a single name stays one name`() {
        assertEquals(listOf("Adele"), CreditNames.split("Adele"))
        assertEquals(emptyList<String>(), CreditNames.split(null))
    }
}

class RecordingCreditsTest {

    /** Shaped like a real MusicBrainz recording lookup with relationships. */
    private val body = """
        {
          "id": "abc",
          "title": "Kesariya",
          "relations": [
            { "type": "vocal",
              "artist": { "name": "Arijit Singh" } },
            { "type": "instrument",
              "artist": { "name": "Some Musician" } },
            { "type": "performance",
              "work": {
                "title": "Kesariya",
                "relations": [
                  { "type": "composer", "artist": { "name": "Pritam" } },
                  { "type": "lyricist", "artist": { "name": "Amitabh Bhattacharya" } }
                ]
              } }
          ]
        }
    """.trimIndent()

    @Test
    fun `the singer is read from the vocal relationship`() {
        val credits = MusicBrainz.parseRecordingCredits(body)
        assertEquals(listOf("Arijit Singh"), credits.singers)
        assertEquals("Arijit Singh", credits.singerLine)
    }

    @Test
    fun `composer and lyricist come from the work`() {
        val credits = MusicBrainz.parseRecordingCredits(body)
        assertEquals(listOf("Pritam"), credits.composers)
        assertEquals(listOf("Amitabh Bhattacharya"), credits.lyricists)
    }

    @Test
    fun `a recording with no relationships yields nothing rather than a guess`() {
        val credits = MusicBrainz.parseRecordingCredits("""{"id":"abc","title":"X"}""")
        assertTrue(credits.isEmpty)
        assertNull(credits.singerLine)
    }
}
