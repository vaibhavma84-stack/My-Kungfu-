package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolutionTest {

    @Test
    fun `the ordinary sizes are named the ordinary way`() {
        assertEquals("8K", Resolution.label(7680, 4320))
        assertEquals("4K", Resolution.label(3840, 2160))
        assertEquals("2K", Resolution.label(2560, 1440))
        assertEquals("1080p", Resolution.label(1920, 1080))
        assertEquals("720p", Resolution.label(1280, 720))
        assertEquals("480p", Resolution.label(854, 480))
    }

    /**
     * The case that decides long-edge over height. A 4K film is routinely
     * 3840x1600; by height that is 1600 lines and would be called 2K, which is
     * wrong for most films in a collection.
     */
    @Test
    fun `a widescreen film is judged on its width, not its letterbox`() {
        assertEquals("4K", Resolution.label(3840, 1600))
        assertEquals("1080p", Resolution.label(1920, 800))
        assertEquals("4K", Resolution.label(4096, 1716))
    }

    /** Held upright, a 1080p video is still a 1080p video. */
    @Test
    fun `a video shot on a phone is not promoted for being tall`() {
        assertEquals("1080p", Resolution.label(1080, 1920))
        assertEquals("720p", Resolution.label(720, 1280))
    }

    /** Encodes get cropped by a few pixels; that is not a lower grade. */
    @Test
    fun `a little under still counts`() {
        assertEquals("4K", Resolution.label(3808, 2144))
        assertEquals("1080p", Resolution.label(1912, 1072))
    }

    @Test
    fun `an unreadable size says nothing rather than guessing`() {
        assertNull(Resolution.label(null, null))
        assertNull(Resolution.label(0, 0))
        assertNull(Resolution.exact(null, 1080))
        assertNull(Resolution.exact(1920, 0))
    }

    @Test
    fun `anything smaller is standard definition`() {
        assertEquals("SD", Resolution.label(320, 240))
    }

    @Test
    fun `the exact size is written the way it is spoken`() {
        assertEquals("1920×1080", Resolution.exact(1920, 1080))
    }
}
