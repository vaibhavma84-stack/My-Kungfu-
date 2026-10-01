package com.mykungfu.mvtagger.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLinksTest {

    @Test
    fun `the real thing, in all the ways it is written`() {
        assertTrue(YouTubeLinks.isYouTube("https://m.youtube.com/"))
        assertTrue(YouTubeLinks.isYouTube("https://www.youtube.com/watch?v=abc"))
        assertTrue(YouTubeLinks.isYouTube("https://youtube.com/feed/trending"))
        assertTrue(YouTubeLinks.isYouTube("https://youtu.be/abc"))
        assertTrue(YouTubeLinks.isYouTube("https://www.youtube-nocookie.com/embed/abc"))
        assertTrue(YouTubeLinks.isYouTube("https://consent.youtube.com/m?continue=x"))
    }

    @Test
    fun `a hostname that merely contains the words is not the place`() {
        // The whole reason this is a parsed comparison rather than a contains.
        assertFalse(YouTubeLinks.isYouTube("https://youtube.com.example.net/watch?v=abc"))
        assertFalse(YouTubeLinks.isYouTube("https://notyoutube.com/watch?v=abc"))
        assertFalse(YouTubeLinks.isYouTube("https://example.net/?q=youtube.com"))
        assertFalse(YouTubeLinks.isYouTube("https://myyoutu.be/abc"))
    }

    @Test
    fun `everything else is refused`() {
        assertFalse(YouTubeLinks.isYouTube("https://google.com"))
        assertFalse(YouTubeLinks.isYouTube("https://accounts.google.com/signin"))
        assertFalse(YouTubeLinks.isYouTube(null))
        assertFalse(YouTubeLinks.isYouTube("   "))
    }

    @Test
    fun `a link that would hand the page to another app is not a web address`() {
        assertFalse(YouTubeLinks.isYouTube("intent://www.youtube.com/watch?v=abc#Intent;end"))
        assertFalse(YouTubeLinks.isYouTube("market://details?id=com.google.android.youtube"))
        assertFalse(YouTubeLinks.isYouTube("javascript:alert(1)"))
        assertFalse(YouTubeLinks.isYouTube("file:///sdcard/youtube.com.html"))
    }

    @Test
    fun `a video page is told from a list of them`() {
        assertTrue(YouTubeLinks.isWatchable("https://m.youtube.com/watch?v=abc"))
        assertTrue(YouTubeLinks.isWatchable("https://youtu.be/abc123"))
        assertTrue(YouTubeLinks.isWatchable("https://m.youtube.com/shorts/abc"))

        assertFalse(YouTubeLinks.isWatchable("https://m.youtube.com/"))
        assertFalse(YouTubeLinks.isWatchable("https://m.youtube.com/results?search_query=x"))
        assertFalse(YouTubeLinks.isWatchable("https://youtu.be/"))
        assertFalse(YouTubeLinks.isWatchable("https://example.net/watch?v=abc"))
    }
}
