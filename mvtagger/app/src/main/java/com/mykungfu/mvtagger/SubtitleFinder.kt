package com.mykungfu.mvtagger

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.mykungfu.mvtagger.core.Cue
import com.mykungfu.mvtagger.core.FilenameParser
import com.mykungfu.mvtagger.core.Languages
import com.mykungfu.mvtagger.core.SubtitleTrack
import com.mykungfu.mvtagger.core.Subtitles
import java.nio.ByteBuffer

/**
 * Subtitles a file already has, before anything is downloaded.
 *
 * Two places to look, in the order they can be trusted:
 *
 * 1. **A subtitle file sitting next to the video.** `Episode.en.srt` beside
 *    `Episode.mkv` is how most downloads arrive, and reading it is exact --
 *    real timings, the language in the name.
 * 2. **A subtitle track inside the video.** Less reliable, and honestly so:
 *    Android's extractor hands over the text of each subtitle and the moment it
 *    starts, but not how long it stays up. The end has to be inferred, which is
 *    covered below.
 */
object SubtitleFinder {

    /** MIME types Android reports for text subtitle tracks. */
    private val TEXT_SUBTITLE_MIMES = setOf(
        "application/x-subrip",
        "text/vtt",
        "text/x-ssa",
        "application/x-ssa",
        "application/ttml+xml",
        "text/plain",
    )

    /**
     * How long a subtitle stays up when nothing says otherwise.
     *
     * Android gives the start of each subtitle but not its end, so the end is
     * taken as the next one's start, capped here. Without a cap a line would
     * sit on screen through a minute of silence until the next one arrived.
     */
    private const val MAX_INFERRED_MS = 7_000L
    private const val MIN_INFERRED_MS = 800L

    /** A subtitle file in the same folder, matched to this video by name. */
    fun beside(context: Context, item: Item): SubtitleTrack? {
        if (item.parentDocumentId.isBlank()) return null
        val resolver = context.contentResolver
        val siblings = runCatching {
            Saf.listChildren(resolver, item.treeUri, item.parentDocumentId)
        }.getOrDefault(emptyList())

        val videoBase = FilenameParser.stripExtension(item.name).lowercase()
        val candidates = siblings.filter { doc ->
            !doc.isDirectory && Saf.isSubtitle(doc.name) &&
                    FilenameParser.stripExtension(doc.name).lowercase().let { base ->
                        // "Episode.srt" and "Episode.en.srt" both belong to
                        // "Episode.mkv"; the language sits between the two dots.
                        base == videoBase || base.startsWith("$videoBase.")
                    }
        }
        if (candidates.isEmpty()) return null

        // Prefer one that names a language over a bare "Episode.srt", since the
        // language is worth having for the track header and the sidecar name.
        val chosen = candidates.minByOrNull {
            if (languageOf(it.name, videoBase) == null) 1 else 0
        } ?: return null

        val text = Saf.readText(resolver, Saf.documentUri(item.treeUri, chosen.documentId))
            ?: return null
        val cues = Subtitles.parse(text)
        if (cues.isEmpty()) return null

        return SubtitleTrack(
            cues = cues,
            language = languageOf(chosen.name, videoBase),
            source = chosen.name,
        )
    }

    /** `Episode.en.srt` against `episode` gives "en". */
    private fun languageOf(subtitleName: String, videoBase: String): String? {
        val base = FilenameParser.stripExtension(subtitleName)
        if (!base.lowercase().startsWith("$videoBase.")) return null
        val tail = base.substring(videoBase.length + 1)
        return Languages.normalise(tail.substringBefore('.'))
    }

    /**
     * A text subtitle track inside the video.
     *
     * Best effort, and worth saying why: Android's extractor does not report
     * how long each subtitle is shown, only when it starts. The end is taken as
     * the next subtitle's start, capped so a line does not hang on screen
     * through a long silence. Timings from a file beside the video are exact,
     * so [beside] is always preferred over this.
     */
    fun embedded(context: Context, uri: Uri, preferredLanguage: String? = null): SubtitleTrack? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)

            var chosen = -1
            var language: String? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase() ?: continue
                if (mime !in TEXT_SUBTITLE_MIMES) continue
                val trackLanguage = runCatching {
                    format.getString(MediaFormat.KEY_LANGUAGE)
                }.getOrNull()?.let { Languages.normalise(it) }

                if (chosen < 0 ||
                    (preferredLanguage != null && trackLanguage == preferredLanguage)
                ) {
                    chosen = i
                    language = trackLanguage
                    if (preferredLanguage != null && trackLanguage == preferredLanguage) break
                }
            }
            if (chosen < 0) return null

            extractor.selectTrack(chosen)
            val buffer = ByteBuffer.allocate(256 * 1024)
            val starts = ArrayList<Long>()
            val texts = ArrayList<String>()

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) break
                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.get(bytes, 0, size)
                val text = String(bytes, Charsets.UTF_8).trim()
                if (text.isNotEmpty()) {
                    starts += extractor.sampleTime / 1000
                    texts += text
                }
                buffer.clear()
                if (!extractor.advance()) break
            }
            if (texts.isEmpty()) return null

            val cues = ArrayList<Cue>(texts.size)
            for (i in texts.indices) {
                val start = starts[i]
                val nextStart = starts.getOrNull(i + 1) ?: (start + MAX_INFERRED_MS)
                val end = minOf(nextStart, start + MAX_INFERRED_MS)
                    .coerceAtLeast(start + MIN_INFERRED_MS)
                // The text may itself be an SSA dialogue line rather than plain
                // words, depending on the track.
                val cleaned = Subtitles.parse(texts[i]).firstOrNull()?.text ?: texts[i]
                cues += Cue(start, end, cleaned)
            }

            SubtitleTrack(cues = cues, language = language, source = "the video file")
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }
}
