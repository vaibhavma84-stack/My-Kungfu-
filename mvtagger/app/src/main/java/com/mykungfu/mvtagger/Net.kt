package com.mykungfu.mvtagger

import com.mykungfu.mvtagger.core.MusicBrainz
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The one place that makes network calls.
 *
 * Plain `HttpURLConnection`: the whole app fetches a handful of small JSON
 * documents and some cover art, which is not worth a networking library.
 */
object Net {

    /** Identifies the app, as MusicBrainz requires and others appreciate. */
    private const val USER_AGENT = MusicBrainz.USER_AGENT

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Cover art can be a megabyte; anything past this is not a cover. */
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 4 * 1024 * 1024

    class HttpError(val code: Int, message: String) : Exception(message)

    /**
     * MusicBrainz allows one request a second and blocks callers that ignore
     * it. Everything MusicBrainz goes through here, so the pacing cannot be
     * forgotten at a call site.
     */
    private object MusicBrainzPace {
        private var lastCall = 0L

        @Synchronized
        fun await() {
            val since = System.currentTimeMillis() - lastCall
            val wait = MusicBrainz.MIN_INTERVAL_MS - since
            if (wait > 0) {
                runCatching { Thread.sleep(wait) }
            }
            lastCall = System.currentTimeMillis()
        }
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        if (url.contains("musicbrainz.org")) MusicBrainzPace.await()
        return String(get(url, MAX_TEXT_BYTES, "application/json", headers), Charsets.UTF_8)
    }

    fun getTextOrNull(url: String, headers: Map<String, String>): String? =
        runCatching { getText(url, headers) }.getOrNull()

    /**
     * A JSON POST, which OpenSubtitles needs for logging in and for asking for
     * a download link. Nothing else here posts anything.
     */
    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            for ((name, value) in headers) setRequestProperty(name, value)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.readBytes()?.toString(Charsets.UTF_8)
                }.getOrNull().orEmpty().take(300)
                throw HttpError(code, "HTTP " + code + " from " + url + " " + detail)
            }
            return connection.inputStream.use {
                String(readCapped(it, MAX_TEXT_BYTES), Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun postJsonOrNull(url: String, body: String, headers: Map<String, String>): String? =
        runCatching { postJson(url, body, headers) }.getOrNull()

    /** Returns null rather than throwing: a missing lookup is not an error. */
    fun getTextOrNull(url: String): String? = runCatching { getText(url) }.getOrNull()

    fun getBytes(url: String): ByteArray = get(url, MAX_IMAGE_BYTES, "image/*")

    fun getBytesOrNull(url: String): ByteArray? = runCatching { getBytes(url) }.getOrNull()

    private fun get(
        url: String,
        limit: Int,
        accept: String,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        var current = url
        // Cover Art Archive answers with a redirect to wherever the image
        // actually lives, and HttpURLConnection will not follow one that
        // changes host across https, so redirects are followed by hand.
        repeat(5) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", accept)
                setRequestProperty("Accept-Encoding", "gzip")
                for ((name, value) in headers) setRequestProperty(name, value)
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw HttpError(code, "redirect with no location")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    throw HttpError(code, "HTTP $code for $current")
                }
                val raw = connection.inputStream
                val stream = if (connection.contentEncoding
                        ?.contains("gzip", ignoreCase = true) == true
                ) GZIPInputStream(raw) else raw
                stream.use { return readCapped(it, limit) }
            } finally {
                connection.disconnect()
            }
        }
        throw HttpError(310, "too many redirects for $url")
    }

    private fun readCapped(stream: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n <= 0) break
            if (out.size() + n > limit) throw HttpError(413, "response larger than $limit bytes")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
