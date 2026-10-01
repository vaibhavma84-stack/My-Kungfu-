package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipsTest {

    @Test
    fun `an ordinary piece is allowed`() {
        assertNull(Clips.refuse(10_000, 30_000, 180_000))
    }

    @Test
    fun `a piece that ends before it starts is not`() {
        assertNotNull(Clips.refuse(30_000, 10_000))
        assertNotNull(Clips.refuse(30_000, 30_000))
    }

    @Test
    fun `a mis-tap is not a clip`() {
        assertNotNull(Clips.refuse(10_000, 10_200))
        assertNull(Clips.refuse(10_000, 10_600))
    }

    @Test
    fun `a start past the end of the file is refused, but only when the length is known`() {
        assertNotNull(Clips.refuse(200_000, 210_000, 180_000))
        assertNull(Clips.refuse(200_000, 210_000, 0))
    }

    @Test
    fun `the name says where the piece came from`() {
        assertEquals(
            "PERFECT 01m02s to 01m40s.mp4",
            Clips.fileName("PERFECT", 62_240, 100_000),
        )
    }

    @Test
    fun `hours appear only when there are hours`() {
        assertEquals("00m05s", Clips.stamp(5_000))
        assertEquals("1h04m11s", Clips.stamp(3_851_000))
    }

    @Test
    fun `a title that would not survive a filesystem is made to`() {
        val name = Clips.fileName("AC/DC: Live?", 0, 5_000)
        assertTrue(name, name.none { it in "/\\:?*\"<>|" })
        assertTrue(name, name.endsWith(".mp4"))
    }

    @Test
    fun `something with no title still gets a name`() {
        assertEquals("Clip 00m00s to 00m05s.mp4", Clips.fileName(null, 0, 5_000))
    }
}
