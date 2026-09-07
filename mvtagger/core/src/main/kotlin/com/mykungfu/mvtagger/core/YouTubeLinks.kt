package com.mykungfu.mvtagger.core

import java.net.URI

/**
 * Which addresses count as YouTube.
 *
 * This is the whole of the browser's lock. The browser inside the app opens
 * YouTube and refuses everything else, which is what makes it a feature rather
 * than a browser nobody asked for -- and the refusing is one function, here,
 * with tests, rather than a string comparison written twice in the UI.
 *
 * ## The check that matters
 *
 * `contains("youtube.com")` is the obvious way to write this and it is wrong:
 * `youtube.com.example.net` contains it, and so does
 * `example.net/?q=youtube.com`. The host is parsed and compared from the right
 * hand end, at a dot boundary, which is the only comparison that cannot be
 * dressed up by a hostname somebody else controls.
 */
object YouTubeLinks {

    /** Where the browser starts. The mobile site is the one built for a phone. */
    const val HOME = "https://m.youtube.com/"

    /**
     * The domains the browser will open.
     *
     * `youtube-nocookie.com` is YouTube's own embed domain and `youtu.be` its
     * share domain, so both are the same place. Google's sign-in pages are
     * deliberately not here: this is for finding videos, and a browser that
     * wandered into an account login would be inviting a password into an app
     * that has no business holding one.
     */
    private val ALLOWED = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")

    fun isYouTube(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return ALLOWED.any { host == it || host.endsWith("." + it) }
    }

    /**
     * Whether this page is a video rather than a list of them.
     *
     * The download button is only worth offering where there is something to
     * download, and YouTube writes a video three ways: the ordinary watch URL,
     * the short share link, and a Short.
     */
    fun isWatchable(url: String?): Boolean {
        if (!isYouTube(url)) return false
        val text = url.orEmpty().lowercase()
        val host = hostOf(text) ?: return false
        return text.contains("watch?v=") ||
                text.contains("/shorts/") ||
                (host.endsWith("youtu.be") && path(text).length > 1)
    }

    /**
     * The host, lowercased, or null when this is not an ordinary web address.
     *
     * Anything that is not http or https is refused outright: an `intent://`
     * link is how a page asks to be handed to another app, which is the one
     * thing this browser exists to avoid.
     */
    private fun hostOf(url: String?): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parsed = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        return parsed.host?.lowercase()?.removePrefix("www.")
    }

    private fun path(url: String): String =
        runCatching { URI(url).path.orEmpty() }.getOrDefault("")
}
