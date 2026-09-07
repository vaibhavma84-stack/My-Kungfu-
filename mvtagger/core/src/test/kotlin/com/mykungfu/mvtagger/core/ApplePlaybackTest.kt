package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePlaybackTest {

    private val fourK = 3840
    private val hd = 1280

    @Test
    fun `what the chip decodes raises nothing`() {
        val h264 = ApplePlayback.check("video/avc", "audio/mp4a-latm", longEdge = fourK)
        assertEquals(ApplePlayback.Level.HARDWARE, h264.level)
        assertNull(h264.warning)

        val hevc = ApplePlayback.check("video/hevc", "audio/eac3", longEdge = fourK)
        assertEquals(ApplePlayback.Level.HARDWARE, hevc.level)
        assertNull(hevc.warning)
    }

    /**
     * The complaint this was built for: 4K files that play in VLC and struggle
     * in Infuse. VP9 is what a 4K download usually is, and no iPad has silicon
     * for it.
     */
    @Test
    fun `a 4K VP9 file is flagged`() {
        val v = ApplePlayback.check("video/x-vnd.on2.vp9", "audio/opus", longEdge = fourK)
        assertEquals(ApplePlayback.Level.SOFTWARE, v.level)
        assertEquals("VP9", v.warning)
        assertTrue(v.reason, v.reason.contains("stutter"))
    }

    /** Ten-bit H.264 is the other one, and it is invisible from the MIME type. */
    @Test
    fun `ten-bit H264 is flagged and ten-bit HEVC is not`() {
        val hi10 = ApplePlayback.check("video/avc", "audio/mp4a-latm", tenBit = true, longEdge = fourK)
        assertEquals("10-bit H.264", hi10.warning)

        // Main 10 HEVC decodes in hardware and must not be confused with it.
        val hevc10 = ApplePlayback.check("video/hevc", "audio/mp4a-latm", tenBit = true, longEdge = fourK)
        assertEquals(ApplePlayback.Level.HARDWARE, hevc10.level)
        assertNull(hevc10.warning)
    }

    /**
     * Software decoding only matters at a size where it hurts. A badge that is
     * always lit is a badge nobody reads.
     */
    @Test
    fun `the same codec small enough is not worth mentioning`() {
        val small = ApplePlayback.check("video/x-vnd.on2.vp9", "audio/opus", longEdge = hd)
        assertEquals(ApplePlayback.Level.SOFTWARE, small.level)
        assertNull("a 720p file needs no warning", small.warning)
        assertTrue(small.reason, small.reason.contains("no trouble"))
    }

    @Test
    fun `AV1 is reported as the risk it is on most iPads`() {
        val av1 = ApplePlayback.check("video/av01", "audio/mp4a-latm", longEdge = fourK)
        assertEquals("AV1", av1.warning)
        assertTrue(av1.reason, av1.reason.contains("A17 Pro"))
    }

    /** Audio only speaks when the video had nothing to say. */
    @Test
    fun `bad audio on good video is mentioned, and never twice`() {
        val dts = ApplePlayback.check("video/hevc", "audio/vnd.dts", longEdge = fourK)
        assertNotNull(dts.warning)
        assertTrue(dts.warning!!, dts.warning.contains("DTS"))

        val both = ApplePlayback.check("video/x-vnd.on2.vp9", "audio/vnd.dts", longEdge = fourK)
        assertEquals("only the video is named", "VP9", both.warning)
    }

    @Test
    fun `the audio an iPad carries natively raises nothing`() {
        for (audio in listOf("audio/mp4a-latm", "audio/ac3", "audio/eac3", "audio/mpeg")) {
            assertNull(audio, ApplePlayback.check("video/avc", audio, longEdge = fourK).warning)
        }
    }

    @Test
    fun `codecs are named the way a person would say them`() {
        assertEquals("H.265", ApplePlayback.friendly("video/hevc"))
        assertEquals("DTS", ApplePlayback.friendly("audio/vnd.dts"))
        assertEquals("unknown", ApplePlayback.friendly(null))
    }
}
