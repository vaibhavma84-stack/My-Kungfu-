package com.mykungfu.mvtagger.core

/**
 * The small files Infuse, Plex and Jellyfin look for beside a video.
 *
 * Everything this app knows is already written inside the file, which is what
 * makes a file self-describing on any device. Those three read embedded
 * metadata to varying degrees and would rather not: left alone they identify a
 * library by guessing from filenames and then fetching their own details, which
 * is a second guess at a question already answered — and it guesses badly on
 * exactly the material this library is full of, where a Hindi film song looks
 * like nothing in their catalogues.
 *
 * An `.nfo` next to the video settles it. It is the Kodi format, which Jellyfin
 * and Plex both read, and it says plainly what the file is instead of leaving
 * them to work it out. A `poster.jpg` in the folder does the same for artwork.
 *
 * The folder layout the app already writes needs no changes for any of them:
 *
 *     Movies/Title (Year)/Title (Year).mp4
 *     TV Shows/Show/Season 01/Show - S01E02 - Title.mp4
 *     Music Videos/Artist/Artist - Title.mp4
 *
 * which is what all three expect, and the third is Jellyfin's music-video
 * convention specifically.
 */
object LibraryFiles {

    /** The artwork a media server looks for in a folder. */
    const val POSTER = "poster.jpg"

    /** The series-level file, at the show's folder rather than a season's. */
    const val SHOW_NFO = "tvshow.nfo"

    /** Beside the video, named after it: what Kodi, Jellyfin and Plex expect. */
    fun nfoName(base: String) = "$base.nfo"

    /**
     * The `.nfo` for one video.
     *
     * Only fields that are actually known are written. An empty `<plot/>` is
     * not neutral -- a scraper reads it as "this has no plot" and stops looking,
     * so leaving the element out entirely is the honest thing.
     */
    fun nfo(tags: VideoTags): String = when (tags.mediaKind) {
        MediaKind.MOVIE -> document(
            "movie",
            listOf(
                "title" to tags.title,
                "originaltitle" to tags.title,
                "year" to tags.year,
                "premiered" to tags.date?.takeIf { it.length >= 10 },
                "plot" to tags.albumInfo,
                "genre" to tags.genre,
            ),
        )

        MediaKind.TV_EPISODE -> document(
            "episodedetails",
            listOf(
                "title" to tags.title,
                "showtitle" to tags.showName,
                "season" to tags.seasonNumber?.toString(),
                "episode" to tags.episodeNumber?.toString(),
                "aired" to tags.date?.takeIf { it.length >= 10 },
                "year" to tags.year,
                "plot" to tags.albumInfo,
                "studio" to tags.network,
            ),
        )

        MediaKind.MUSIC_VIDEO -> document(
            "musicvideo",
            listOf(
                "title" to tags.title,
                "artist" to tags.artist,
                "album" to tags.album,
                "year" to tags.year,
                "genre" to tags.genre,
                "premiered" to tags.date?.takeIf { it.length >= 10 },
                // The film or album blurb, which is the closest thing a music
                // video has to a plot.
                "plot" to tags.albumInfo,
                "director" to tags.composer,
            ),
        )
    }

    /**
     * The series-level file, written once at the show's folder.
     *
     * Without it a server has the episodes but nothing that says what the
     * series is, and falls back to guessing the show from the folder name.
     */
    fun showNfo(tags: VideoTags): String? {
        val show = tags.showName?.trim()?.ifBlank { null } ?: return null
        return document(
            "tvshow",
            listOf(
                "title" to show,
                "showtitle" to show,
                "plot" to tags.albumInfo,
                "studio" to tags.network,
                "year" to tags.year,
            ),
        )
    }

    /** Whether this is worth writing at all: a file with nothing known is not. */
    fun worthWriting(tags: VideoTags): Boolean =
        !tags.title.isNullOrBlank() || !tags.showName.isNullOrBlank()

    private fun document(root: String, fields: List<Pair<String, String?>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append('<').append(root).append(">\n")
        val seen = HashSet<String>()
        for ((name, value) in fields) {
            val text = value?.trim()?.ifBlank { null } ?: continue
            // `originaltitle` repeats the title on purpose; anything else
            // arriving twice would be a mistake rather than a convention.
            if (!seen.add(name)) continue
            sb.append("  <").append(name).append('>')
            sb.append(escape(text))
            sb.append("</").append(name).append(">\n")
        }
        sb.append("</").append(root).append(">\n")
        return sb.toString()
    }

    /**
     * XML escaping, and stripping the characters XML cannot carry at all.
     *
     * A stray control character in a title -- and downloaded metadata does
     * carry them -- makes the whole document unparseable, at which point the
     * server ignores the file and silently goes back to guessing.
     */
    private fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\n', '\r', '\t' -> sb.append(' ')
                else -> if (ch.code >= 0x20 || ch.code == 0x09) sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("  +"), " ").trim()
    }
}
