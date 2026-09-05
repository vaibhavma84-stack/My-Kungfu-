package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsTest {

    private fun video(
        label: String,
        height: Int,
        container: Downloads.Container = Downloads.Container.MP4,
        withSound: Boolean = false,
    ) = Downloads.Option(label, label, height, container, hasVideo = true, hasAudio = withSound)

    private fun sound(
        label: String,
        container: Downloads.Container,
        bitrate: Int,
    ) = Downloads.Option(
        label, label, 0, container, hasVideo = false, hasAudio = true, bitrate = bitrate,
    )

    @Test
    fun `a complete MP4 is taken as it is when nothing better exists`() {
        val choice = Downloads.bestVideo(
            listOf(video("720p", 720, withSound = true), video("360p", 360, withSound = true))
        )!!
        assertEquals("720p", choice.video?.label)
        assertFalse(choice.needsJoining)
        assertNull(choice.warning)
    }

    @Test
    fun `a better picture is worth joining two streams for`() {
        val choice = Downloads.bestVideo(
            listOf(
                video("720p", 720, withSound = true),
                video("1080p", 1080),
                sound("m4a", Downloads.Container.M4A, 128),
            )
        )!!
        assertEquals("1080p", choice.video?.label)
        assertTrue(choice.needsJoining)
        assertEquals("m4a", choice.audio?.label)
    }

    @Test
    fun `joining is not worth it when the whole file is just as big`() {
        val choice = Downloads.bestVideo(
            listOf(
                video("1080p whole", 1080, withSound = true),
                video("1080p video only", 1080),
                sound("m4a", Downloads.Container.M4A, 128),
            )
        )!!
        assertEquals("1080p whole", choice.video?.label)
        assertFalse(choice.needsJoining)
    }

    @Test
    fun `the sizes this app cannot tag are left alone`() {
        val choice = Downloads.bestVideo(
            listOf(
                video("720p", 720, withSound = true),
                video("2160p", 2160, Downloads.Container.WEBM),
                sound("opus", Downloads.Container.WEBM, 160),
            )
        )!!
        assertEquals("720p", choice.video?.label)
        assertNull(choice.warning)
    }

    @Test
    fun `WebM is still offered when it is all there is, and said so`() {
        val choice = Downloads.bestVideo(
            listOf(
                video("1080p", 1080, Downloads.Container.WEBM),
                sound("opus", Downloads.Container.WEBM, 160),
            )
        )!!
        assertEquals("1080p", choice.video?.label)
        assertNotNull(choice.warning)
        assertTrue(choice.warning!!, choice.warning.contains("WebM"))
    }

    @Test
    fun `nothing offered is nothing chosen`() {
        assertNull(Downloads.bestVideo(emptyList()))
    }

    @Test
    fun `sound that can be tagged beats sound that cannot`() {
        val best = Downloads.bestAudio(
            listOf(
                sound("opus", Downloads.Container.WEBM, 160),
                sound("m4a", Downloads.Container.M4A, 128),
            )
        )!!
        assertEquals("m4a", best.label)
    }

    @Test
    fun `between two of the same kind the fuller one wins`() {
        val best = Downloads.bestAudio(
            listOf(
                sound("m4a low", Downloads.Container.M4A, 64),
                sound("m4a high", Downloads.Container.M4A, 128),
            )
        )!!
        assertEquals("m4a high", best.label)
    }

    @Test
    fun `the name is the title and the right extension`() {
        assertEquals("PERFECT.mp4", Downloads.fileName("PERFECT", Downloads.Container.MP4))
        assertEquals("PERFECT.m4a", Downloads.fileName("PERFECT", Downloads.Container.M4A))
        val awkward = Downloads.fileName("AC/DC: Live?", Downloads.Container.MP4)
        assertTrue(awkward, awkward.none { it in "/\\:?*\"<>|" })
        assertEquals("Download.mp4", Downloads.fileName("  ", Downloads.Container.MP4))
    }
}
