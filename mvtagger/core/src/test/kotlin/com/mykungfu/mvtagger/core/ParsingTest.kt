package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameParserTest {

    @Test
    fun `artist and title from the usual western shape`() {
        val p = FilenameParser.parse("Adele - Hello (Official Music Video) [1080p].mp4")
        assertEquals("Adele", p.artist)
        assertEquals("Hello", p.title)
        assertEquals("Adele Hello", p.query)
    }

    @Test
    fun `a leading track number is taken off`() {
        val p = FilenameParser.parse("03. Coldplay - Yellow.mkv")
        assertEquals(3, p.trackNumber)
        assertEquals("Coldplay", p.artist)
        assertEquals("Yellow", p.title)
    }

    @Test
    fun `a leading year is not mistaken for a track number`() {
        val p = FilenameParser.parse("2013 - Get Lucky.mp4")
        assertNull(p.trackNumber)
    }

    @Test
    fun `the film convention separates the song from the film`() {
        val p = FilenameParser.parse(
            "Kesariya – Brahmastra | Ranbir Kapoor | Arijit Singh | Pritam.mp4"
        )
        // The song and the film are joined by a dash inside the first field.
        // Glued together they find nothing, which is what made Hindi tracks
        // miss.
        assertEquals("Kesariya", p.title)
        assertEquals("Brahmastra", p.album)
        assertTrue("the film should be searched for too, was " + p.queries,
            p.queries.any { it.contains("Kesariya") && it.contains("Brahmastra") })
    }

    @Test
    fun `an actor is never taken for the singer`() {
        val p = FilenameParser.parse(
            "Kesariya – Brahmastra | Ranbir Kapoor | Arijit Singh | Pritam.mp4"
        )
        // Ranbir Kapoor is the actor. Claiming him as the artist poisons the
        // search and gets written to the file if the lookup then fails, so
        // nothing is claimed at all -- the names stay as extras for scoring.
        assertNull(p.artist)
        assertTrue("Arijit Singh" in p.extras)
        assertTrue("Pritam" in p.extras)
    }

    @Test
    fun `the film is tried even when it is a separate pipe field`() {
        val p = FilenameParser.parse("Tum Hi Ho | Aashiqui 2 | Arijit Singh.mp4")
        assertEquals("Tum Hi Ho", p.title)
        assertTrue("should try the song with the film, was " + p.queries,
            p.queries.any { it.contains("Tum Hi Ho") && it.contains("Aashiqui 2") })
    }

    @Test
    fun `a long film credit still finds the song and the film`() {
        val p = FilenameParser.parse(
            "Chaleya - Jawan | Shah Rukh Khan | Nayanthara | Anirudh | Arijit Singh.mkv"
        )
        assertEquals("Chaleya", p.title)
        assertEquals("Jawan", p.album)
    }

    @Test
    fun `record labels and channels are not searched for`() {
        val p = FilenameParser.parse("Kesariya Full Video | T-Series | 4K.mp4")
        val all = p.queries.joinToString(" ").lowercase()
        assertTrue("label leaked into the query: " + p.queries, "t-series" !in all)
        assertTrue("resolution leaked into the query: " + p.queries, "4k" !in all)
        assertTrue("Kesariya" in p.queries.joinToString(" "))
    }

    @Test
    fun `western names still resolve to one good query`() {
        val p = FilenameParser.parse("Adele - Hello (Official Music Video).mp4")
        assertEquals("Adele Hello", p.queries.first())
    }

    @Test
    fun `underscores become spaces only when there are none already`() {
        val p = FilenameParser.parse("Tum_Hi_Ho_-_Aashiqui_2_[YE7VzlLtp-4].webm")
        assertNotNull(p.title)
        assertTrue("query should be readable, was '${p.query}'", "Tum Hi Ho" in p.query)
        assertTrue("the video id should be gone", "YE7VzlLtp" !in p.query)
    }

    @Test
    fun `noise words go but real words stay`() {
        val p = FilenameParser.parse("Shape of You - Official Video 4K.mp4")
        assertTrue("of should survive", "of" in p.query.lowercase())
        assertTrue("4K should be gone, was '${p.query}'", "4k" !in p.query.lowercase())
    }

    @Test
    fun `a devanagari filename is detected as hindi`() {
        val p = FilenameParser.parse("अरिजीत सिंह - केसरिया.mp4")
        assertEquals("hi", p.language)
    }

    @Test
    fun `extensions are split off without eating part of the name`() {
        assertEquals("Vol 2 Track", FilenameParser.stripExtension("Vol 2 Track.mp4"))
        assertEquals("mp4", FilenameParser.extensionOf("Vol 2 Track.mp4"))
        assertEquals("No extension here", FilenameParser.stripExtension("No extension here"))
        assertEquals("", FilenameParser.extensionOf("No extension here"))
    }
}

class MediaClassifierTest {

    @Test
    fun `an SxxExx name is an episode`() {
        val m = MediaClassifier.classify("Game.of.Thrones.S01E02.1080p.BluRay.x264.mkv")
        assertEquals(MediaKind.TV_EPISODE, m.kind)
        assertEquals("Game of Thrones", m.name)
        assertEquals(1, m.season)
        assertEquals(2, m.episode)
    }

    @Test
    fun `the 1x02 form works too`() {
        val m = MediaClassifier.classify("Friends 3x07 The One With the Race Car Bed.mp4")
        assertEquals(MediaKind.TV_EPISODE, m.kind)
        assertEquals("Friends", m.name)
        assertEquals(3, m.season)
        assertEquals(7, m.episode)
    }

    @Test
    fun `spelled out seasons work`() {
        val m = MediaClassifier.classify("Sacred Games Season 2 Episode 4.mp4")
        assertEquals(MediaKind.TV_EPISODE, m.kind)
        assertEquals(2, m.season)
        assertEquals(4, m.episode)
    }

    @Test
    fun `a release name with a year is a movie`() {
        val m = MediaClassifier.classify("Brahmastra.Part.One.2022.1080p.WEB-DL.x264.AAC.mkv")
        assertEquals(MediaKind.MOVIE, m.kind)
        assertEquals("2022", m.year)
        assertTrue("name was '${m.name}'", m.name.startsWith("Brahmastra"))
    }

    @Test
    fun `a song with a year in it is not mistaken for a movie`() {
        // One stray year and no release-group noise: still a music video.
        val m = MediaClassifier.classify("Arijit Singh - Kesariya 2022.mp4")
        assertEquals(MediaKind.MUSIC_VIDEO, m.kind)
    }

    @Test
    fun `an ordinary music video stays a music video`() {
        assertEquals(
            MediaKind.MUSIC_VIDEO,
            MediaClassifier.classify("Adele - Hello (Official Music Video).mp4").kind,
        )
    }
}

class RenameTemplateTest {

    private val tags = VideoTags(
        title = "Kesariya",
        artist = "Arijit Singh",
        album = "Brahmastra",
        date = "2022-07-17",
        trackNumber = 3,
    )

    @Test
    fun `tokens are filled in`() {
        assertEquals("Arijit Singh - Kesariya", RenameTemplate.baseName("{artist} - {title}", tags))
    }

    @Test
    fun `an optional section appears when its tokens are known`() {
        assertEquals(
            "Arijit Singh - Kesariya (2022)",
            RenameTemplate.baseName("{artist} - {title}[ ({year})]", tags),
        )
    }

    @Test
    fun `an optional section vanishes entirely when a token is missing`() {
        assertEquals(
            "Arijit Singh - Kesariya",
            RenameTemplate.baseName("{artist} - {title}[ ({year})]", tags.copy(date = null)),
        )
    }

    @Test
    fun `illegal characters are replaced, not left to fail at write time`() {
        val awkward = tags.copy(title = "AC/DC: Back?  In* Black")
        val name = RenameTemplate.baseName("{title}", awkward)!!
        assertTrue("still contains an illegal character: $name",
            name.none { it in "/\\:*?\"<>|" })
        assertTrue(name.isNotBlank())
    }

    @Test
    fun `non-latin names are kept rather than transliterated`() {
        assertEquals("केसरिया", RenameTemplate.baseName("{title}", VideoTags(title = "केसरिया")))
    }

    @Test
    fun `a template that resolves to nothing returns null`() {
        assertNull(RenameTemplate.baseName("{artist}", VideoTags()))
    }

    @Test
    fun `episode templates pad the numbers`() {
        val ep = VideoTags(
            mediaKind = MediaKind.TV_EPISODE,
            title = "Winter Is Coming",
            showName = "Game of Thrones",
            seasonNumber = 1,
            episodeNumber = 2,
        )
        assertEquals(
            "Game of Thrones - S01E02 - Winter Is Coming",
            RenameTemplate.baseName(RenameTemplate.defaultFor(MediaKind.TV_EPISODE), ep),
        )
    }

    @Test
    fun `the extension is put back on`() {
        assertEquals(
            "Arijit Singh - Kesariya.mp4",
            RenameTemplate.fileName("{artist} - {title}", tags, "mp4"),
        )
    }

    @Test
    fun `a duplicate gets a numbered suffix before the extension`() {
        assertEquals("Song (2).mp4", RenameTemplate.withSuffix("Song.mp4", 2))
    }
}

class OrganiserTest {

    @Test
    fun `music videos are filed under the artist`() {
        val tags = VideoTags(artist = "Arijit Singh", title = "Kesariya")
        assertEquals(
            listOf("Music Videos", "Arijit Singh"),
            Organiser.folder(Organiser.MUSIC_VIDEOS, tags),
        )
    }

    @Test
    fun `episodes are filed under show and season`() {
        val tags = VideoTags(
            mediaKind = MediaKind.TV_EPISODE,
            showName = "Sacred Games", seasonNumber = 2, episodeNumber = 4,
            title = "Matsya",
        )
        assertEquals(
            listOf("TV Shows", "Sacred Games", "Season 02"),
            Organiser.folder(Organiser.TV_EPISODES, tags),
        )
    }

    @Test
    fun `a missing season does not create an empty folder`() {
        val tags = VideoTags(mediaKind = MediaKind.TV_EPISODE, showName = "Unknown Show")
        assertEquals(
            listOf("TV Shows", "Unknown Show"),
            Organiser.folder(Organiser.TV_EPISODES, tags),
        )
    }

    @Test
    fun `the full destination path reads correctly`() {
        val tags = VideoTags(
            mediaKind = MediaKind.MOVIE, title = "Brahmastra", date = "2022-09-09",
        )
        assertEquals(
            "Movies/Brahmastra (2022)/Brahmastra (2022).mkv",
            Organiser.previewPath(
                Organiser.MOVIES, RenameTemplate.defaultFor(MediaKind.MOVIE), tags, "mkv",
            ),
        )
    }
}

class LanguagesTest {

    @Test
    fun `three letter codes fold to two`() {
        assertEquals("hi", Languages.normalise("hin"))
        assertEquals("ta", Languages.normalise("tam"))
        assertEquals("en", Languages.normalise("eng"))
        assertEquals("fr", Languages.normalise("fre"))
    }

    @Test
    fun `names and regional variants fold too`() {
        assertEquals("hi", Languages.normalise("Hindi"))
        assertEquals("en", Languages.normalise("en-GB"))
        assertNull(Languages.normalise("klingon"))
    }

    @Test
    fun `scripts map to their likeliest language`() {
        assertEquals("hi", Languages.fromScript(TextScript.dominant("केसरिया")))
        assertEquals("ta", Languages.fromScript(TextScript.dominant("தமிழ்")))
        assertEquals("pa", Languages.fromScript(TextScript.dominant("ਪੰਜਾਬੀ")))
        assertNull(Languages.fromScript(TextScript.dominant("Hello")))
    }

    @Test
    fun `a mixed title is judged by the bulk of its letters`() {
        assertEquals(Script.DEVANAGARI, TextScript.dominant("केसरिया (Official Video)"))
    }

    @Test
    fun `a romanised hindi title falls back to the storefront`() {
        assertEquals("hi", Languages.guess(title = "Kesariya", storefront = "IN"))
        assertEquals("en", Languages.guess(title = "Hello", storefront = "US"))
    }

    @Test
    fun `what the source declared always wins`() {
        assertEquals("ta", Languages.guess(declared = "tam", title = "Hello", storefront = "US"))
    }
}

class MatchingTest {

    private val parsed = FilenameParser.parse("Arijit Singh - Kesariya.mp4")

    private fun candidate(
        title: String, artist: String?, durationMs: Int? = null, album: String? = null,
    ) = Candidate(
        source = "iTunes", id = title, title = title, artist = artist,
        album = album, durationMs = durationMs,
    )

    @Test
    fun `the right song outranks a wrong one`() {
        val ranked = Matching.rank(
            listOf(
                candidate("Something Else", "Another Artist"),
                candidate("Kesariya", "Arijit Singh"),
            ),
            parsed,
        )
        assertEquals("Kesariya", ranked.first().candidate.title)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `a matching duration is decisive between two similar entries`() {
        val ranked = Matching.rank(
            listOf(
                candidate("Kesariya", "Arijit Singh", durationMs = 400_000),
                candidate("Kesariya", "Arijit Singh", durationMs = 268_000),
            ),
            parsed,
            durationMs = 268_500,
        )
        assertEquals(268_000, ranked.first().candidate.durationMs)
        assertTrue(ranked.first().reasons.any { "length" in it })
    }

    @Test
    fun `the reasons say why a match won`() {
        val ranked = Matching.rank(listOf(candidate("Kesariya", "Arijit Singh")), parsed)
        assertTrue("no reasons given", ranked.first().reasons.isNotEmpty())
    }

    @Test
    fun `accents and punctuation do not block a match`() {
        assertEquals(1.0, Matching.tokenOverlap("Beyoncé", "Beyonce"), 0.001)
        assertEquals(1.0, Matching.tokenOverlap("Don't Stop", "Dont Stop"), 0.001)
    }

    @Test
    fun `the film convention still matches when the parser guessed the halves round the wrong way`() {
        // Parsed as artist="Kesariya", title="Brahmastra"; the real answer is
        // the other way round, and scoring tries both.
        val wrongWayRound = FilenameParser.parse("Kesariya - Brahmastra.mp4")
        val ranked = Matching.rank(
            listOf(candidate("Kesariya", "Arijit Singh", album = "Brahmastra")),
            wrongWayRound,
        )
        assertTrue("score was ${ranked.first().score}", ranked.first().score > 0.4)
    }
}

class ArtworkPlanTest {

    private val songEntry = Candidate(
        source = "iTunes", id = "1", title = "Kesariya", artist = "Arijit Singh",
        album = "Brahmastra (Original Motion Picture Soundtrack)",
        artworkUrls = listOf("https://example.test/album-cover.jpg"),
        kind = "song", language = "hi",
    )
    private val videoEntry = Candidate(
        source = "iTunes", id = "2", title = "Kesariya", artist = "Arijit Singh",
        artworkUrls = listOf("https://example.test/video-still.jpg"),
        kind = "musicVideo", language = "hi",
    )

    @Test
    fun `the album cover is preferred over a frame from the video`() {
        val urls = ArtworkPlan.urls(videoEntry, listOf(songEntry), language = "hi")
        assertEquals("https://example.test/album-cover.jpg", urls.first())
    }

    @Test
    fun `a film poster from the film lookup comes first for hindi`() {
        val urls = ArtworkPlan.urls(
            videoEntry, listOf(songEntry), language = "hi",
            tmdbPosterUrls = listOf("https://example.test/poster.jpg"),
        )
        assertEquals("https://example.test/poster.jpg", urls.first())
    }

    @Test
    fun `for english the album front is used and no film lookup is involved`() {
        val english = songEntry.copy(album = "25", language = "en")
        val urls = ArtworkPlan.urls(
            videoEntry.copy(language = "en"), listOf(english), language = "en",
            tmdbPosterUrls = listOf("https://example.test/poster.jpg"),
        )
        assertEquals("https://example.test/album-cover.jpg", urls.first())
    }

    @Test
    fun `soundtracks are recognised by name`() {
        assertTrue(ArtworkPlan.looksLikeSoundtrack("Brahmastra (Original Motion Picture Soundtrack)"))
        assertTrue(!ArtworkPlan.looksLikeSoundtrack("25"))
    }
}

class SidecarTest {

    @Test
    fun `only mp4 family files can hold embedded tags`() {
        assertTrue(Sidecar.canEmbed("song.mp4"))
        assertTrue(Sidecar.canEmbed("song.m4v"))
        assertTrue(Sidecar.canEmbed("song.MOV"))
        assertTrue(!Sidecar.canEmbed("song.mkv"))
        assertTrue(!Sidecar.canEmbed("song.webm"))
    }

    @Test
    fun `the json sidecar is valid json and keeps devanagari readable`() {
        val json = Sidecar.json(
            VideoTags(title = "केसरिया", artist = "Arijit Singh", trackNumber = 3),
            fileName = "test.mkv",
        )
        val parsed = Json.parse(json)
        assertEquals("केसरिया", parsed["title"].string)
        assertEquals("Arijit Singh", parsed["artist"].string)
        assertEquals(3, parsed["trackNumber"].int)
    }

    @Test
    fun `quotes and newlines in a title do not break the sidecar`() {
        val json = Sidecar.json(VideoTags(title = "He said \"hi\"\nthen left"))
        assertEquals("He said \"hi\"\nthen left", Json.parse(json)["title"].string)
    }

    @Test
    fun `the lrc file carries the timestamps and a header`() {
        val lrc = Sidecar.lrc(
            VideoTags(title = "Kesariya", artist = "Arijit Singh", syncedLyrics = "[00:12.30]Line")
        )!!
        assertTrue(lrc.contains("[ti:Kesariya]"))
        assertTrue(lrc.contains("[ar:Arijit Singh]"))
        assertTrue(lrc.contains("[00:12.30]Line"))
    }

    @Test
    fun `no lyrics means no lrc file`() {
        assertNull(Sidecar.lrc(VideoTags(title = "Kesariya")))
    }
}
