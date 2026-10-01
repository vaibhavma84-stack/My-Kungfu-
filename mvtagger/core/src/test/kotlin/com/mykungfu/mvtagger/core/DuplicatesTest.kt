package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {

    private fun song(title: String, artist: String?, pixels: Int = 1920, bytes: Long = 100) =
        Duplicates.Item(
            kind = MediaKind.MUSIC_VIDEO, title = title, artist = artist,
            album = null, pixels = pixels, bytes = bytes,
        )

    private fun episode(show: String, season: Int?, number: Int?, pixels: Int = 1920) =
        Duplicates.Item(
            kind = MediaKind.TV_EPISODE, title = null, artist = null, album = null,
            showName = show, season = season, episode = number, pixels = pixels,
        )

    private fun film(title: String, year: String?, pixels: Int = 1920) = Duplicates.Item(
        kind = MediaKind.MOVIE, title = title, artist = null, album = null,
        year = year, pixels = pixels,
    )

    private fun <T> find(items: List<T>, of: (T) -> Duplicates.Item) = Duplicates.find(items, of)

    @Test
    fun `the same song twice is one group`() {
        val items = listOf(
            song("Tum Hi Ho", "Arijit Singh"),
            song("tum hi ho", "arijit singh"),
            song("Kesariya", "Arijit Singh"),
        )
        val groups = find(items) { it }
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().copies.size)
    }

    /** A Devanagari title and its romanised spelling are one thing, not two. */
    @Test
    fun `a title in either script is recognised as the same song`() {
        val groups = find(listOf(song("तुम ही हो", "Arijit Singh"), song("Tum Hi Ho", "Arijit Singh"))) { it }
        assertEquals(1, groups.size)
    }

    /**
     * The distinction worth making. Two identical copies are waste; a 4K
     * alongside a 1080p is a choice already made, and saying "duplicate" about
     * it would be wrong.
     */
    @Test
    fun `a bigger copy is an upgrade, not a duplicate`() {
        val groups = find(
            listOf(
                song("Kesariya", "Arijit Singh", pixels = 1920),
                song("Kesariya", "Arijit Singh", pixels = 3840),
            )
        ) { it }
        val group = groups.single()
        assertTrue("this is an upgrade, not two of the same", group.isUpgrade)
        assertEquals("the better copy should come first", 3840, group.best.pixels)
    }

    @Test
    fun `two copies of the same size are duplicates, and the bigger file wins`() {
        val groups = find(
            listOf(
                song("Kesariya", "Arijit Singh", pixels = 1920, bytes = 100),
                song("Kesariya", "Arijit Singh", pixels = 1920, bytes = 400),
            )
        ) { it }
        val group = groups.single()
        assertTrue("same size both ways is not an upgrade", !group.isUpgrade)
        assertEquals(400L, group.best.bytes)
    }

    @Test
    fun `episodes match on series, season and number`() {
        val groups = find(
            listOf(
                episode("Westworld", 1, 10, pixels = 1920),
                episode("Westworld", 1, 10, pixels = 3840),
                episode("Westworld", 1, 9),
                episode("Westworld", 2, 10),
            )
        ) { it }
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().copies.size)
    }

    /** Films of the same name are common; the year is what tells them apart. */
    @Test
    fun `two films of one name in different years are two films`() {
        val groups = find(listOf(film("Don", "1978"), film("Don", "2006"))) { it }
        assertTrue(groups.isEmpty())
        assertEquals(1, find(listOf(film("Don", "2006"), film("Don", "2006"))) { it }.size)
    }

    /**
     * The mistake that would cost someone a file. Everything unidentified is
     * not one thing repeated, and grouping it would invite deleting it.
     */
    @Test
    fun `files that say too little are never grouped together`() {
        val groups = find(
            listOf(
                song("", null),
                song("", null),
                song("Kesariya", null),
                song("Kesariya", null),
                episode("Westworld", null, null),
                episode("Westworld", null, null),
            )
        ) { it }
        assertTrue("nothing here says enough to be called the same: " + groups.size, groups.isEmpty())
    }

    @Test
    fun `a single copy is not a group`() {
        assertTrue(find(listOf(song("Kesariya", "Arijit Singh"))) { it }.isEmpty())
    }

    @Test
    fun `groups are labelled the way the thing is spoken about`() {
        val groups = find(listOf(episode("Westworld", 1, 10), episode("Westworld", 1, 10))) { it }
        assertEquals("Westworld  S01E10", groups.single().label)
    }
}
