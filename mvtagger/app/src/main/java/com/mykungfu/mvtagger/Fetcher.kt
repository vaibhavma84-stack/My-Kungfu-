package com.mykungfu.mvtagger

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Pulling bytes down to a file, a piece at a time.
 *
 * The first version asked for the whole file in one request, which is the
 * obvious way and does not work here. Google serves a large media file to a
 * single open-ended request very slowly or not at all -- the connection opens,
 * nothing arrives, and half a minute later the socket times out with the
 * progress still reading nought per cent. That is exactly what came back from
 * the first real download anybody tried.
 *
 * So it asks in pieces, with a byte range on each, which is what every
 * downloader that works does. Three things follow from it and all three
 * matter:
 *
 *  - the transfer is not throttled, because no single request is large enough
 *    to be worth throttling;
 *  - a stall costs one piece rather than the whole file, and the piece is
 *    asked for again from exactly where it stopped;
 *  - the size is known from the first answer, so the progress is real.
 */
object Fetcher {

    /** Big enough to be efficient, small enough that losing one costs nothing. */
    private const val PIECE = 8L * 1024 * 1024

    private const val BUFFER = 128 * 1024

    /** How many times a piece may stall before the download is given up on. */
    private const val STALLS_ALLOWED = 4

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

    /**
     * The loop: ask for the next piece, keep what arrives, ask again.
     *
     * Two kinds of trouble are recovered from rather than reported. A refusal
     * is tried once more as the phone app, because Google hands a stream URL
     * to whichever client asked for it and sometimes wants that client back.
     * A stall is simply asked again from the byte it stopped at -- which is
     * why [Piece] reports what it wrote even when it failed.
     */
    private fun stream(
        link: String,
        out: OutputStream,
        keepGoing: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var agent = BROWSER
        var done = 0L
        var total = -1L
        var stalls = 0

        while (true) {
            if (!keepGoing()) throw InterruptedException("stopped")

            val until = if (total > 0) minOf(done + PIECE - 1, total - 1) else done + PIECE - 1
            val piece: Piece
            try {
                piece = fetchPiece(link, agent, done, until, out, keepGoing) { far ->
                    onProgress(done + far, maxOf(total, 0L))
                }
            } catch (refused: Refused) {
                if (agent == BROWSER) {
                    agent = PHONE_APP
                    continue
                }
                throw refused
            } catch (stalled: Stalled) {
                // Whatever did arrive is kept; the next attempt starts after it.
                done += stalled.written
                stalls++
                if (stalls > STALLS_ALLOWED) throw stalled.cause
                continue
            }

            stalls = 0
            done += piece.written
            if (piece.total > 0) total = piece.total
            onProgress(done, maxOf(total, 0L))

            // A server that ignored the range has just sent the whole file, and
            // a piece that came back empty has nothing more to give.
            if (piece.wholeFile || piece.written == 0L) break
            if (total > 0 && done >= total) break
        }

        out.flush()
        return done
    }

    private class Piece(val written: Long, val total: Long, val wholeFile: Boolean)

    /** A server that will not serve this at all, as opposed to one that failed. */
    private class Refused(message: String) : java.io.IOException(message)

    /** A transfer that stopped partway, and how far it got before it did. */
    private class Stalled(val written: Long, val cause: java.io.IOException) : java.io.IOException()

    private fun fetchPiece(
        link: String,
        agent: String,
        from: Long,
        until: Long,
        out: OutputStream,
        keepGoing: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): Piece {
        val connection = URL(link).openConnection() as HttpURLConnection
        var written = 0L
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", agent)
            connection.setRequestProperty("Range", "bytes=" + from + "-" + until)

            val code = connection.responseCode
            if (code == 401 || code == 403) {
                throw Refused(
                    "the server answered " + code + " " + (connection.responseMessage ?: "") +
                            " (asked as " + (if (agent == BROWSER) "a browser" else "the phone app") + ")"
                )
            }
            if (code == 416) {
                // Asked past the end, which means there is nothing left.
                return Piece(0L, -1L, false)
            }
            if (code !in 200..299) {
                throw java.io.IOException("the server answered " + code + " " +
                        (connection.responseMessage ?: ""))
            }

            val whole = code != 206
            val total = totalFrom(connection, whole)
            val buffer = ByteArray(BUFFER)

            connection.inputStream.use { input ->
                while (true) {
                    if (!keepGoing()) throw InterruptedException("stopped")
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    onProgress(written)
                }
            }
            return Piece(written, total, whole)
        } catch (timeout: SocketTimeoutException) {
            throw Stalled(written, timeout)
        } catch (reset: java.io.IOException) {
            // A connection cut mid-piece is the same situation as a stall: some
            // of it arrived, and the rest can be asked for again.
            if (written > 0) throw Stalled(written, reset) else throw reset
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * How big the whole file is.
     *
     * A ranged answer says so in `Content-Range: bytes 0-8388607/1234567890`,
     * where the part after the slash is the only number that means the size of
     * the file rather than the size of this piece.
     */
    private fun totalFrom(connection: HttpURLConnection, whole: Boolean): Long {
        if (whole) return connection.contentLengthLong.coerceAtLeast(-1L)
        val range = connection.getHeaderField("Content-Range") ?: return -1L
        return range.substringAfterLast('/').trim().toLongOrNull() ?: -1L
    }

    private const val BROWSER =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"

    /**
     * What YouTube's own iPhone app calls itself, which is the client the
     * extractor usually asks as. Kept here rather than read from the library,
     * which does not expose it.
     */
    private const val PHONE_APP =
        "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"
}
