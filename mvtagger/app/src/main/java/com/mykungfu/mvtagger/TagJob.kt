package com.mykungfu.mvtagger

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.mykungfu.mvtagger.core.FilenameParser
import com.mykungfu.mvtagger.core.Mp4Metadata
import com.mykungfu.mvtagger.core.Organiser
import com.mykungfu.mvtagger.core.RenameTemplate
import com.mykungfu.mvtagger.core.Sidecar
import com.mykungfu.mvtagger.core.VideoTags

/**
 * Writing the finished file.
 *
 * The original is never touched. A new, renamed, fully tagged file is written
 * into the output folder under the organised path, and the source folder is
 * left exactly as it was -- which means a wrong match costs nothing but a
 * delete, and there is no half-rewritten video to lose.
 *
 * There are three ways a file can come out, in order of preference:
 *
 * 1. **MP4 family** -- tags are written straight into it.
 * 2. **Something else whose streams MP4 can hold** -- the audio and video are
 *    moved into an MP4 container untouched (see [Remux]) and tagged. Nothing is
 *    re-encoded, so no quality is lost.
 * 3. **Anything left** -- copied and renamed, with the details written to files
 *    alongside, and the app says plainly that is what happened.
 */
object TagJob {

    data class Outcome(
        val ok: Boolean,
        /** Where it went, relative to the output folder. */
        val path: String? = null,
        /** True when the tags went inside the file rather than into sidecars. */
        val embedded: Boolean = false,
        /** True when the file was repackaged into MP4 on the way. */
        val converted: Boolean = false,
        val message: String,
    )

    fun save(
        context: Context,
        sourceTree: Uri,
        documentId: String,
        sourceName: String,
        tags: VideoTags,
        settings: Settings,
    ): Outcome {
        val resolver = context.contentResolver
        val outputTree = settings.outputUri
            ?: return Outcome(false, message = "No output folder chosen yet.")
        val sourceUri = Saf.documentUri(sourceTree, documentId)

        // A container that cannot hold tags is worth repackaging, because the
        // whole point is that the details travel inside the file.
        val embedsDirectly = Sidecar.canEmbed(sourceName)
        val conversion =
            if (!embedsDirectly && settings.convertToMp4) Remux.inspect(context, sourceUri)
            else null
        val converting = conversion?.possible == true

        val extension = if (converting) "mp4" else FilenameParser.extensionOf(sourceName)
        val fileName = RenameTemplate.fileName(
            settings.nameTemplateFor(tags.mediaKind), tags, extension
        ) ?: if (converting) FilenameParser.stripExtension(sourceName) + ".mp4" else sourceName
        val folder = Organiser.folder(settings.folderTemplateFor(tags.mediaKind), tags)

        val parentId = Saf.ensurePath(resolver, outputTree, folder)
            ?: return Outcome(
                false,
                message = "Could not create the folder " + folder.joinToString("/"),
            )

        val created = Saf.createFile(resolver, outputTree, parentId, fileName, mimeFor(extension))
            ?: return Outcome(false, message = "Could not create $fileName in the output folder")
        val displayPath = (folder + created.name).joinToString("/")

        val embedded = try {
            when {
                embedsDirectly -> {
                    writeTagged(context, sourceUri, created.uri, tags)
                    true
                }
                converting -> {
                    convertThenTag(context, sourceUri, created.uri, outputTree, parentId, created.name, tags)
                    true
                }
                else -> {
                    copyOnly(context, sourceUri, created.uri)
                    false
                }
            }
        } catch (e: Mp4Metadata.UnsupportedContainer) {
            // It looked taggable and was not -- a fragmented MP4, or one whose
            // moov is unusable. Keep the copy, fall back to sidecars, and say
            // so rather than pretending it worked.
            return try {
                copyOnly(context, sourceUri, created.uri)
                writeSidecars(context, outputTree, parentId, created.name, tags, settings)
                Outcome(
                    ok = true, path = displayPath, embedded = false,
                    message = "Copied and renamed, but tags could not go inside it (" +
                            e.message + "). Details written alongside instead.",
                )
            } catch (e2: Exception) {
                Saf.delete(resolver, created.uri)
                Outcome(false, message = "Failed: " + (e2.message ?: e2.toString()))
            }
        } catch (e: Exception) {
            // Never leave a partly written file behind to be mistaken for a good one.
            Saf.delete(resolver, created.uri)
            return Outcome(false, message = "Failed: " + (e.message ?: e.toString()))
        }

        if (!embedded && settings.writeSidecars) {
            runCatching {
                writeSidecars(context, outputTree, parentId, created.name, tags, settings)
            }
        }

        val message = when {
            converting ->
                "Converted to MP4 and tagged, saved to " + displayPath +
                        ". Nothing was re-encoded, so the picture is unchanged."
            embedded -> "Tagged and saved to " + displayPath
            else -> {
                val why = conversion?.reason?.takeIf { !it.startsWith("Could not read") }
                "Saved to " + displayPath + ". " +
                        FilenameParser.extensionOf(sourceName).uppercase() +
                        " cannot hold tags inside it, so the details were written " +
                        "alongside." + (why?.let { " " + it } ?: "")
            }
        }
        return Outcome(
            ok = true, path = displayPath, embedded = embedded,
            converted = converting, message = message,
        )
    }

    /**
     * Repackages into MP4, then tags the result.
     *
     * Two steps rather than one because MediaMuxer writes the file it is given
     * and the tagger rewrites a file it reads, so the conversion lands in a
     * temporary document first. That temporary sits in the output folder rather
     * than the cache: an episode can be several gigabytes and the cache is not
     * the place for it.
     *
     * A side benefit of the second pass: MediaMuxer leaves `moov` at the end of
     * the file, and the tagger moves it to the front, so what comes out is
     * fast-start and begins playing without reading to the end first.
     */
    private fun convertThenTag(
        context: Context,
        source: Uri,
        target: Uri,
        outputTree: Uri,
        parentId: String,
        finalName: String,
        tags: VideoTags,
    ) {
        val resolver = context.contentResolver
        val temporary = Saf.createFile(
            resolver, outputTree, parentId,
            FilenameParser.stripExtension(finalName) + ".converting.tmp",
            "video/mp4",
        ) ?: throw java.io.IOException("could not create a temporary file to convert into")

        try {
            Remux.toMp4(context, source, temporary.uri)
            writeTagged(context, temporary.uri, target, tags)
        } finally {
            Saf.delete(resolver, temporary.uri)
        }
    }

    private fun writeTagged(context: Context, source: Uri, target: Uri, tags: VideoTags) {
        Saf.UriSource(context.contentResolver, source).use { input ->
            val output = Saf.openOutput(context.contentResolver, target)
                ?: throw java.io.IOException("could not open the new file for writing")
            output.use { Mp4Metadata.write(input, tags, it) }
        }
    }

    private fun copyOnly(context: Context, source: Uri, target: Uri) {
        val input = context.contentResolver.openInputStream(source)
            ?: throw java.io.IOException("could not read the original")
        input.use { from ->
            val output = Saf.openOutput(context.contentResolver, target)
                ?: throw java.io.IOException("could not open the new file for writing")
            output.use { Saf.copy(from, it) }
        }
    }

    /**
     * For containers with nowhere to put tags. Written next to the video with
     * the names players already look for.
     */
    private fun writeSidecars(
        context: Context,
        outputTree: Uri,
        parentId: String,
        fileName: String,
        tags: VideoTags,
        settings: Settings,
    ) {
        val resolver = context.contentResolver
        val base = FilenameParser.stripExtension(fileName)

        fun put(name: String, mime: String, bytes: ByteArray) {
            val doc = Saf.createFile(resolver, outputTree, parentId, name, mime) ?: return
            Saf.openOutput(resolver, doc.uri)?.use { it.write(bytes) }
        }

        put(
            Sidecar.jsonName(base), "application/json",
            Sidecar.json(tags, fileName).toByteArray(Charsets.UTF_8),
        )
        Sidecar.lrc(tags)?.let {
            put(Sidecar.lrcName(base), "text/plain", it.toByteArray(Charsets.UTF_8))
        }
        if (settings.fetchArtwork) {
            tags.artwork?.let {
                put(
                    Sidecar.artworkName(base, it),
                    if (it.isPng) "image/png" else "image/jpeg",
                    it.bytes,
                )
            }
        }
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov", "qt" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "3gp" -> "video/3gpp"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "mpg", "mpeg" -> "video/mpeg"
        "ogv" -> "video/ogg"
        else -> "video/*"
    }

    /**
     * How long the video runs, which is the strongest single signal for telling
     * two songs of the same name apart. Best effort: a container Android cannot
     * open simply contributes nothing.
     */
    fun durationMs(context: Context, uri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.toInt()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Tags already inside a file, or empty tags if it has none or cannot hold any. */
    fun readExisting(context: Context, uri: Uri, name: String): VideoTags {
        if (!Sidecar.canEmbed(name)) return VideoTags()
        return try {
            Saf.UriSource(context.contentResolver, uri).use { Mp4Metadata.read(it) }
        } catch (e: Exception) {
            VideoTags()
        }
    }
}
