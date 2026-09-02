package com.mykungfu.mvtagger.core

/**
 * What could be worked out from a filename before anything was looked up.
 *
 * [query] is what actually gets sent to the search services -- they do better
 * with the whole cleaned phrase than with a guessed split, so the split into
 * [artist] and [title] is for showing the user and for scoring the results,
 * not for the search itself.
 */
data class ParsedName(
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    val year: String? = null,
    val trackNumber: Int? = null,
    val query: String = "",
    /** Leftover pipe-separated fields: singers, music director, film. */
    val extras: List<String> = emptyList(),
    val language: String? = null,
)

/**
 * Turns a downloaded filename into something searchable.
 *
 * Downloaded music videos are named in a handful of recognisable ways:
 *
 * ```
 * Adele - Hello (Official Music Video) [1080p].mp4
 * 03. Coldplay - Yellow.mkv
 * Kesariya – Brahmastra | Ranbir Kapoor | Arijit Singh | Pritam.mp4
 * Tum_Hi_Ho_-_Aashiqui_2_[YE7VzlLtp-4].webm
 * ```
 *
 * The first is `Artist - Title`. The third is the Hindi film convention, which
 * is the opposite way round: the song comes first and the film, cast and
 * singers follow after pipes. Both are handled, because the collection has both.
 */
object FilenameParser {

    /** Junk that is never part of a song title. Matched whole-word, any case. */
    private val NOISE = listOf(
        "official music video", "official video song", "official video",
        "official audio", "official lyric video", "official trailer",
        "full video song", "full video", "video song", "full song",
        "lyric video", "lyrical video", "with lyrics", "lyrics", "lyrical",
        "music video", "official", "hd video", "audio song",
        "remastered", "reupload", "extended version",
        "4k", "8k", "2160p", "1440p", "1080p", "1080i", "720p", "480p", "360p",
        "hd", "fhd", "uhd", "hq", "x264", "x265", "h264", "h265", "hevc",
        "aac", "mp3", "m4a", "webm", "bluray", "brrip", "dvdrip", "web-dl",
        "60fps", "copyright free", "free download",
    )

    /** A YouTube id as yt-dlp leaves it: exactly eleven of this alphabet. */
    private val YOUTUBE_ID = Regex("""[\[(\-_ ][A-Za-z0-9_-]{11}[\])]?$""")

    private val BRACKETED = Regex("""[\[({][^\[\]{}()]*[\])}]""")
    private val LEADING_TRACK = Regex("""^\s*(\d{1,3})\s*[.\-)]\s+""")
    private val YEAR = Regex("""(?<!\d)(19\d{2}|20\d{2})(?!\d)""")
    private val SEPARATORS = listOf(" - ", " – ", " — ", " -- ", " _ ")

    fun parse(fileName: String): ParsedName {
        val base = stripExtension(fileName)
        // Non-breaking spaces survive many download tools and break matching.
        var work = base.replace('\u00A0', ' ')

        // yt-dlp writes underscores when a filesystem is fussy. Only undo that
        // if the name has no real spaces, so "Tum Hi Ho_Aashiqui" is untouched.
        if (!work.contains(' ') && work.contains('_')) work = work.replace('_', ' ')

        var trackNumber: Int? = null
        LEADING_TRACK.find(work)?.let {
            val n = it.groupValues[1].toIntOrNull()
            // A leading "2013" is a year, not track two hundred and thirteen.
            if (n != null && n in 1..199) {
                trackNumber = n
                work = work.removeRange(it.range)
            }
        }

        // Bracketed groups are almost always noise -- resolution, a video id, a
        // channel name. Keep a bracketed year, which is not.
        val yearFromBrackets = BRACKETED.findAll(work)
            .mapNotNull { YEAR.find(it.value)?.value }
            .firstOrNull()
        work = BRACKETED.replace(work, " ")
        work = YOUTUBE_ID.replace(work, " ")

        work = stripNoise(work)

        val yearFromText = YEAR.find(work)?.value
        val year = yearFromBrackets ?: yearFromText

        work = work.replace(Regex("""\s+"""), " ").trim(' ', '-', '–', '—', '|', '.', '_')

        val (artist, title, album, extras) = split(work)

        val query = listOfNotNull(artist, title).joinToString(" ")
            .ifBlank { work }
            .trim()

        return ParsedName(
            artist = artist?.takeIf { it.isNotBlank() },
            title = title?.takeIf { it.isNotBlank() },
            album = album?.takeIf { it.isNotBlank() },
            year = year,
            trackNumber = trackNumber,
            query = query,
            extras = extras,
            language = Languages.fromScript(TextScript.dominant(work))
                ?.takeIf { TextScript.hasNonLatin(work) },
        )
    }

    private data class Split(
        val artist: String?,
        val title: String?,
        val album: String?,
        val extras: List<String>,
    )

    private fun split(text: String): Split {
        // Pipes first: a name with pipes is the film convention, and its dashes
        // (if any) are inside one of the fields rather than the top-level split.
        if (text.contains('|')) {
            val parts = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val title = parts[0]
                val rest = parts.drop(1)
                // "Song | Film | Singer | Composer" -- the field after the song
                // is the film often enough to be a useful default, and it is
                // shown to the user before anything is written.
                return Split(
                    artist = rest.getOrNull(1),
                    title = title,
                    album = rest.getOrNull(0),
                    extras = rest,
                )
            }
        }

        for (sep in SEPARATORS) {
            val at = text.indexOf(sep)
            if (at > 0) {
                val left = text.substring(0, at).trim()
                val right = text.substring(at + sep.length).trim()
                if (left.isNotEmpty() && right.isNotEmpty()) {
                    return Split(artist = left, title = right, album = null, extras = emptyList())
                }
            }
        }

        // A bare dash with no spaces around it, as in "Adele-Hello".
        val bare = Regex("""^([^-]{2,})-([^-]{2,})$""").find(text)
        if (bare != null) {
            return Split(
                artist = bare.groupValues[1].trim(),
                title = bare.groupValues[2].trim(),
                album = null,
                extras = emptyList(),
            )
        }

        return Split(artist = null, title = text.ifBlank { null }, album = null, extras = emptyList())
    }

    private fun stripNoise(text: String): String {
        var out = text
        for (word in NOISE) {
            // Whole words only, so "HD" does not eat the "hd" in a real title.
            out = Regex("""(?<![\p{L}\p{N}])${Regex.escape(word)}(?![\p{L}\p{N}])""",
                RegexOption.IGNORE_CASE).replace(out, " ")
        }
        return out
    }

    fun stripExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        // Only treat a short trailing run as an extension: "Vol.2" keeps its .2
        // only because that is two characters and numeric, so guard on letters.
        if (dot > 0 && dot >= fileName.length - 6 &&
            fileName.substring(dot + 1).all { it.isLetterOrDigit() } &&
            fileName.substring(dot + 1).any { it.isLetter() }
        ) return fileName.substring(0, dot)
        return fileName
    }

    fun extensionOf(fileName: String): String {
        val stripped = stripExtension(fileName)
        return if (stripped.length == fileName.length) "" else fileName.substring(stripped.length + 1)
    }
}
