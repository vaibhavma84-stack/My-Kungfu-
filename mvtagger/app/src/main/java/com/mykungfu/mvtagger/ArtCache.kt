package com.mykungfu.mvtagger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * Small copies of the cover art, so the collection list can show it.
 *
 * The artwork inside a file is a few hundred kilobytes and the list may hold
 * hundreds of files. Reading and decoding those on the way past would make
 * scrolling stutter and would hold far too much in memory, so each cover is
 * shrunk once, when the file is first read, and kept as a thumbnail.
 *
 * Two layers: a small in-memory cache for what is on screen, and files on disk
 * for everything else. The disk copies live in the cache directory, which
 * Android is free to clear -- losing them costs a rescan, not any data.
 */
object ArtCache {

    /** Comfortably sharp for a list thumbnail without being a picture viewer. */
    private const val THUMBNAIL_PX = 192
    private const val QUALITY = 80

    /** Enough for a screenful and a bit either side. */
    private val memory = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun directory(context: Context): File =
        File(context.cacheDir, "artwork").also { it.mkdirs() }

    /** Document ids contain slashes and colons, so they are not filenames. */
    private fun fileFor(context: Context, documentId: String): File =
        File(directory(context), documentId.hashCode().toString() + ".jpg")

    fun has(context: Context, documentId: String): Boolean =
        fileFor(context, documentId).exists()

    /**
     * Shrinks and keeps a cover. Returns false if the bytes were not an image
     * this device can decode, which is worth knowing rather than retrying.
     */
    fun store(context: Context, documentId: String, bytes: ByteArray): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false

        val scale = THUMBNAIL_PX.toFloat() / maxOf(full.width, full.height)
        val thumbnail = if (scale >= 1f) full else Bitmap.createScaledBitmap(
            full,
            maxOf(1, (full.width * scale).toInt()),
            maxOf(1, (full.height * scale).toInt()),
            true,
        )

        fileFor(context, documentId).outputStream().use {
            thumbnail.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)
        }
        memory.put(documentId, thumbnail)
        if (thumbnail !== full) full.recycle()
        true
    }.getOrDefault(false)

    /** The thumbnail, from memory if it is there and from disk if not. */
    fun load(context: Context, documentId: String): Bitmap? {
        memory.get(documentId)?.let { return it }
        val file = fileFor(context, documentId)
        if (!file.exists()) return null
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)?.also { memory.put(documentId, it) }
        }.getOrNull()
    }

    fun clear(context: Context) {
        memory.evictAll()
        runCatching { directory(context).listFiles()?.forEach { it.delete() } }
    }

    /**
     * A power of two that gets the decode near the target size, so a 1000px
     * cover is not fully decoded just to be thrown away.
     */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= THUMBNAIL_PX && h / 2 >= THUMBNAIL_PX) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
}
