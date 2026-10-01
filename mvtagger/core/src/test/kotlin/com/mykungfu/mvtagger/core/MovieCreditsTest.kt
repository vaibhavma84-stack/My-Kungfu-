package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieCreditsTest {

    private val film = VideoTags(
        mediaKind = MediaKind.MOVIE,
        title = "The Family Man",
        director = "Brett Ratner",
        producers = "Marc Abraham, Tony Ludwig",
        cast = "Nicolas Cage, Téa Leoni, Don Cheadle",
        studio = "Universal Pictures",
    )

    @Test
    fun `what is written comes back`() {
        val back = MovieCredits.read(MovieCredits.plist(film))!!
        assertEquals("Brett Ratner", back.director)
        assertEquals("Marc Abraham, Tony Ludwig", back.producers)
        assertEquals("Nicolas Cage, Téa Leoni, Don Cheadle", back.cast)
        assertEquals("Universal Pictures", back.studio)
    }

    @Test
    fun `a film with nothing known writes nothing`() {
        // An empty directors array would be a claim that the film had none.
        assertNull(MovieCredits.plist(VideoTags(mediaKind = MediaKind.MOVIE, title = "x")))
        assertNull(MovieCredits.read(null))
        assertNull(MovieCredits.read("<plist><dict></dict></plist>"))
    }

    @Test
    fun `only the roles that are known appear`() {
        val plist = MovieCredits.plist(VideoTags(director = "Anurag Kashyap"))!!
        assertTrue(plist, plist.contains("directors"))
        assertTrue(plist, !plist.contains("<key>cast</key>"))
        assertTrue(plist, !plist.contains("<key>producers</key>"))
    }

    @Test
    fun `an ampersand in a company name does not break the file`() {
        val plist = MovieCredits.plist(VideoTags(studio = "Ruby & Sons <Films>"))!!
        assertTrue(plist, !plist.contains("Ruby & Sons"))
        assertEquals("Ruby & Sons <Films>", MovieCredits.read(plist)!!.studio)
    }

    @Test
    fun `what another tool wrote is read the same way`() {
        // The shape iTunes itself produces, tabs and all.
        val theirs = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
            	<key>cast</key>
            	<array>
            		<dict><key>name</key><string>Shah Rukh Khan</string></dict>
            		<dict><key>name</key><string>Deepika Padukone</string></dict>
            	</array>
            	<key>directors</key>
            	<array>
            		<dict><key>name</key><string>Siddharth Anand</string></dict>
            	</array>
            </dict>
            </plist>
        """.trimIndent()
        val back = MovieCredits.read(theirs)!!
        assertEquals("Shah Rukh Khan, Deepika Padukone", back.cast)
        assertEquals("Siddharth Anand", back.director)
        assertNull(back.studio)
    }
}

/** The credits have to survive the whole write-and-read of a real MP4. */
class CreditsInFileTest {

    @Test
    fun `a film keeps its crew through a round trip`() {
        val tagged = TestMp4.writeToBytes(
            TestMp4.build().bytes,
            VideoTags(
                mediaKind = MediaKind.MOVIE,
                title = "The Family Man",
                date = "2000-12-22",
                director = "Brett Ratner",
                producers = "Marc Abraham",
                cast = "Nicolas Cage, Téa Leoni",
                studio = "Universal Pictures",
            ),
        )
        val back = TestMp4.readTags(tagged)
        assertEquals("Brett Ratner", back.director)
        assertEquals("Marc Abraham", back.producers)
        assertEquals("Nicolas Cage, Téa Leoni", back.cast)
        assertEquals("Universal Pictures", back.studio)
        assertEquals(MediaKind.MOVIE, back.mediaKind)
    }
}
