package com.mykungfu.mvtagger

import com.mykungfu.mvtagger.core.Downloads
import com.mykungfu.mvtagger.core.YouTubeLinks
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asking YouTube what a video is and where its streams are.
 *
 * All of the actual work is NewPipeExtractor's. This is the thin layer that
 * starts it up, hands it a way to make HTTP requests, and turns what it
 * reports into the plain [Downloads.Option] values the rest of the app reasons
 * about -- so nothing outside this file has to know which library is doing it,
 * and a library that stops working can be replaced without the choosing logic
 * or its tests moving.
 *
 * ## What will go wrong, eventually
 *
 * There is no supported way to do this. YouTube's own API lists videos and
 * does not hand over streams, so every downloader that has ever existed works
 * by asking the site the way its player does, and every one of them breaks
 * when the site changes. When this stops working the fix is a newer version of
 * the extractor, not a change here -- which is exactly why a maintained
 * library is worth the dependency.
 */
object YouTube {

    /** What one video turns out to be. */
    class Video(
        val title: String,
        val uploader: String?,
        val durationSeconds: Long,
        val options: List<Downloads.Option>,
    )

    /** Where the browser starts. The mobile site is the one built for a phone. */
    const val HOME = YouTubeLinks.HOME

    private var started = false

    @Synchronized
    private fun ready() {
        if (started) return
        NewPipe.init(PlainDownloader)
        started = true
    }

    /**
     * True for the links worth pasting in, so a typo is caught before a
     * request. The parsing and the reasoning are in [YouTubeLinks], where they
     * can be tested.
     */
    fun looksLikeYouTube(text: String): Boolean = YouTubeLinks.isYouTube(text)

    fun isWatchable(url: String?): Boolean = YouTubeLinks.isWatchable(url)

    /**
     * What is at this link. Throws when the site will not say, which is a
     * sentence for the user rather than something to recover from.
     */
    fun about(link: String): Video {
        ready()
        val info = StreamInfo.getInfo(ServiceList.YouTube, link.trim())

        val options = ArrayList<Downloads.Option>()
        // Complete files first, then video without sound, then sound alone.
        // The choosing is not done here: this only reports what exists.
        for (stream in info.videoStreams.orEmpty()) {
            picture(stream.content, stream.isUrl, stream.resolution, suffixOf(stream.format?.suffix), true)
                ?.let { options += it }
        }
        for (stream in info.videoOnlyStreams.orEmpty()) {
            picture(stream.content, stream.isUrl, stream.resolution, suffixOf(stream.format?.suffix), false)
                ?.let { options += it }
        }
        for (stream in info.audioStreams.orEmpty()) {
            if (!stream.isUrl) continue
            val container = suffixOf(stream.format?.suffix)
            options += Downloads.Option(
                id = stream.content,
                label = bitrateLabel(stream.averageBitrate, container),
                height = 0,
                container = container,
                hasVideo = false,
                hasAudio = true,
                bitrate = stream.averageBitrate,
            )
        }

        return Video(
            title = info.name.orEmpty().ifBlank { "Video" },
            uploader = info.uploaderName?.ifBlank { null },
            durationSeconds = info.duration,
            options = options,
        )
    }

    /**
     * A stream that is not a plain URL is a DASH or HLS manifest, which would
     * mean fetching and stitching hundreds of segments. Skipped rather than
     * half-supported.
     */
    private fun picture(
        content: String?,
        isUrl: Boolean,
        resolution: String?,
        container: Downloads.Container,
        withSound: Boolean,
    ): Downloads.Option? {
        if (content.isNullOrBlank() || !isUrl) return null
        val label = resolution?.ifBlank { null } ?: "video"
        return Downloads.Option(
            id = content,
            label = label,
            height = heightOf(resolution),
            container = container,
            hasVideo = true,
            hasAudio = withSound,
        )
    }

    /** `1080p60` is 1080 lines at sixty frames, and the number in front is the size. */
    private fun heightOf(resolution: String?): Int {
        val digits = resolution.orEmpty().takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    private fun bitrateLabel(bitrate: Int, container: Downloads.Container): String {
        val kind = if (container == Downloads.Container.M4A) "AAC" else "Opus"
        return if (bitrate > 0) kind + " " + bitrate + "k" else kind
    }

    private fun suffixOf(suffix: String?): Downloads.Container = when (suffix?.lowercase()) {
        "mp4" -> Downloads.Container.MP4
        "m4a" -> Downloads.Container.M4A
        "webm", "webma" -> Downloads.Container.WEBM
        else -> Downloads.Container.OTHER
    }

    /**
     * The extractor needs something that can make an HTTP request, and does not
     * mind what. This is the smallest thing that answers: no OkHttp, no extra
     * megabyte in the APK, and nothing kept between calls.
     */
    private object PlainDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val connection = URL(request.url()).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.httpMethod()
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true

                for ((name, values) in request.headers()) {
                    for (value in values) connection.addRequestProperty(name, value)
                }

                val body = request.dataToSend()
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }

                val code = connection.responseCode
                // An error body is still a body, and the extractor reads it to
                // work out what the site is objecting to.
                val stream = if (code >= 400) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() }

                return Response(
                    code,
                    connection.responseMessage,
                    connection.headerFields,
                    text,
                    connection.url.toString(),
                )
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }
}
