package com.mykungfu.mvtagger

import android.content.Context
import android.net.Uri
import com.mykungfu.mvtagger.core.FilenameParser
import com.mykungfu.mvtagger.core.Sidecar
import com.mykungfu.mvtagger.core.VideoTags

/**
 * Where a song's words are, for a file that is about to be played.
 *
 * The app has been writing these since early on and never read one back: into
 * the `lyr` atom of an MP4, into the .json beside a container that cannot hold
 * tags, and into a .lrc next to the video because that is the file every other
 * player looks for. All of it was written for something else to read.
 *
 * Timed words are worth more than untimed ones here, since the whole point on
 * a playing video is that the line changes when the singing does. So the .lrc
 * beside the file is preferred over the plain copy inside it, even though the
 * one inside is the more authoritative record everywhere else in the app.
 */
object LyricsSource {

    fun of(
        context: Context,
        uri: Uri,
        tree: Uri?,
        parentDocumentId: String?,
        fileName: String?,
    ): String? {
        val tags = tagsFor(context, uri, tree, parentDocumentId, fileName)

        tags?.syncedLyrics?.ifBlank { null }?.let { return it }
        beside(context, tree, parentDocumentId, fileName)?.let { return it }
        return tags?.lyrics?.ifBlank { null }
    }

    private fun tagsFor(
        context: Context,
        uri: Uri,
        tree: Uri?,
        parentDocumentId: String?,
        fileName: String?,
    ): VideoTags? = runCatching {
        when {
            fileName == null -> null
            Sidecar.canEmbed(fileName) -> TagJob.readExisting(context, uri, fileName)
            tree != null && parentDocumentId != null ->
                Catalogue.sidecarTags(context, tree, parentDocumentId, fileName)
            else -> null
        }
    }.getOrNull()

    /** The .lrc a player would look for, which is usually the timed copy. */
    private fun beside(
        context: Context,
        tree: Uri?,
        parentDocumentId: String?,
        fileName: String?,
    ): String? {
        if (tree == null || parentDocumentId == null || fileName == null) return null
        val name = Sidecar.lrcName(FilenameParser.stripExtension(fileName))
        val found = runCatching {
            Saf.findChild(context.contentResolver, tree, parentDocumentId, name)
        }.getOrNull() ?: return null
        return runCatching {
            Saf.readText(context.contentResolver, Saf.documentUri(tree, found.documentId))
        }.getOrNull()?.ifBlank { null }
    }
}
