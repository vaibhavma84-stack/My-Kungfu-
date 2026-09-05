package com.mykungfu.mvtagger.core

/**
 * Builds a filename out of the tags.
 *
 * Tokens are `{artist}`, `{title}`, `{album}`, `{year}`, `{date}`, `{genre}`,
 * `{track}`, `{track2}`, `{language}`, `{albumartist}` and `{composer}`.
 *
 * Square brackets mark an optional section: it disappears entirely if any token
 * inside it is empty. So
 *
 * ```
 * {artist} - {title}[ ({year})]
 * ```
 *
 * gives `Arijit Singh - Kesariya (2022).mp4` when the year is known and
 * `Arijit Singh - Kesariya.mp4` when it is not, rather than leaving an empty
 * `()` behind.
 */
object RenameTemplate {

    const val DEFAULT = "{artist} - {title}"

    val SUGGESTIONS = listOf(
        "{artist} - {title}",
        "{artist} - {title}[ ({year})]",
        "[{track2}. ]{artist} - {title}",
        "{title}[ - {album}][ ({year})]",
        "[{album} - ]{title} - {artist}",
    )

    /** Sensible starting template for each kind of file. */
    fun defaultFor(kind: MediaKind): String = when (kind) {
        MediaKind.MUSIC_VIDEO -> "{artist} - {title}"
        MediaKind.MOVIE -> "{title}[ ({year})]"
        MediaKind.TV_EPISODE -> "{show} - S{season2}E{episode2}[ - {title}]"
        // Numbered where there are numbers, named where there are not: a
        // podcast episode usually has both, a workout usually has neither.
        MediaKind.PODCAST -> "[{show} - ][{date} - ]{title}"
        MediaKind.FITNESS -> "[{show} - ]{title}"
        MediaKind.LEARNING -> "[{show} - ][S{season2}E{episode2} - ]{title}"
    }

    /** Characters no Android filesystem, SD card included, will accept. */
    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    /** Longest base name produced, leaving room for an extension and a suffix. */
    private const val MAX_BASE = 120

    // Both closing delimiters are escaped deliberately. The JVM's regex engine
    // treats a dangling `}` or `]` as a literal, but Android's is ICU-backed and
    // is stricter about them -- and these compile in a static initialiser that
    // runs at launch, so being lenient here means the app dies on the phone
    // while every test still passes on a laptop.
    private val TOKEN = Regex("""\{(\w+)\}""")
    private val OPTIONAL = Regex("""\[([^\[\]]*)\]""")

    /**
     * The new base name (no extension) for [tags], or null if the template
     * resolves to nothing usable -- which is the signal not to rename at all,
     * rather than to invent a name.
     */
    fun baseName(template: String, tags: VideoTags): String? =
        sanitise(render(template, tags)).takeIf { it.isNotBlank() }

    /**
     * Fills in the tokens and drops any optional section with an empty one.
     * Not sanitised -- callers that build a path need the separators intact.
     */
    fun render(template: String, tags: VideoTags): String {
        val filled = OPTIONAL.replace(template) { m ->
            val section = m.groupValues[1]
            if (TOKEN.findAll(section).any { value(it.groupValues[1], tags).isNullOrBlank() }) ""
            else substitute(section, tags)
        }
        return substitute(filled, tags)
    }

    /** The full filename including [extension], if there is one. */
    fun fileName(template: String, tags: VideoTags, extension: String): String? {
        val base = baseName(template, tags) ?: return null
        return if (extension.isBlank()) base else "$base.$extension"
    }

    private fun substitute(text: String, tags: VideoTags): String =
        TOKEN.replace(text) { value(it.groupValues[1], tags) ?: "" }

    private fun value(token: String, tags: VideoTags): String? = when (token.lowercase()) {
        "artist" -> tags.artist
        "title", "song", "name" -> tags.title
        "album", "film", "movie" -> tags.album
        "albumartist" -> tags.albumArtist
        "composer" -> tags.composer
        "year" -> tags.year
        "date" -> tags.date
        "genre" -> tags.genre
        "language" -> tags.language?.let { Languages.displayName(it) }
        "track" -> tags.trackNumber?.toString()
        "track2" -> tags.trackNumber?.let { it.toString().padStart(2, '0') }
        "show", "series" -> tags.showName
        "season" -> tags.seasonNumber?.toString()
        "season2" -> tags.seasonNumber?.let { it.toString().padStart(2, '0') }
        "episode" -> tags.episodeNumber?.toString()
        "episode2" -> tags.episodeNumber?.let { it.toString().padStart(2, '0') }
        else -> null
    }

    /**
     * Makes a name a filesystem will take.
     *
     * Deliberately keeps non-Latin characters: Android has handled Devanagari
     * filenames for years, and transliterating "केसरिया" to something ASCII
     * would make the library harder to read, not easier.
     */
    fun sanitise(name: String): String {
        var out = name
        for (c in ILLEGAL) out = out.replace(c, ' ')
        out = out.filter { it.code >= 0x20 }
        out = out.replace(Regex("""\s+"""), " ").trim()
        // Windows and some SD-card readers cannot cope with a trailing dot.
        out = out.trimEnd('.', ' ')
        if (out.length > MAX_BASE) out = out.take(MAX_BASE).trimEnd('.', ' ', '-')
        return out
    }

    /**
     * `name (2).mp4` from `name.mp4`, for when the target already exists.
     * Counting from two matches what every file manager does.
     */
    fun withSuffix(fileName: String, n: Int): String {
        val base = FilenameParser.stripExtension(fileName)
        val ext = FilenameParser.extensionOf(fileName)
        val stamped = "$base ($n)"
        return if (ext.isBlank()) stamped else "$stamped.$ext"
    }
}
