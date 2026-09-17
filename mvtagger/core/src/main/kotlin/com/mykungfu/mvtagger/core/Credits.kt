package com.mykungfu.mvtagger.core

/**
 * Who actually did what on a track.
 *
 * Worth separating because Indian film music is credited differently from
 * western pop, and the difference is exactly what makes the artist field come
 * out wrong. An iTunes entry for a Hindi song reads
 *
 * ```
 * artistName: "Pritam, Arijit Singh & Amitabh Bhattacharya"
 * ```
 *
 * which is the music director, the singer and the lyricist run together in one
 * string with nothing to say which is which. Writing that whole thing into the
 * artist field is what the app was doing, and it is not what anyone means by
 * the artist of the song.
 */
data class Credits(
    val singers: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val lyricists: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = singers.isEmpty() && composers.isEmpty() && lyricists.isEmpty()

    /** The singers as one field, in the order they were credited. */
    val singerLine: String? get() = singers.takeIf { it.isNotEmpty() }?.joinToString(", ")
    val composerLine: String? get() = composers.takeIf { it.isNotEmpty() }?.joinToString(", ")
    val lyricistLine: String? get() = lyricists.takeIf { it.isNotEmpty() }?.joinToString(", ")

    fun merge(other: Credits) = Credits(
        singers = singers.ifEmpty { other.singers },
        composers = composers.ifEmpty { other.composers },
        lyricists = lyricists.ifEmpty { other.lyricists },
    )
}

/** Splits a run-together credit string into the individual people. */
object CreditNames {

    private val SEPARATORS = Regex(
        """\s*(?:,|&|;|/|\band\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|\bx\b)\s*""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "Pritam, Arijit Singh & Amitabh Bhattacharya" as three names.
     *
     * Order is kept, because it is the only thing the string carries: iTunes
     * India lists the music director first for film songs. That ordering is a
     * convention rather than a guarantee, so it is used to break ties and never
     * on its own -- see [Credits].
     */
    fun split(credit: String?): List<String> {
        if (credit.isNullOrBlank()) return emptyList()
        return SEPARATORS.split(credit)
            .map { it.trim().trim('-', '–', '·', '.').trim() }
            .filter { it.length >= 2 }
            .distinct()
    }
}

/**
 * Getting the film's name out of the way film music is titled.
 *
 * A soundtrack is not titled like an album. The same song appears as
 *
 * ```
 * track:  Kesariya (From "Brahmastra")
 * album:  Brahmastra (Original Motion Picture Soundtrack)
 * ```
 *
 * so the film's name is sitting in both, wrapped in boilerplate. The album
 * field should hold the film, which is what someone browsing a library expects
 * to see, and the title should hold the song rather than repeating the film.
 */
object FilmTitle {

    /** `(From "Brahmastra")`, `[From Jawan]`, `(From the film "Jawan")`. */
    private val FROM = Regex(
        """[\(\[]\s*from\s+(?:the\s+)?(?:film\s+|movie\s+|motion\s+picture\s+)?["“‘']?(.+?)["”’']?\s*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )

    /** Boilerplate that wraps a film name in a soundtrack album title. */
    private val SOUNDTRACK_WORDS = listOf(
        "original motion picture soundtrack",
        "original television soundtrack",
        "music from the motion picture",
        "original motion picture score",
        "original soundtrack",
        "motion picture soundtrack",
        "original score",
        "soundtrack",
    )

    /** A parenthesised or bracketed group that is only soundtrack boilerplate. */
    private val BRACKETED = Regex("""\s*[\(\[][^\(\)\[\]]*[\)\]]""")

    /** The film named inside a track title, if it says. */
    fun fromTrackTitle(title: String?): String? {
        val text = title?.takeIf { it.isNotBlank() } ?: return null
        val found = FROM.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return cleanAlbum(found).takeIf { it.isNotBlank() }
    }

    /** The song on its own, with the `(From "…")` part taken off. */
    fun songTitle(title: String?): String? {
        val text = title?.takeIf { it.isNotBlank() } ?: return null
        return FROM.replace(text, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', '–', ',')
            .trim()
            .ifBlank { text.trim() }
    }

    /**
     * An album title reduced to the film's name.
     *
     * Only groups that are *nothing but* soundtrack boilerplate are removed, so
     * a title that genuinely contains brackets keeps them: "Brahmastra (Original
     * Motion Picture Soundtrack)" becomes "Brahmastra", while "Jawan (Deluxe)"
     * is left alone.
     */
    fun cleanAlbum(album: String?): String {
        var out: String = album?.takeIf { it.isNotBlank() } ?: return ""

        out = BRACKETED.replace(out) { match ->
            val inside = match.value.trim().trim('(', ')', '[', ']').trim().lowercase()
            if (SOUNDTRACK_WORDS.any { inside == it || inside.contains(it) }) "" else match.value
        }

        // The same boilerplate also turns up after a dash rather than in brackets.
        for (word in SOUNDTRACK_WORDS) {
            val tail = Regex("""\s*[-–—:]\s*""" + Regex.escape(word) + """\s*$""", RegexOption.IGNORE_CASE)
            out = tail.replace(out, "")
        }

        return out.replace(Regex("""\s+"""), " ").trim().trim('-', '–', ':', ',').trim()
    }
}
