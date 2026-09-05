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

    /**
     * The MIME type for a filename.
     *
     * Used both when creating a file and when handing one to another app: a
     * player that filters on `video/x-matroska` will not offer itself for a
     * bare wildcard video type, so being specific is what makes the right
     * apps appear in the chooser.
     */
    fun mimeForName(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mov", "qt" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "3gp", "3g2" -> "video/3gpp"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "mpg", "mpeg" -> "video/mpeg"
            "ogv" -> "video/ogg"
            "m4a", "m4b" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "opus", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            else -> if (isAudio(fileName, "")) "audio/*" else "video/*"
        }

    fun isVideo(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("video/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /**
     * Music with no picture, which the app now has to handle because it can
     * now fetch it: a song downloaded as sound alone is an .m4a, and an .m4a
     * is an MP4 underneath, so the title, artist and cover go inside it
     * exactly as they would in a video.
     */
    private val AUDIO_EXTENSIONS = setOf(
        "m4a", "m4b", "mp3", "flac", "opus", "oga", "ogg", "aac", "wav", "wma",
        "aiff", "aif", "alac",
    )

    fun isAudio(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    /** Anything this app is willing to take charge of. */
    fun isMedia(name: String, mimeType: String): Boolean =
        isVideo(name, mimeType) || isAudio(name, mimeType)

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
                } else if (isMedia(child.name, child.mimeType)) {
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

    /**
     * A file of this name already in this folder, or null.
     *
     * Used when rewriting something the app itself wrote earlier: [createFile]
     * deliberately steps a duplicate name aside, which is right for a new file
     * and wrong for replacing one, where the whole point is to land on the same
     * name.
     */
    fun findChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        name: String,
    ): Doc? = listChildren(resolver, treeUri, parentDocumentId)
        .firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }

    /** Size in bytes, or null if the provider will not say. */
    fun querySize(resolver: ContentResolver, uri: Uri): Long? =
        resolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null
        )?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /**
     * Whether the folder behind a tree URI can actually be reached right now.
     *
     * Not the same question as whether it is empty, and the difference matters
     * for a library kept on a memory card or a plugged-in drive. An empty
     * listing from a folder that is not there looks exactly like a folder with
     * nothing in it, and treating one as the other is how a remembered library
     * gets overwritten with nothing while the drive is in a drawer.
     */
    fun canRead(resolver: ContentResolver, treeUri: Uri): Boolean = runCatching {
        queryName(resolver, documentUri(treeUri, rootDocumentId(treeUri))) != null
    }.getOrDefault(false)

    fun queryName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    fun rename(resolver: ContentResolver, uri: Uri, newName: String): Uri? =
        runCatching { DocumentsContract.renameDocument(resolver, uri, newName) }.getOrNull()

    fun delete(resolver: ContentResolver, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    /**
     * Appends to a file and patches a few bytes near its front.
     *
     * Needed for Matroska, where the cover goes on the end and the Segment's
     * length near the start has to be corrected to match. "rw" rather than "w"
     * because "w" truncates, which would throw away the file being added to.
     *
     * Returns false if the provider will not open the file this way, which some
     * will not -- the caller keeps what it already wrote.
     */
    fun appendAndPatch(
        resolver: ContentResolver,
        uri: Uri,
        append: ByteArray,
        patchAt: Long,
        patch: ByteArray,
    ): Boolean = runCatching {
        resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            java.io.FileOutputStream(pfd.fileDescriptor).use { stream ->
                val channel = stream.channel
                channel.position(channel.size())
                channel.write(java.nio.ByteBuffer.wrap(append))
                channel.write(java.nio.ByteBuffer.wrap(patch), patchAt)
                channel.force(true)
            }
            true
        } ?: false
    }.getOrDefault(false)

    /**
     * The last bytes of a file.
     *
     * A Matroska cover written by this app sits at the very end, and a
     * television episode is several gigabytes -- so the end is read directly
     * rather than the file scanned to reach it.
     */
    fun readTail(resolver: ContentResolver, uri: Uri, count: Int): ByteArray? = runCatching {
        UriSource(resolver, uri).use { source ->
            val length = source.length
            if (length <= 0L) return@use null
            val want = minOf(count.toLong(), length).toInt()
            val buffer = ByteArray(want)
            var filled = 0
            while (filled < want) {
                val read = source.readAt(length - want + filled, buffer, filled, want - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled == want) buffer else buffer.copyOf(filled)
        }
    }.getOrNull()

    /** The first bytes of a file, for reading a header without opening it twice. */
    fun readHead(resolver: ContentResolver, uri: Uri, count: Int): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val read = stream.read(buffer, filled, count - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled == count) buffer else buffer.copyOf(filled)
        }
    }.getOrNull()

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
