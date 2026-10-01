package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A podcast answer describes the series, not the file.
 *
 * The fault this guards against is a quiet one: run a podcast result through
 * the music parser and every episode of a two-hundred-episode show ends up
 * titled with the name of the show.
 */
class PodcastTest {

    private val body = """
        {"resultCount":1,"results":[{
          "wrapperType":"track","kind":"podcast",
          "collectionId":1234,
          "collectionName":"The Ranveer Show",
          "trackName":"The Ranveer Show",
          "artistName":"BeerBiceps",
          "primaryGenreName":"Health & Fitness",
          "releaseDate":"2026-01-04T12:00:00Z",
          "artworkUrl600":"https://example.com/a/600x600bb.jpg",
          "artworkUrl100":"https://example.com/a/100x100bb.jpg"
        }]}
    """.trimIndent()

    @Test
    fun `the show and its artwork come back`() {
        val found = ITunes.parsePodcasts(body, "IN")
        assertEquals(1, found.size)
        val one = found.first()
        assertEquals("The Ranveer Show", one.showName)
        assertEquals("BeerBiceps", one.artist)
        assertEquals(MediaKind.PODCAST, one.mediaKind)
        assertTrue(one.artworkUrls.isNotEmpty())
    }

    @Test
    fun `the episode keeps its own title`() {
        val tags = ITunes.parsePodcasts(body, "IN").first().toTags()
        // The whole point: nothing here is allowed to name the episode.
        assertNull(tags.title)
        assertEquals("The Ranveer Show", tags.showName)
        assertEquals(MediaKind.PODCAST, tags.mediaKind)
    }

    @Test
    fun `what the file already said survives the overlay`() {
        val fromFile = VideoTags(
            mediaKind = MediaKind.PODCAST,
            title = "Ep 412 — sleep and recovery",
        )
        val merged = fromFile.overlaidWith(ITunes.parsePodcasts(body, "IN").first().toTags())
        assertEquals("Ep 412 — sleep and recovery", merged.title)
        assertEquals("The Ranveer Show", merged.showName)
    }
}

/** The crew, pulled out of a list that also contains the caterers. */
class CreditsParsingTest {

    @Test
    fun `directors and producers are named and the cast is cut short`() {
        val body = """
            {"cast":[
              {"name":"Nicolas Cage"},{"name":"Téa Leoni"},{"name":"Don Cheadle"},
              {"name":"Jeremy Piven"},{"name":"Saul Rubinek"},{"name":"Josef Sommer"},
              {"name":"Makenzie Vega"},{"name":"Jake Milkovich"}
             ],
             "crew":[
              {"job":"Director","name":"Brett Ratner"},
              {"job":"Producer","name":"Marc Abraham"},
              {"job":"Executive Producer","name":"Armyan Bernstein"},
              {"job":"Catering","name":"Somebody Else"},
              {"job":"Producer","name":"Marc Abraham"}
             ]}
        """.trimIndent()
        val credits = Tmdb.parseCredits(body)
        assertEquals("Brett Ratner", credits.director)
        assertEquals("Marc Abraham, Armyan Bernstein", credits.producers)
        assertEquals(
            "Nicolas Cage, Téa Leoni, Don Cheadle, Jeremy Piven, Saul Rubinek, Josef Sommer",
            credits.cast,
        )
    }

    @Test
    fun `a film with no crew listed says nothing rather than nothing found`() {
        val credits = Tmdb.parseCredits("""{"cast":[],"crew":[]}""")
        assertNull(credits.director)
        assertNull(credits.producers)
        assertNull(credits.cast)
    }
}
