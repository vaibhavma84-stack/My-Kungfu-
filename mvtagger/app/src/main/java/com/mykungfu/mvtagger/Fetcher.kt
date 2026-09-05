package com.mykungfu.mvtagger

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulling bytes down to a file, with something to look at while it happens.
 *
 * Nothing clever: an HTTP GET, streamed straight out to disk. What matters is
 * the parts that are easy to leave out -- reporting how far along it is, so a
 * two-hundred-megabyte download does not look like a frozen app, and stopping
 * cleanly when the person walks away, so a cancelled download does not leave a
 * half file that looks like a whole one.
 */
object Fetcher {

    /** Big enough that the progress callback is not the expensive part. */
    private const val CHUNK = 256 * 1024

    /**
     * Fetches [link] into [target].
     *
     * [onProgress] is given bytes so far and the total where the server says,
     * which it usually does; zero means it would not say. [keepGoing] is asked
     * between chunks, and answering false stops the transfer -- the caller is
     * responsible for cleaning up what was written, since only it knows
     * whether a partial file is worth anything.
     */
    fun toFile(
        link: String,
        target: File,
        keepGoing: () -> Boolean = { true },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long = FileOutputStream(target).use { out -> stream(link, out, keepGoing, onProgress) }

    fun toDocument(
        context: Context,
        link: String,
        target: Uri,
        keepGoing: () -> Boolean = { true },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long {
        val out = context.contentResolver.openOutputStream(target)
            ?: throw java.io.IOException("could not open the new file for writing")
        return out.use { stream(link, it, keepGoing, onProgress) }
    }

    private fun stream(
        link: String,
        out: OutputStream,
        keepGoing: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val connection = URL(link).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            // Some hosts hand a media file to a browser and an error to
            // anything else. Asking as a browser is not a trick here so much
            // as the absence of one.
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val code = connection.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("the server answered " + code + " " +
                        (connection.responseMessage ?: ""))
            }

            val total = connection.contentLengthLong.coerceAtLeast(0L)
            var done = 0L
            val buffer = ByteArray(CHUNK)

            connection.inputStream.use { input ->
                while (true) {
                    if (!keepGoing()) throw InterruptedException("stopped")
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
            out.flush()
            return done
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"
}
