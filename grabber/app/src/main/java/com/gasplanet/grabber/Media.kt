package com.gasplanet.grabber

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Publishes a finished download into the phone's own media library.
 *
 * A file sitting in the app's private folder is invisible: the gallery will
 * not list it and a VR player cannot open it. Handing it to the MediaStore is
 * what puts it in Movies/Grabber where everything else can find it, and from
 * Android 10 onwards that needs no storage permission at all.
 */
object MediaExport {

    const val FOLDER = "Grabber"

    data class Saved(val uri: String, val displayPath: String)

    fun save(context: Context, file: File, title: String, vr: VrProfile, audio: Boolean): Saved {
        val ext = file.extension.ifBlank { if (audio) "m4a" else "mp4" }
        val name = fileName(title, vr, ext)
        val mime = mimeFor(ext, audio)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, file, name, mime, audio)
        } else {
            saveViaPublicFolder(context, file, name, mime, audio)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        file: File,
        name: String,
        mime: String,
        audio: Boolean,
    ): Saved {
        val resolver = context.contentResolver
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relative = (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) +
            File.separator + FOLDER

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            // Hidden from other apps until the copy is complete, so nothing
            // tries to play a half-written file.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("The media library refused the file")

        try {
            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out) { "Could not open the media library for writing" }
                file.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE * 8) }
            }
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return Saved(uri.toString(), "$relative/$name")
    }

    private fun saveViaPublicFolder(
        context: Context,
        file: File,
        name: String,
        mime: String,
        audio: Boolean,
    ): Saved {
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(
            if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        )
        val dir = File(base, FOLDER)
        dir.mkdirs()
        val dest = uniqueIn(dir, name)
        file.inputStream().use { input ->
            dest.outputStream().use { input.copyTo(it, DEFAULT_BUFFER_SIZE * 8) }
        }
        // Older Android only notices a new file once it has been scanned.
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return Saved(Uri.fromFile(dest).toString(), dest.absolutePath)
    }

    private fun uniqueIn(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext")
            n++
        }
        return candidate
    }

    /**
     * Builds the saved name, with the VR layout suffix attached where there is
     * one -- that suffix is how a headset player knows to wrap the video round
     * you instead of showing it flat on a screen.
     */
    fun fileName(title: String, vr: VrProfile, ext: String): String {
        val clean = sanitize(title).ifBlank { "video" }
        return clean + vr.hint + "." + ext
    }

    fun sanitize(title: String): String = title
        .replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(120)
        .trimEnd('.', ' ')

    private fun mimeFor(ext: String, audio: Boolean): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> if (audio) "audio/webm" else "video/webm"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> if (audio) "audio/*" else "video/*"
    }
}
