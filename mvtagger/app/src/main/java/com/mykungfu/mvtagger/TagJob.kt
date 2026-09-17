package com.mykungfu.mvtagger

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.mykungfu.mvtagger.core.FilenameParser
import com.mykungfu.mvtagger.core.LibraryFiles
import com.mykungfu.mvtagger.core.Matroska
import com.mykungfu.mvtagger.core.MediaKind
import com.mykungfu.mvtagger.core.Mp4Metadata
import com.mykungfu.mvtagger.core.Organiser
import com.mykungfu.mvtagger.core.RenameTemplate
import com.mykungfu.mvtagger.core.Sidecar
import com.mykungfu.mvtagger.core.SubtitleTrack
import com.mykungfu.mvtagger.core.Subtitles
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

    /** How far back to look for a cover already attached. See [Catalogue]. */
    private const val COVER_TAIL_BYTES = 2 * 1024 * 1024

    data class Outcome(
        val ok: Boolean,
        /** Where it went, relative to the output folder. */
        val path: String? = null,
        /** True when the tags went inside the file rather than into sidecars. */
        val embedded: Boolean = false,
        /** True when the file was repackaged into MP4 on the way. */
        val converted: Boolean = false,
        /** True when the original was deleted afterwards. */
        val deletedOriginal: Boolean = false,
        val message: String,
    )

    fun save(
        context: Context,
        sourceTree: Uri,
        documentId: String,
        sourceName: String,
        tags: VideoTags,
        settings: Settings,
        subtitles: SubtitleTrack? = null,
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

        val created = Saf.createFile(resolver, outputTree, parentId, fileName, Saf.mimeForName(fileName))
            ?: return Outcome(false, message = "Could not create $fileName in the output folder")
        val displayPath = (folder + created.name).joinToString("/")

        val embedded = try {
            when {
                embedsDirectly -> {
                    writeTagged(context, sourceUri, created.uri, tags, subtitles)
                    true
                }
                converting -> {
                    convertThenTag(
                        context, sourceUri, created.uri, outputTree, parentId,
                        created.name, tags, subtitles,
                    )
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

        // A Matroska file can hold the cover after all, even when it could not
        // be repackaged. The files beside it are still written -- they cost a
        // few kilobytes and every player reads them, including any that ignores
        // an attachment.
        val attached = !embedded && runCatching {
            writeMatroska(context, created.uri, created.name, tags)
        }.getOrDefault(false)

        if (!embedded && settings.writeSidecars) {
            runCatching {
                writeSidecars(context, outputTree, parentId, created.name, tags, settings)
            }
        }

        if (settings.writeLibraryFiles) {
            runCatching {
                writeLibraryFiles(
                    context, outputTree, parentId, folder, created.name, tags
                )
            }
        }

        // A .srt beside the video as well as inside it. Costs a few kilobytes,
        // and every player reads it -- including the ones that ignore a text
        // track inside an MP4.
        if (subtitles != null && !subtitles.isEmpty) {
            runCatching {
                writeSubtitleSidecar(context, outputTree, parentId, created.name, subtitles)
            }
        }

        // Only now, with the new file written and checked, is it safe to
        // consider removing the old one.
        val deleted = settings.deleteOriginalAfterSaving &&
                created.uri != sourceUri &&
                verifyWritten(context, created.uri, sourceUri, embedded, tags) &&
                Saf.delete(resolver, sourceUri)

        val deleteNote = when {
            deleted -> " The original was deleted."
            settings.deleteOriginalAfterSaving ->
                " The original was KEPT: the new file could not be verified."
            else -> ""
        }

        val subtitleNote = subtitles?.takeIf { !it.isEmpty }?.let {
            " Subtitles added (" + it.cues.size + " lines" +
                (it.source?.let { where -> ", from " + where } ?: "") + ")."
        } ?: ""

        val message = when {
            converting ->
                "Converted to MP4 and tagged, saved to " + displayPath +
                        ". Nothing was re-encoded, so the picture is unchanged."
            embedded -> "Tagged and saved to " + displayPath
            attached ->
                "Saved to " + displayPath + ". The cover and details were attached " +
                        "inside it, the way Matroska carries them."
            else -> {
                val why = conversion?.reason?.takeIf { !it.startsWith("Could not read") }
                "Saved to " + displayPath + ". " +
                        FilenameParser.extensionOf(sourceName).uppercase() +
                        " cannot hold tags inside it, so the details were written " +
                        "alongside." + (why?.let { " " + it } ?: "")
            }
        } + subtitleNote + deleteNote
        return Outcome(
            ok = true, path = displayPath, embedded = embedded,
            converted = converting, deletedOriginal = deleted, message = message,
        )
    }

    /**
     * Changes the details on a file that is already finished and filed.
     *
     * The details live inside the file, so there is no small edit to make:
     * changing one means writing the file again. That is done to a new document
     * first, and the old one is removed only once the new one has been checked
     * -- the same rule the delete-original setting follows, and it matters more
     * here, because by this point the file in the output folder may be the only
     * copy left.
     *
     * If the write fails, or does not check out, or the old file will not go,
     * nothing changes and the file stays exactly as it was.
     *
     * Renaming and refiling follow from the new details, since the templates
     * are built out of them -- correcting an artist moves the file into that
     * artist's folder. Correcting only the language, which no default template
     * uses, rewrites the tags and leaves the file where it is.
     */
    fun retag(
        context: Context,
        outputTree: Uri,
        documentId: String,
        parentDocumentId: String,
        currentName: String,
        tags: VideoTags,
        settings: Settings,
    ): Outcome {
        val resolver = context.contentResolver
        val sourceUri = Saf.documentUri(outputTree, documentId)
        val extension = FilenameParser.extensionOf(currentName)
        val wantedName = RenameTemplate.fileName(
            settings.nameTemplateFor(tags.mediaKind), tags, extension
        ) ?: currentName

        if (!Sidecar.canEmbed(currentName)) {
            // Nothing inside this container can hold the details, so the files
            // beside it are all there is to rewrite. The video is left where it
            // is rather than copied gigabyte for gigabyte to change a name.
            if (!settings.writeSidecars) {
                return Outcome(
                    false,
                    message = extension.uppercase() + " cannot hold details inside it, " +
                            "and writing files alongside is switched off in Settings, " +
                            "so there is nothing here to change.",
                )
            }
            return try {
                /*
                   Matroska can hold the cover after all, and correcting a file
                   should put it in -- not only saving one for the first time,
                   which is where this was wired and nowhere else. So a
                   correction on an MKV wrote the files beside it and left the
                   inside untouched, which is exactly what was reported as the
                   artwork still not being embedded.

                   Once, though. The attachment goes on the end and there is no
                   way to replace one in place without rewriting the whole file
                   and moving every position recorded in it, so a file that
                   already carries one is left as it is and says so.
                */
                val attachedNow = writeMatroskaOnce(context, outputTree, documentId, currentName, tags)

                replaceSidecars(
                    context, outputTree, parentDocumentId, currentName, tags, settings
                )
                if (settings.writeLibraryFiles) {
                    runCatching {
                        writeLibraryFiles(
                            context, outputTree, parentDocumentId,
                            Organiser.folder(
                                settings.folderTemplateFor(tags.mediaKind), tags
                            ),
                            currentName, tags,
                        )
                    }
                }
                Outcome(
                    ok = true, path = currentName, embedded = attachedNow,
                    message = if (attachedNow) {
                        "Updated, and the cover was attached inside " + currentName + "."
                    } else {
                        "Updated the details beside " + currentName + ". " +
                                extension.uppercase() + " keeps them there rather than " +
                                "inside it, and the video was left where it is."
                    },
                )
            } catch (e: Exception) {
                Outcome(false, message = "Failed: " + (e.message ?: e.toString()))
            }
        }

        val folder = Organiser.folder(settings.folderTemplateFor(tags.mediaKind), tags)
        val destinationParent = Saf.ensurePath(resolver, outputTree, folder)
            ?: return Outcome(
                false,
                message = "Could not create the folder " + folder.joinToString("/"),
            )

        val created = Saf.createFile(
            resolver, outputTree, destinationParent, wantedName, Saf.mimeForName(wantedName)
        ) ?: return Outcome(false, message = "Could not create " + wantedName + " to write into")

        try {
            writeTagged(context, sourceUri, created.uri, tags)
        } catch (e: Exception) {
            Saf.delete(resolver, created.uri)
            return Outcome(
                false,
                message = "Failed, and " + currentName + " is untouched: " +
                        (e.message ?: e.toString()),
            )
        }

        if (!verifyWritten(context, created.uri, sourceUri, embedded = true, tags = tags)) {
            Saf.delete(resolver, created.uri)
            return Outcome(
                false,
                message = "The rewritten file did not check out, so it was thrown away " +
                        "and " + currentName + " is exactly as it was.",
            )
        }

        if (!Saf.delete(resolver, sourceUri)) {
            Saf.delete(resolver, created.uri)
            return Outcome(
                false,
                message = "Could not replace " + currentName + ", so it was left as it was.",
            )
        }

        // While both existed the new document had to step around the old one's
        // name. With the old one gone the wanted name is free.
        var finalName = created.name
        if (!finalName.equals(wantedName, ignoreCase = true)) {
            if (Saf.rename(resolver, created.uri, wantedName) != null) finalName = wantedName
        }

        if (settings.writeLibraryFiles) {
            runCatching {
                writeLibraryFiles(
                    context, outputTree, destinationParent, folder, finalName, tags
                )
            }
        }

        val displayPath = (folder + finalName).joinToString("/")
        val moved = !finalName.equals(currentName, ignoreCase = true) ||
                destinationParent != parentDocumentId
        return Outcome(
            ok = true, path = displayPath, embedded = true,
            message = if (moved) "Updated, and filed as " + displayPath
            else "Updated " + finalName + ".",
        )
    }

    /**
     * Whether the new file is genuinely good enough to delete the old one for.
     *
     * "The save reported success" is not enough. A provider can report a write
     * that did not land, and a truncated file looks like a file. Deleting the
     * original is the one irreversible thing this app does, so it is gated on
     * evidence rather than on the absence of an error:
     *
     * - the new file exists and the provider will state its size;
     * - that size is in the right region -- not zero, and not a fraction of the
     *   original, which is what a half-written copy looks like;
     * - and where tags were written into it, the file is opened again and its
     *   metadata read back, which only succeeds if the box structure survived.
     *
     * Anything short of all three keeps the original. A stray copy is a
     * nuisance; a deleted video is gone.
     */
    private fun verifyWritten(
        context: Context,
        target: Uri,
        source: Uri,
        embedded: Boolean,
        tags: VideoTags,
    ): Boolean {
        val resolver = context.contentResolver
        val written = Saf.querySize(resolver, target) ?: return false
        if (written <= 0) return false

        val original = Saf.querySize(resolver, source)
        if (original != null && original > 0) {
            // Repackaging legitimately loses subtitle and attachment tracks, so
            // some shrinkage is expected; half the size is not.
            if (written < original / 2) return false
        }

        if (!embedded) return true

        // Reading the tags back proves the box tree is walkable, which is the
        // thing that would be broken if the write went wrong.
        return runCatching {
            Saf.UriSource(resolver, target).use { Mp4Metadata.read(it) }
        }.map { back ->
            val wanted = tags.title?.trim()
            wanted.isNullOrEmpty() || back.title?.trim() == wanted
        }.getOrDefault(false)
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
        subtitles: SubtitleTrack?,
    ) {
        val resolver = context.contentResolver
        val temporary = Saf.createFile(
            resolver, outputTree, parentId,
            FilenameParser.stripExtension(finalName) + ".converting.tmp",
            "video/mp4",
        ) ?: throw java.io.IOException("could not create a temporary file to convert into")

        try {
            Remux.toMp4(context, source, temporary.uri)
            writeTagged(context, temporary.uri, target, tags, subtitles)
        } finally {
            Saf.delete(resolver, temporary.uri)
        }
    }

    private fun writeTagged(
        context: Context,
        source: Uri,
        target: Uri,
        tags: VideoTags,
        subtitles: SubtitleTrack? = null,
    ) {
        Saf.UriSource(context.contentResolver, source).use { input ->
            val output = Saf.openOutput(context.contentResolver, target)
                ?: throw java.io.IOException("could not open the new file for writing")
            output.use { Mp4Metadata.write(input, tags, it, subtitles) }
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
     * Puts the cover and the details inside a Matroska file after all.
     *
     * This container was the standing exception: no place for iTunes atoms, and
     * a repackage into MP4 that Android's muxer refuses whenever the audio is
     * AC3, E-AC3 or DTS -- which is what television comes in. So a series ended
     * up copied unchanged with a .jpg beside it, and "the artwork is not
     * getting embedded" was simply true.
     *
     * Matroska does have somewhere: an attachment named `cover.jpg`, which is
     * the convention Infuse, Plex, Jellyfin, Kodi and VLC all read, and a Tags
     * element for the rest. See [Matroska] for why appending to the end of the
     * Segment is the safe way in.
     *
     * Returns whether anything was written. Everything about this is
     * conditional -- the file has to parse, the length field has to have room,
     * the provider has to allow writing at a position -- and any of those
     * failing leaves the copy exactly as it was, which is a working video with
     * its details beside it rather than a broken one.
     */
    private fun writeMatroska(
        context: Context,
        target: Uri,
        fileName: String,
        tags: VideoTags,
    ): Boolean {
        if (!Matroska.isMatroska(fileName)) return false
        val additions = Matroska.additions(tags)
        if (additions.isEmpty()) return false

        val resolver = context.contentResolver
        val head = Saf.readHead(resolver, target, Matroska.HEAD_BYTES) ?: return false
        val segment = Matroska.segmentOf(head) ?: return false
        val resized = Matroska.resized(segment, additions.size.toLong()) ?: return false

        if (!Saf.appendAndPatch(
                resolver, target, additions, segment.sizeAt.toLong(), resized
            )
        ) return false

        // Read the front back and check the file still describes itself. A
        // length that no longer matches what is there is worse than no cover.
        val after = Saf.readHead(resolver, target, Matroska.HEAD_BYTES)
            ?.let { Matroska.segmentOf(it) }
        val size = Saf.querySize(resolver, target)
        return after != null && size != null && after.dataAt + after.size == size
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

    /**
     * The same files as [writeSidecars], but landing on the names already
     * there.
     *
     * [Saf.createFile] steps a duplicate name aside, which is what a new file
     * wants and the opposite of what this wants: rewriting the details for a
     * file means replacing its .json, not leaving the stale one in place beside
     * a "(2)" copy that nothing will ever read.
     */
    private fun replaceSidecars(
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
            val existing = Saf.findChild(resolver, outputTree, parentId, name)
            val uri = if (existing != null) {
                Saf.documentUri(outputTree, existing.documentId)
            } else {
                Saf.createFile(resolver, outputTree, parentId, name, mime)?.uri ?: return
            }
            Saf.openOutput(resolver, uri)?.use { it.write(bytes) }
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

    /**
     * Attaches the cover to a Matroska file that has not got one.
     *
     * The attachment is appended, and appending twice would leave two covers
     * and two sets of details in the file. Replacing the first would mean
     * rewriting the whole thing and correcting every position recorded inside
     * it, which is the work that appending exists to avoid. So this writes once
     * and then leaves well alone.
     */
    private fun writeMatroskaOnce(
        context: Context,
        outputTree: Uri,
        documentId: String,
        fileName: String,
        tags: VideoTags,
    ): Boolean {
        if (!Matroska.isMatroska(fileName)) return false
        val uri = Saf.documentUri(outputTree, documentId)
        val already = Saf.readTail(context.contentResolver, uri, COVER_TAIL_BYTES)
            ?.let { runCatching { Matroska.hasAttachments(it) }.getOrDefault(false) }
            ?: false
        if (already) return false
        return runCatching { writeMatroska(context, uri, fileName, tags) }.getOrDefault(false)
    }

    /**
     * The poster and .nfo that Infuse, Plex and Jellyfin look for.
     *
     * The folder layout this app already writes is the one all three expect, so
     * nothing moves; what they are missing is a statement of what each file is.
     * Left without one they identify a library by guessing from filenames and
     * then fetching their own details -- a second guess at a question already
     * answered, and a bad guess on Indian film music, where a song's name means
     * nothing to a film catalogue.
     *
     * The poster goes in the folder rather than beside the file, which is where
     * they look, and is written once: for a film that is its own folder, and
     * for a series the show's folder rather than each season's. An existing
     * poster is left alone -- it may have been put there on purpose.
     *
     * Best effort throughout. These are a convenience for other apps, and
     * failing to write one is not a reason to fail a save that has already
     * written the video itself.
     */
    private fun writeLibraryFiles(
        context: Context,
        outputTree: Uri,
        parentId: String,
        folder: List<String>,
        fileName: String,
        tags: VideoTags,
    ) {
        if (!LibraryFiles.worthWriting(tags)) return
        val resolver = context.contentResolver
        val base = FilenameParser.stripExtension(fileName)

        fun put(where: String, name: String, mime: String, bytes: ByteArray, replace: Boolean) {
            val existing = Saf.findChild(resolver, outputTree, where, name)
            if (existing != null && !replace) return
            val uri = if (existing != null) {
                Saf.documentUri(outputTree, existing.documentId)
            } else {
                Saf.createFile(resolver, outputTree, where, name, mime)?.uri ?: return
            }
            Saf.openOutput(resolver, uri)?.use { it.write(bytes) }
        }

        runCatching {
            put(
                parentId, LibraryFiles.nfoName(base), "text/xml",
                LibraryFiles.nfo(tags).toByteArray(Charsets.UTF_8),
                replace = true,
            )
        }

        val artwork = tags.artwork
        if (tags.mediaKind == MediaKind.MOVIE && artwork != null) {
            // A film has a folder to itself, so the folder poster is this film's.
            runCatching {
                put(parentId, LibraryFiles.POSTER, "image/jpeg", artwork.bytes, replace = false)
            }
        }

        if (tags.mediaKind == MediaKind.TV_EPISODE) {
            // The show's folder, which is one level up from the season -- but
            // only when there is a season folder to come up from. The template
            // drops the season segment when the file never said which season it
            // was, and going up regardless would put the series file at the top
            // of the library, claiming every show in it.
            val lastIsSeason = folder.lastOrNull()?.startsWith("Season", ignoreCase = true) == true
            val showId = if (lastIsSeason && folder.size >= 2) {
                Saf.ensurePath(resolver, outputTree, folder.dropLast(1)) ?: return
            } else {
                parentId
            }
            runCatching {
                LibraryFiles.showNfo(tags)?.let {
                    put(
                        showId, LibraryFiles.SHOW_NFO, "text/xml",
                        it.toByteArray(Charsets.UTF_8), replace = true,
                    )
                }
                if (artwork != null) {
                    put(showId, LibraryFiles.POSTER, "image/jpeg", artwork.bytes, replace = false)
                }
            }
        }
    }

    private fun writeSubtitleSidecar(
        context: Context,
        outputTree: Uri,
        parentId: String,
        fileName: String,
        subtitles: SubtitleTrack,
    ) {
        val base = FilenameParser.stripExtension(fileName)
        val name = Subtitles.sidecarName(base, subtitles.language)
        val doc = Saf.createFile(
            context.contentResolver, outputTree, parentId, name, "application/x-subrip"
        ) ?: return
        Saf.openOutput(context.contentResolver, doc.uri)?.use {
            it.write(Subtitles.toSrt(Subtitles.tidy(subtitles.cues)).toByteArray(Charsets.UTF_8))
        }
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

    /**
     * How big the picture is, for saying whether a file is 1080p or 4K.
     *
     * Read from the file rather than believed from its name: "4K" in a filename
     * is a claim by whoever uploaded it, and an upscaled 1080p carries it just
     * as readily as the real thing.
     */
    fun videoSize(context: Context, uri: Uri): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (width != null && height != null && width > 0 && height > 0) {
                width to height
            } else {
                null
            }
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
