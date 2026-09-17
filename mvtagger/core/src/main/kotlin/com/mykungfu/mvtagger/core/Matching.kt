package com.mykungfu.mvtagger.core

import java.text.Normalizer

/**
 * Ranks search results against what the filename suggested.
 *
 * The scoring is deliberately explainable rather than clever: each rule adds a
 * named amount, and [Scored.reasons] carries them so the app can say *why* a
 * match is top of the list. A wrong auto-tag that cannot be explained is worse
 * than no auto-tag.
 */
object Matching {

    data class Scored(
        val candidate: Candidate,
        val score: Double,
        val reasons: List<String>,
    ) {
        /** High enough to apply without a person looking at it. */
        val isConfident: Boolean get() = score >= 0.80
    }

    /**
     * Folds a title down to something comparable: lower case, no accents, no
     * punctuation. Devanagari and other Indic text passes through unchanged --
     * there is nothing to fold, and mangling it would lose the match.
     */
    fun normalise(text: String?): String {
        if (text.isNullOrBlank()) return ""
        // A Devanagari title and its Latin spelling share no characters at all,
        // so without this step the right match scores exactly zero.
        val latin =
            if (Transliterate.hasDevanagari(text)) Transliterate.devanagari(text) else text
        val decomposed = Normalizer.normalize(latin, Normalizer.Form.NFKD)
        val stripped = decomposed.filter { !it.isMark() }
        return stripped.lowercase()
            // Apostrophes are dropped rather than turned into a space, so
            // "Don't Stop" and "Dont Stop" come out as the same two words
            // instead of three words against two.
            .filter { it !in "'\u2019\u02BC`" }
            .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
            .joinToString("")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun Char.isMark(): Boolean = when (Character.getType(this).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK -> true
        else -> false
    }

    /**
     * Words, folded so that two spellings of the same Indian title come out the
     * same. See [Transliterate] for why that is necessary at all.
     */
    private fun tokens(text: String?): Set<String> =
        normalise(text).split(' ')
            .filter { it.isNotBlank() }
            .map { Transliterate.latinFold(it) }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Proportion of the shorter side's words that appear on both.
     *
     * Words are compared through [Transliterate.sameWord] rather than by
     * equality, because a transliterated title has no single correct spelling
     * and exact comparison scored the right answer at zero.
     */
    fun tokenOverlap(a: String?, b: String?): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val shared = ta.count { word -> tb.any { Transliterate.sameWord(word, it) } }
        return minOf(shared, minOf(ta.size, tb.size)).toDouble() / minOf(ta.size, tb.size)
    }

    /**
     * A candidate's title with its bracketed qualifier taken off.
     *
     * Shops put a great deal inside brackets, and it is not all the same kind
     * of thing:
     *
     *     Nachle Na (From "Dil Juunglee")     the same song, longer name
     *     Obsession (feat. Dua Lipa)          the same song, guest credited
     *     Butter (Megan Thee Stallion Remix)  somebody else's record entirely
     *
     * The first two are the record the filename is asking for. The third only
     * has the artist's name in it, and compared whole the three are
     * indistinguishable -- [tokenOverlap] measures against the shorter side,
     * so the three words "Megan Thee Stallion" sitting inside that five-word
     * title came out at 1.0 and were reported to the user as "title matches
     * exactly".
     *
     * That is what put "Butter -- BTS & Megan Thee Stallion" top of the list
     * at 84% for a file called `Megan Thee Stallion | Fantasy Pool Party`:
     * confident enough to write on its own, and wrong. The head of the title
     * is the part that has to agree.
     *
     * A blank head falls back to the whole title, because a record really can
     * be called "(Everything I Do) I Do It for You".
     */
    fun headline(title: String?): String {
        val text = title ?: return ""
        val at = text.indexOfFirst { it == '(' || it == '[' }
        if (at <= 0) return text
        return text.substring(0, at).trim().ifBlank { text }
    }

    /**
     * How well a name matches a candidate's title.
     *
     * The qualifier is dropped rather than weighed. Nothing is lost in the
     * other direction: a filename that spells the qualifier out in full --
     * "Butter Megan Thee Stallion Remix" -- is the longer side, so every word
     * of the head still counts and the match is still exact.
     */
    fun titleMatch(mine: String?, theirs: String?): Double =
        tokenOverlap(mine, headline(theirs))

    /**
     * Scores episodes, which are a different problem from songs.
     *
     * A season and episode number is an exact answer, so there is nothing to
     * rank on the words -- every result is the right number of the show it came
     * from, and the only question left is whether it is the right show.
     *
     * Length is what answers that, and it was being thrown away. A file of
     * "House of the Dragon The House That Dragons Built S03E08" matched the
     * aftershow of that name at 95%: same number, right show for the words in
     * the name, and twenty-two minutes against the file's seventy. Confident
     * enough to apply on its own, and wrong. An episode three times the length
     * of what came back is not that episode, whatever it is called.
     *
     * Where a length is missing on either side the number stands on its own, as
     * it did before -- an unknown is not evidence against.
     */
    fun rankEpisodes(found: List<Candidate>, durationMs: Int?): List<Scored> =
        found.map { candidate ->
            val reasons = ArrayList<String>()
            reasons += "season and episode matched"
            candidate.showName?.takeIf { it.isNotBlank() }?.let { reasons += it }

            val theirs = candidate.durationMs
            val score = if (theirs == null || theirs <= 0 || durationMs == null || durationMs <= 0) {
                reasons += "length not known"
                NUMBER_ONLY
            } else {
                val ratio = minOf(theirs, durationMs).toDouble() / maxOf(theirs, durationMs)
                when {
                    ratio >= CLOSE_ENOUGH -> {
                        reasons += "length agrees"
                        NUMBER_AND_LENGTH
                    }
                    ratio >= SOMEWHAT -> {
                        reasons += "length differs: " + minutes(theirs) +
                                " against this file's " + minutes(durationMs)
                        NUMBER_ODD_LENGTH
                    }
                    else -> {
                        reasons += "length is nothing like it: " + minutes(theirs) +
                                " against this file's " + minutes(durationMs) +
                                " -- probably a different series"
                        NUMBER_WRONG_LENGTH
                    }
                }
            }
            Scored(candidate, score, reasons)
        }.sortedByDescending { it.score }

    /** Within this of each other, two lengths are the same episode. */
    private const val CLOSE_ENOUGH = 0.85

    /** Below this, they are not the same programme at all. */
    private const val SOMEWHAT = 0.6

    private const val NUMBER_AND_LENGTH = 0.95
    private const val NUMBER_ONLY = 0.9
    private const val NUMBER_ODD_LENGTH = 0.7
    private const val NUMBER_WRONG_LENGTH = 0.3

    /**
     * A segment with the uploader's habit taken off the end.
     *
     * "Nachle Na Video" is the song plus a word that is in no catalogue
     * anywhere. Comparing with it attached costs a third of the overlap and
     * turns an exact match into a partial one.
     */
    private fun stripMarker(text: String): String =
        TRAILING_MARKER.replace(text, "").trim().ifBlank { text }

    private val TRAILING_MARKER = Regex(
        """\s+((official|full|hd|4k)\s+)*(video|audio|lyrical|lyrics|song)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun minutes(ms: Int): String {
        val total = ms / 1000
        return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
    }

    fun rank(
        candidates: List<Candidate>,
        parsed: ParsedName,
        /** Length of the actual video, when known: the strongest signal there is. */
        durationMs: Int? = null,
        preferredLanguage: String? = null,
    ): List<Scored> = candidates
        .map { score(it, parsed, durationMs, preferredLanguage) }
        .sortedByDescending { it.score }

    private fun score(
        c: Candidate,
        parsed: ParsedName,
        durationMs: Int?,
        preferredLanguage: String?,
    ): Scored {
        var score = 0.0
        val reasons = ArrayList<String>()

        // The whole cleaned filename against "artist title" -- catches the case
        // where the dash split guessed the two the wrong way round.
        val whole = tokenOverlap(parsed.query, listOfNotNull(c.artist, c.title).joinToString(" "))
        score += whole * 0.35

        /*
           The title, against every part of the name that could be one.

           Artist and album have always been tried against the segments after
           the pipes; the title was not, and that asymmetry was the whole fault
           in a real report. A file called

             Guru Randhawa | Nachle Na Video | DIL JUUNGLEE | Neeti M | ...

           scored "AZUL by Guru Randhawa" at 73% -- artist right, title
           matching nothing at all, runtime a coincidence -- and the actual
           song, "Nachle Na (From Dil Juunglee)", tenth at 63%, because the
           only place the words "Nachle Na" appeared was in a segment the title
           check never looked at.
        */
        val titleHit = maxOf(
            titleMatch(parsed.title, c.title),
            // The film convention puts the song first, so the parser's "artist"
            // may in fact be the title. Try it both ways.
            titleMatch(parsed.artist, c.title),
            parsed.extras.maxOfOrNull { titleMatch(stripMarker(it), c.title) } ?: 0.0,
        )
        score += titleHit * 0.30
        if (titleHit >= 0.99) reasons += "title matches exactly"
        else if (titleHit >= 0.6) reasons += "title mostly matches"

        // Said here rather than above, because it is only true with the title
        // check in hand. Every word of the filename can be accounted for by
        // the candidate's *artist* alone -- an artist's name and a record of
        // theirs the file has nothing to do with -- and claiming the title
        // matched in that case is the reason a wrong match looked convincing.
        if (whole >= 0.75 && titleHit >= 0.5) reasons += "filename matches artist and title"

        val artistHit = maxOf(
            tokenOverlap(parsed.artist, c.artist),
            tokenOverlap(parsed.title, c.artist),
            // Singers are often listed after pipes in the filename.
            parsed.extras.maxOfOrNull { tokenOverlap(it, c.artist) } ?: 0.0,
        )
        score += artistHit * 0.20
        if (artistHit >= 0.75) reasons += "artist matches"

        /*
           A title that matches and an artist that matches nothing.

           "Obsession" is a song title thirty-seven records share. When the
           filename names an artist and the candidate's has nothing to do with
           it, the title alone is not evidence -- it is a coincidence, and
           without this the top of the list is whichever stranger the shop
           happened to return first.
        */
        val artistNamed = !parsed.artist.isNullOrBlank()
        /*
           Unless the field called "artist" was the title all along.

           "Kesariya - Brahmastra" parses as artist Kesariya, title Brahmastra,
           and the song is the other way round -- so an artist field that
           matched the candidate's *title* is evidence the parse was inverted,
           not evidence of a wrong artist. Penalising it broke a test that has
           guarded that case since the beginning, which is what tests are for.
        */
        val artistWasReallyTheTitle = titleMatch(parsed.artist, c.title) >= 0.6
        if (artistNamed && !artistWasReallyTheTitle && titleHit >= 0.6 && artistHit < 0.2) {
            score -= 0.12
            reasons += "but nothing in the name matches this artist"
        }

        val albumHit = maxOf(
            tokenOverlap(parsed.album, c.album),
            parsed.extras.maxOfOrNull { tokenOverlap(it, c.album) } ?: 0.0,
        )
        score += albumHit * 0.08
        if (albumHit >= 0.75) reasons += "album or film matches"

        val candidateYear = c.year
        if (parsed.year != null && candidateYear != null) {
            if (parsed.year == candidateYear) {
                score += 0.05
                reasons += "year matches"
            } else if (Math.abs(parsed.year.toInt() - candidateYear.toInt()) > 2) {
                score -= 0.05
            }
        }

        // Duration is worth a lot when it lines up, because titles repeat and
        // running times do not. Within three seconds is the same recording.
        if (durationMs != null && c.durationMs != null && c.durationMs > 0) {
            val gapSec = Math.abs(durationMs - c.durationMs) / 1000.0
            // A song entry's length is the audio track. The video of the same
            // song routinely runs a minute longer -- an intro, dialogue, a fade
            // -- so only a like-for-like entry may be punished for a gap.
            // Penalising the rest was demoting correct matches.
            val comparable = c.kind == "musicVideo"
            when {
                gapSec <= 3 -> {
                    score += 0.15
                    reasons += "length matches within 3s"
                }
                gapSec <= 10 -> score += 0.05
                comparable && gapSec > 45 -> {
                    score -= 0.20
                    reasons += "length is off by " + gapSec.toInt() + "s"
                }
                !comparable && gapSec > 150 -> {
                    score -= 0.15
                    reasons += "length is off by " + gapSec.toInt() + "s"
                }
            }
        }

        val language = preferredLanguage ?: parsed.language
        if (language != null && c.language == language) {
            score += 0.05
            reasons += Languages.displayName(language) + " matches"
        }

        // A music-video entry is the better description of the file, but its
        // artwork is a video still; the artwork rule handles that separately.
        if (c.kind == "musicVideo") score += 0.03

        return Scored(c, score.coerceIn(0.0, 1.0), reasons)
    }
}

/**
 * Which picture to embed.
 *
 * The rule asked for: the album front for English, the film cover for Hindi.
 * Those are the same instruction underneath -- use the *release* artwork, not a
 * frame from the video -- because a Hindi song's release is its film
 * soundtrack, whose cover is the film poster.
 *
 * So the ordering is always: release artwork first, video stills last, and for
 * Indian-language tracks a soundtrack release is preferred over a compilation,
 * with a real film poster from [Tmdb] ahead of both when a key is configured.
 */
object ArtworkPlan {

    /**
     * Artwork URLs to try in order for [chosen], drawing on the other
     * [alternatives] returned by the same search.
     *
     * [tmdbPosterUrls] is whatever the film lookup produced, or empty.
     */
    fun urls(
        chosen: Candidate,
        alternatives: List<Candidate> = emptyList(),
        language: String? = null,
        tmdbPosterUrls: List<String> = emptyList(),
    ): List<String> {
        val lang = language ?: chosen.language
        val isFilmMusic = lang != null && lang in Languages.INDIAN_FILM
        val out = LinkedHashSet<String>()

        // A genuine film poster, when the optional film lookup found one.
        if (isFilmMusic) out += tmdbPosterUrls

        // Release artwork from entries that describe the same recording. A song
        // entry carries the album cover; a music-video entry carries a still.
        val sameRecording = (listOf(chosen) + alternatives).filter {
            it.kind != "musicVideo" && sameEnough(it, chosen)
        }
        val soundtrackFirst = sameRecording.sortedByDescending {
            if (isFilmMusic && looksLikeSoundtrack(it.album)) 1 else 0
        }
        for (c in soundtrackFirst) out += c.artworkUrls

        // Anything else from the same search, then the video still as a last resort.
        for (c in alternatives) if (c.kind != "musicVideo") out += c.artworkUrls
        out += chosen.artworkUrls
        for (c in alternatives) out += c.artworkUrls

        return out.toList()
    }

    fun looksLikeSoundtrack(album: String?): Boolean {
        val a = album ?: return false
        return listOf(
            "original motion picture", "soundtrack", "original soundtrack",
            "motion picture", "from the film", "film version",
        ).any { a.contains(it, ignoreCase = true) }
    }

    private fun sameEnough(a: Candidate, b: Candidate): Boolean =
        Matching.tokenOverlap(a.title, b.title) >= 0.6 &&
                (a.artist == null || b.artist == null ||
                        Matching.tokenOverlap(a.artist, b.artist) >= 0.5)
}
