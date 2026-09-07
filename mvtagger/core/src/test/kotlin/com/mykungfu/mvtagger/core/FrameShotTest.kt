package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameShotTest {

    @Test
    fun `zoom is a ratio and stays within reach`() {
        assertEquals(2f, FrameShot.zoom(1f, 2f), 0.001f)
        assertEquals(FrameShot.MAX_ZOOM, FrameShot.zoom(6f, 4f), 0.001f)
        assertEquals(FrameShot.MIN_ZOOM, FrameShot.zoom(2f, 0.1f), 0.001f)
    }

    @Test
    fun `at life size the picture cannot be pushed off the screen`() {
        assertEquals(0f, FrameShot.pan(500f, 1080f, 1f), 0.001f)
        assertEquals(0f, FrameShot.pan(-500f, 1080f, 1f), 0.001f)
    }

    @Test
    fun `zoomed in it may move by half the hidden part`() {
        // Twice life size on a 1000-wide screen hides 1000; half of that is
        // how far either edge can travel before it comes into view.
        assertEquals(500f, FrameShot.pan(900f, 1000f, 2f), 0.001f)
        assertEquals(-500f, FrameShot.pan(-900f, 1000f, 2f), 0.001f)
        assertEquals(120f, FrameShot.pan(120f, 1000f, 2f), 0.001f)
    }

    @Test
    fun `a span nobody has measured yet moves nowhere`() {
        assertEquals(0f, FrameShot.pan(80f, 0f, 4f), 0.001f)
    }

    @Test
    fun `a stamp reads as a time and sorts as one`() {
        assertEquals("01m02s240", FrameShot.stamp(62_240))
        assertEquals("00m00s000", FrameShot.stamp(0))
        assertEquals("1h04m11s000", FrameShot.stamp(3_851_000))
        assertEquals("00m00s000", FrameShot.stamp(-5))
    }

    @Test
    fun `two frames a frame apart are two files`() {
        val a = FrameShot.fileName("PERFECT", 62_240)
        val b = FrameShot.fileName("PERFECT", 62_280)
        assertEquals("PERFECT 01m02s240.jpg", a)
        assertNotEquals(a, b)
    }

    @Test
    fun `a title that would not survive a filesystem is made to`() {
        val name = FrameShot.fileName("AC/DC: Live?", 1000)
        assertTrue(name, name.none { it in "/\\:?*\"<>|" })
        assertTrue(name, name.endsWith("00m01s000.jpg"))
    }

    @Test
    fun `something with no title still gets saved`() {
        assertEquals("Frame 00m01s000.jpg", FrameShot.fileName("   ", 1000))
        assertEquals("Frame 00m01s000.jpg", FrameShot.fileName(null, 1000))
    }
}
