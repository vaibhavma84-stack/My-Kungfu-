package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesTest {

    private val search = """
        { "data": [
          { "attributes": {
              "language": "en", "download_count": 50, "from_trusted": "false",
              "hearing_impaired": "false", "machine_translated": "true",
              "files": [ { "file_id": 111, "file_name": "machine.srt" } ] } },
          { "attributes": {
              "language": "en", "download_count": 9000, "from_trusted": "true",
              "hearing_impaired": "false", "machine_translated": "false",
              "files": [ { "file_id": 222, "file_name": "good.srt" } ] } },
          { "attributes": {
              "language": "hi", "download_count": 400, "from_trusted": "true",
              "hearing_impaired": "false", "machine_translated": "false",
              "files": [ { "file_id": 333, "file_name": "hindi.srt" } ] } }
        ] }
    """.trimIndent()

    @Test
    fun `search results are read with their file ids`() {
        val matches = OpenSubtitles.parseSearch(search)
        assertEquals(3, matches.size)
        assertEquals("111", matches[0].fileId)
        assertEquals("en", matches[0].language)
    }

    @Test
    fun `the language asked for comes first`() {
        val ranked = OpenSubtitles.rank(OpenSubtitles.parseSearch(search), listOf("hi", "en"))
        assertEquals("333", ranked.first().fileId)
    }

    @Test
    fun `a machine translation loses to one a person made`() {
        // A machine translation is worse than none: it reads as though it were
        // right until you actually follow it.
        val ranked = OpenSubtitles.rank(OpenSubtitles.parseSearch(search), listOf("en"))
        assertEquals("222", ranked.first().fileId)
    }

    @Test
    fun `an episode search says which episode`() {
        val url = OpenSubtitles.searchUrl(
            "Sacred Games", "en", MediaKind.TV_EPISODE, season = 2, episode = 4,
        )
        assertTrue(url.contains("type=episode"))
        assertTrue(url.contains("season_number=2"))
        assertTrue(url.contains("episode_number=4"))
    }

    @Test
    fun `a film search says it is a film`() {
        val url = OpenSubtitles.searchUrl("Brahmastra", "hi", MediaKind.MOVIE, year = "2022")
        assertTrue(url.contains("type=movie"))
        assertTrue(url.contains("year=2022"))
        assertTrue(!url.contains("season_number"))
    }

    @Test
    fun `the login body escapes what is put in it`() {
        val body = OpenSubtitles.loginBody("me", "pa\"ss\\word")
        val parsed = Json.parse(body)
        assertEquals("me", parsed["username"].string)
        assertEquals("pa\"ss\\word", parsed["password"].string)
    }

    @Test
    fun `the token and download link are read back`() {
        assertEquals("abc123", OpenSubtitles.parseLoginToken("""{"token":"abc123"}"""))
        assertEquals(
            "https://example.test/f.srt",
            OpenSubtitles.parseDownloadLink("""{"link":"https://example.test/f.srt"}"""),
        )
        assertNull(OpenSubtitles.parseLoginToken("""{"status":401}"""))
    }

    @Test
    fun `an empty response is not an error`() {
        assertTrue(OpenSubtitles.parseSearch("""{"data":[]}""").isEmpty())
        assertTrue(OpenSubtitles.parseSearch("not json at all").isEmpty())
    }
}
