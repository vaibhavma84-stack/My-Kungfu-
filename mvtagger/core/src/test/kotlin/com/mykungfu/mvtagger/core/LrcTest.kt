package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {

    @Test
    fun `times and words come back out`() {
        val song = Lrc.parse(
            """
            [00:12.30]Tum hi ho
            [00:15.10]Ab tum hi ho
            """.trimIndent()
        )
        assertNotNull(song)
        assertTrue(song!!.isSynced)
        assertEquals(2, song.lines.size)
        assertEquals(12_300L, song.lines[0].atMs)
        assertEquals("Tum hi ho", song.lines[0].text)
        assertEquals(15_100L, song.lines[1].atMs)
    }

    @Test
    fun `a chorus sung twice carries two times and appears twice`() {
        val song = Lrc.parse("[00:15.10][01:42.10]Ab tum hi ho")!!
        assertEquals(2, song.lines.size)
        assertEquals(15_100L, song.lines[0].atMs)
        assertEquals(102_100L, song.lines[1].atMs)
        assertEquals(song.lines[0].text, song.lines[1].text)
    }

    @Test
    fun `metadata about the song is not words in it`() {
        val song = Lrc.parse(
            """
            [ar:Arijit Singh]
            [ti:Tum Hi Ho]
            [00:12.30]Tum hi ho
            """.trimIndent()
        )!!
        assertEquals(1, song.lines.size)
        assertEquals("Tum hi ho", song.lines[0].text)
    }

    @Test
    fun `an offset moves every line by the same amount`() {
        val song = Lrc.parse("[offset:+500]\n[00:10.00]One")!!
        assertEquals(10_500L, song.lines[0].atMs)
    }

    @Test
    fun `hundredths and milliseconds are told apart`() {
        assertEquals(10_250L, Lrc.parse("[00:10.25]x")!!.lines[0].atMs)
        assertEquals(10_025L, Lrc.parse("[00:10.025]x")!!.lines[0].atMs)
        assertEquals(10_000L, Lrc.parse("[00:10]x")!!.lines[0].atMs)
    }

    @Test
    fun `words with no times are still words`() {
        val song = Lrc.parse("Tum hi ho\nAb tum hi ho")!!
        assertFalse(song.isSynced)
        assertEquals("Tum hi ho\nAb tum hi ho", song.plain)
    }

    @Test
    fun `a synced song can still be read straight through`() {
        val song = Lrc.parse("[00:01.00]One\n[00:02.00]Two")!!
        assertEquals("One\nTwo", song.plain)
    }

    @Test
    fun `nothing worth showing is nothing`() {
        assertNull(Lrc.parse(null))
        assertNull(Lrc.parse("   "))
        assertNull(Lrc.parse("[ar:Somebody]"))
    }

    @Test
    fun `a line stays up until the next one arrives`() {
        val lines = Lrc.parse("[00:10.00]One\n[00:20.00]Two\n[00:30.00]Three")!!.lines
        assertEquals(-1, Lrc.indexAt(lines, 0))
        assertEquals(-1, Lrc.indexAt(lines, 9_999))
        assertEquals(0, Lrc.indexAt(lines, 10_000))
        assertEquals(0, Lrc.indexAt(lines, 19_999))
        assertEquals(1, Lrc.indexAt(lines, 20_000))
        assertEquals(2, Lrc.indexAt(lines, 600_000))
    }

    @Test
    fun `a gap in the singing clears the screen rather than holding a line`() {
        // A timestamp with nothing after it is how the format says "silence
        // from here", and dropping it would leave the last line up through an
        // instrumental break.
        val song = Lrc.parse("[00:10.00]One\n[00:14.00]\n[00:30.00]Two")!!
        assertEquals(3, song.lines.size)
        assertEquals("", song.lines[1].text)
        assertEquals(1, Lrc.indexAt(song.lines, 20_000))
    }
}
