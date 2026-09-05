package com.mykungfu.mvtagger.core

/**
 * What a file actually is, which decides everything downstream: which service
 * is asked, which atoms are written, and how the file is renamed.
 */
enum class MediaKind {
    MUSIC_VIDEO,
    MOVIE,
    TV_EPISODE,

    /*
       The three below are different in kind from the three above, and it is
       worth being plain about how.

       A film, an episode and a song all exist in a catalogue somewhere, which
       is what the whole lookup half of this app is built on. A podcast mostly
       does; a workout video and a lecture do not, and never will. So these
       are filed by what the person watching them says they are, and the app's
       job for them is naming, folders and finding them again -- not guessing.

       They are still worth being kinds rather than folders. A kind decides
       the shelf, the name, the atoms written inside the file and which tab it
       appears under, and "a folder called Fitness" would give none of that.
    */
    PODCAST,
    FITNESS,
    LEARNING;

    /**
     * Apple's `stik` value. This is what makes an iPad file it correctly.
     *
     * Podcast and iTunes U are Apple's own values for exactly these things.
     * Fitness has no value of its own, so it goes in as a film, which is what
     * a player should treat it as; the app does not rely on reading it back
     * that way -- see [VideoTags.mediaKind], which is written separately.
     */
    val stik: Int
        get() = when (this) {
            MUSIC_VIDEO -> 6
            MOVIE -> 9
            TV_EPISODE -> 10
            PODCAST -> 21
            FITNESS -> 9
            LEARNING -> 23
        }

    val label: String
        get() = when (this) {
            MUSIC_VIDEO -> "Music video"
            MOVIE -> "Movie"
            TV_EPISODE -> "TV episode"
            PODCAST -> "Podcast"
            FITNESS -> "Fitness"
            LEARNING -> "Learning"
        }

    /** Whether anything online can be asked about a file of this kind. */
    val hasCatalogue: Boolean
        get() = this == MUSIC_VIDEO || this == MOVIE || this == TV_EPISODE || this == PODCAST

    /** Whether files of this kind are grouped under a series, course or show. */
    val hasShow: Boolean
        get() = this == TV_EPISODE || this == PODCAST || this == FITNESS || this == LEARNING

    companion object {
        fun fromStik(value: Int?): MediaKind? = when (value) {
            6 -> MUSIC_VIDEO
            9 -> MOVIE
            10 -> TV_EPISODE
            21 -> PODCAST
            23 -> LEARNING
            else -> null
        }

        /** By name, for reading back what the app itself wrote. */
        fun byName(name: String?): MediaKind? =
            values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

/** What a filename said about a movie or an episode. */
data class ParsedMedia(
    val kind: MediaKind,
    /** Series name for an episode, film name for a movie, song for a video. */
    val name: String,
    val season: Int? = null,
    val episode: Int? = null,
    /** Episode title, when the filename carried one after the SxxExx. */
    val episodeTitle: String? = null,
    val year: String? = null,
    val query: String = name,
)

/**
 * Decides whether a file is an episode, a film, or a music video, and pulls out
 * the fields that go with that answer.
 *
 * The order matters. An episode marker (`S01E02`, `1x02`) is close to
 * unambiguous, so it is tested first. A film is recognised by a year sitting
 * where a release year sits, together with the release-group noise that
 * accompanies one. Everything else is treated as a music video, which is both
 * the app's main job and the safe default -- a misfiled music video is a wrong
 * search, whereas a misfiled film would write television atoms into it.
 */
object MediaClassifier {

    /** `S01E02`, `s1e2`, `S01.E02`, `S01 E02`. */
    private val SXXEXX = Regex("""(?<![\p{L}\p{N}])[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})(?![\p{N}])""")

    /** `1x02`, the other common way of writing it. */
    private val NxNN = Regex("""(?<![\p{L}\p{N}])(\d{1,2})[xX](\d{2,3})(?![\p{N}])""")

    /** `Season 1 Episode 2`, spelled out. */
    private val SPELLED = Regex(
        """(?<![\p{L}\p{N}])Season[\s._-]*(\d{1,2})[\s._-]*Episode[\s._-]*(\d{1,3})""",
        RegexOption.IGNORE_CASE
    )

    private val YEAR_IN_BRACKETS = Regex("""[\[(](19\d{2}|20\d{2})[\])]""")
    private val YEAR_BARE = Regex("""(?<![\p{N}])(19\d{2}|20\d{2})(?![\p{N}])""")

    /**
     * Words that only ever appear in a scene release name. Two or more of these
     * alongside a year is a film, not a song with a year in the title.
     */
    private val RELEASE_NOISE = listOf(
        "bluray", "blu-ray", "brrip", "bdrip", "dvdrip", "dvdscr", "web-dl", "webdl",
        "webrip", "hdrip", "hdtv", "hdcam", "camrip", "predvd", "remux",
        "x264", "x265", "h264", "h265", "hevc", "xvid", "divx", "avc",
        "aac", "ac3", "dts", "ddp", "dd5", "5 1", "7 1", "atmos",
        "1080p", "720p", "2160p", "480p", "4k", "uhd", "hdr", "sdr",
        "dual audio", "multi audio", "esub", "esubs", "msubs", "subs",
        "yify", "yts", "rarbg", "psa", "hdhub", "filmyzilla", "extended",
        "uncut", "proper", "repack", "limited", "internal",
    )

    /** Music videos are the default, so this only has to spot the other two. */
    fun classify(fileName: String): ParsedMedia {
        val base = FilenameParser.stripExtension(fileName)
        val spaced = base.replace('.', ' ').replace('_', ' ')
            .replace(Regex("""\s+"""), " ").trim()

        episodeOf(spaced)?.let { return it }

        val year = YEAR_IN_BRACKETS.find(spaced)?.groupValues?.get(1)
            ?: YEAR_BARE.find(spaced)?.groupValues?.get(1)
        if (year != null && noiseCount(spaced) >= 2) {
            val name = titleBefore(spaced, year)
            if (name.isNotBlank()) {
                return ParsedMedia(
                    kind = MediaKind.MOVIE,
                    name = name,
                    year = year,
                    query = name,
                )
            }
        }

        val parsed = FilenameParser.parse(fileName)
        return ParsedMedia(
            kind = MediaKind.MUSIC_VIDEO,
            name = parsed.title ?: spaced,
            year = parsed.year,
            query = parsed.query.ifBlank { spaced },
        )
    }

    private fun episodeOf(spaced: String): ParsedMedia? {
        val m = SXXEXX.find(spaced) ?: NxNN.find(spaced) ?: SPELLED.find(spaced) ?: return null
        val season = m.groupValues[1].toIntOrNull() ?: return null
        val episode = m.groupValues[2].toIntOrNull() ?: return null

        val show = cleanEdges(spaced.substring(0, m.range.first))
        val after = cleanEdges(stripNoiseWords(spaced.substring(m.range.last + 1)))
        // Whatever is left after the marker and the release junk is the episode
        // title -- often nothing at all, which is fine.
        val episodeTitle = after.takeIf { it.length in 2..80 && !it.all(Char::isDigit) }

        val name = show.ifBlank {
            // "S01E02 - Something" with no series name in front of it.
            episodeTitle ?: return null
        }
        return ParsedMedia(
            kind = MediaKind.TV_EPISODE,
            name = name,
            season = season,
            episode = episode,
            episodeTitle = if (show.isBlank()) null else episodeTitle,
            year = YEAR_BARE.find(spaced)?.groupValues?.get(1),
            query = name,
        )
    }

    private fun noiseCount(text: String): Int {
        val lower = text.lowercase()
        return RELEASE_NOISE.count { lower.contains(it) }
    }

    private fun stripNoiseWords(text: String): String {
        var out = text
        for (word in RELEASE_NOISE) {
            out = Regex(
                """(?<![\p{L}\p{N}])${Regex.escape(word)}(?![\p{L}\p{N}])""",
                RegexOption.IGNORE_CASE
            ).replace(out, " ")
        }
        return out
    }

    /** The part of the name in front of the release year. */
    private fun titleBefore(text: String, year: String): String {
        val at = text.indexOf(year)
        val head = if (at > 0) text.substring(0, at) else text
        return cleanEdges(stripNoiseWords(head))
    }

    private fun cleanEdges(text: String): String =
        text.replace(Regex("""[\[({][^\[\]{}()]*[\])}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '—', '|', '.', '_', ',')
}
