package com.mykungfu.mvtagger

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.mykungfu.mvtagger.core.Mp4
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Everything that touches the Storage Access Framework.
 *
 * The app never asks for blanket storage permission. The user picks a source
 * folder and an output folder; Android grants access to those two subtrees and
 * nothing else, and [persist] keeps that grant across restarts.
 */
object Saf {

    /** One file found by a scan. */
    data class Doc(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
        /** The folder it was found in, so its siblings can be looked at. */
        val parentDocumentId: String = "",
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    /** Subtitle files that may be sitting beside a video. */
    val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")

    fun isSubtitle(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUBTITLE_EXTENSIONS

    /** Extensions treated as video, for folders that report a vague MIME type. */
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "qt", "mkv", "webm", "avi", "wmv", "flv", "mpg",
        "mpeg", "m2ts", "ts", "3gp", "3g2", "ogv", "divx", "vob", "asf", "rm",
        "rmvb", "f4v", "mts",
    )

    fun isVideo(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("video/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /** Keeps a folder grant alive across app restarts. */
    fun persist(context: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
    }

    fun rootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    fun childrenUri(treeUri: Uri, parentDocumentId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)

    fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    /**
     * Direct children of a folder.
     *
     * Queried through the resolver rather than `DocumentFile.listFiles()`,
     * which issues a separate query per file and takes minutes on a folder of
     * a few hundred videos.
     */
    fun listChildren(resolver: ContentResolver, treeUri: Uri, parentDocumentId: String): List<Doc> {
        val out = ArrayList<Doc>()
        val uri = childrenUri(treeUri, parentDocumentId)
        resolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Doc(
                    documentId = c.getString(0),
                    name = c.getString(1) ?: continue,
                    mimeType = c.getString(2) ?: "",
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                    parentDocumentId = parentDocumentId,
                )
            }
        }
        return out
    }

    /**
     * Every video under a folder, following subfolders.
     *
     * [maxDepth] stops a symlinked or pathological tree from running forever;
     * eight levels is far deeper than any real media folder.
     */
    fun scanVideos(
        resolver: ContentResolver,
        treeUri: Uri,
        maxDepth: Int = 8,
        onProgress: (Int) -> Unit = {},
    ): List<Doc> {
        val found = ArrayList<Doc>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += rootDocumentId(treeUri) to 0
        while (queue.isNotEmpty()) {
            val (docId, depth) = queue.removeFirst()
            for (child in listChildren(resolver, treeUri, docId)) {
                if (child.isDirectory) {
                    if (depth < maxDepth) queue += child.documentId to depth + 1
                } else if (isVideo(child.name, child.mimeType)) {
                    found += child
                    onProgress(found.size)
                }
            }
        }
        return found
    }

    /** Finds a subfolder by name, creating it if it is not there. */
    fun findOrCreateDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        name: String,
    ): String? {
        listChildren(resolver, treeUri, parentDocumentId)
            .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.let { return it.documentId }

        val parentUri = documentUri(treeUri, parentDocumentId)
        val created = runCatching {
            DocumentsContract.createDocument(
                resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
            )
        }.getOrNull() ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    /** Walks (and creates) a chain of subfolders, returning the deepest one. */
    fun ensurePath(
        resolver: ContentResolver,
        treeUri: Uri,
        segments: List<String>,
    ): String? {
        var current = rootDocumentId(treeUri)
        for (segment in segments) {
            current = findOrCreateDirectory(resolver, treeUri, current, segment) ?: return null
        }
        return current
    }

    /**
     * Creates a file, stepping the name aside rather than overwriting if one of
     * that name is already there.
     *
     * Some providers silently rename a duplicate themselves and others fail, so
     * the check is done here and the actual name used is reported back.
     */
    data class Created(val uri: Uri, val name: String)

    fun createFile(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        fileName: String,
        mimeType: String,
    ): Created? {
        val existing = listChildren(resolver, treeUri, parentDocumentId)
            .filter { !it.isDirectory }
            .map { it.name.lowercase() }
            .toSet()

        var name = fileName
        var n = 2
        while (name.lowercase() in existing && n < 500) {
            name = com.mykungfu.mvtagger.core.RenameTemplate.withSuffix(fileName, n)
            n++
        }

        val parentUri = documentUri(treeUri, parentDocumentId)
        val uri = runCatching {
            DocumentsContract.createDocument(resolver, parentUri, mimeType, name)
        }.getOrNull() ?: return null

        // The provider may have adjusted the name; report what it actually made.
        val actual = queryName(resolver, uri) ?: name
        return Created(uri, actual)
    }

    fun queryName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    fun rename(resolver: ContentResolver, uri: Uri, newName: String): Uri? =
        runCatching { DocumentsContract.renameDocument(resolver, uri, newName) }.getOrNull()

    fun delete(resolver: ContentResolver, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    fun openOutput(resolver: ContentResolver, uri: Uri): OutputStream? =
        runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()

    /**
     * A [Mp4.ByteSource] over a content URI.
     *
     * Uses the file channel's positional read, which does not move a shared
     * cursor -- the tagger jumps around the file rather than reading it start
     * to finish, so a plain InputStream would mean reopening constantly.
     */
    class UriSource(
        resolver: ContentResolver,
        uri: Uri,
    ) : Mp4.ByteSource {
        private val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("could not open $uri")
        private val stream = FileInputStream(pfd.fileDescriptor)
        private val channel = stream.channel

        override val length: Long = pfd.statSize

        override fun readAt(position: Long, dest: ByteArray, offset: Int, count: Int): Int =
            channel.read(ByteBuffer.wrap(dest, offset, count), position)

        override fun close() {
            runCatching { stream.close() }
            runCatching { pfd.close() }
        }
    }

    /** A whole text file, for reading a subtitle sitting next to a video. */
    fun readText(resolver: ContentResolver, uri: Uri, limit: Int = 8 * 1024 * 1024): String? =
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > limit) return@use null
                // Subtitles are often Windows-1252 rather than UTF-8; a broken
                // decode is better than refusing the file, and the text is only
                // used for its words and timings.
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()

    fun copy(input: java.io.InputStream, output: OutputStream): Long {
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            output.write(buffer, 0, n)
            total += n
        }
        output.flush()
        return total
    }
}
