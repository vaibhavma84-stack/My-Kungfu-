package com.mykungfu.mvtagger.core

import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadReportTest {

    private val options = listOf(
        Downloads.Option("a", "360p", 360, Downloads.Container.MP4, true, true),
        Downloads.Option("b", "1080p", 1080, Downloads.Container.MP4, true, false),
        Downloads.Option("c", "AAC 128k", 0, Downloads.Container.M4A, false, true, 128),
    )

    @Test
    fun `the streams that were offered are all in it`() {
        val text = DownloadReport.of(
            "https://youtu.be/abc", "0.26.5", options,
            Downloads.bestVideo(options), "fetching", "the server answered 403",
        )
        assertTrue(text, text.contains("Streams offered: 3"))
        assertTrue(text, text.contains("Complete: 360p MP4"))
        assertTrue(text, text.contains("Video only: 1080p MP4"))
        assertTrue(text, text.contains("Sound only: AAC 128k M4A"))
        assertTrue(text, text.contains("403"))
        assertTrue(text, text.contains("Chosen: 1080p MP4 joined to AAC 128k M4A"))
    }

    @Test
    fun `an empty answer is the loudest thing in the report`() {
        // The case that says the extractor is out of date rather than that the
        // choosing went wrong, which are two entirely different repairs.
        val text = DownloadReport.of(
            "https://youtu.be/abc", "0.26.5", emptyList(), null, "asking YouTube", "nothing came back",
        )
        assertTrue(text, text.contains("Streams offered: 0"))
        assertTrue(text, text.contains("Chosen: nothing usable"))
    }
}
