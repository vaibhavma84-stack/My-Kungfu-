package com.mykungfu.mvtagger.core

/**
 * What a lookup actually saw, as plain text to copy out of the app.
 *
 * This exists because of a hole in how the app gets built. The matching code
 * can be reasoned about and tested here, but the searches themselves cannot be
 * run from where it is written -- iTunes, MusicBrainz and the rest are not
 * reachable from there, so "this song does not match" has so far had to be
 * guessed at from the song's name alone. Guessing produced three rounds of
 * changes that did not fix it.
 *
 * A report says which of the four possible things went wrong, and they need
 * completely different fixes:
 *
 * 1. the name was read wrongly, so the search asked for the wrong thing;
 * 2. the search asked correctly and the source returned nothing;
 * 3. the right answer came back and scored too low to be offered first;
 * 4. the right answer was offered and the threshold held it back.
 *
 * Nothing here leaves the phone by itself. It goes on the clipboard when the
 * button is pressed, and the person pressing it decides where it goes.
 */
object SearchReport {

    /** How many of the candidates are worth listing. Past ten is noise. */
    private const val LISTED = 10

    /** What the filename gave a music video, in the order worth reading. */
    fun readFrom(parsed: ParsedName): List<Pair<String, String?>> = listOf(
        "title" to parsed.title,
        "artist" to parsed.artist,
        "album/film" to parsed.album,
        "year" to parsed.year,
        "language" to parsed.language,
        "also on the name" to parsed.extras.joinToString(" | ").ifBlank { null },
    )

    /** The same for a film or an episode. */
    fun readFrom(media: ParsedMedia): List<Pair<String, String?>> = listOf(
        "name" to media.name,
        "season" to media.season?.toString(),
        "episode" to media.episode?.toString(),
        "episode title" to media.episodeTitle,
        "year" to media.year,
    )

    fun of(
        fileName: String,
        kind: MediaKind,
        readFromName: List<Pair<String, String?>>,
        queries: List<String>,
        durationMs: Int?,
        preferredLanguage: String?,
        ranked: List<Matching.Scored>,
        all: List<Candidate>,
        threshold: Double,
    ): String {
        val out = StringBuilder()
        out.append("Media Centre search report\n\n")
        out.append("File      ").append(fileName).append('\n')
        out.append("Treated as ").append(kind.label).append('\n')
        out.append("Runs      ").append(duration(durationMs)).append('\n')
        out.append("Preferred language  ")
            .append(preferredLanguage?.let { Languages.displayName(it) } ?: "none set")
            .append('\n')

        out.append("\nRead from the name\n")
        for ((label, value) in readFromName) {
            out.append("  ").append(label.padEnd(18)).append(value ?: "—").append('\n')
        }
        out.append("  ").append("searched for".padEnd(18))
            .append(if (queries.isEmpty()) "—" else queries.joinToString("  |  "))
            .append('\n')

        out.append("\nCame back\n")
        if (all.isEmpty()) {
            out.append("  Nothing, from any source.\n")
        } else {
            val bySource = all.groupBy { it.source }
                .map { (source, items) -> source + " " + items.size }
                .sorted()
            out.append("  ").append(all.size).append(" in all — ")
                .append(bySource.joinToString(", ")).append('\n')
        }

        out.append("\nScored (applies on its own at ")
            .append(percent(threshold)).append(")\n")
        if (ranked.isEmpty()) {
            out.append("  Nothing was scored.\n")
        } else {
            for ((index, scored) in ranked.take(LISTED).withIndex()) {
                val c = scored.candidate
                out.append("  ").append((index + 1).toString().padStart(2)).append(". ")
                    .append(percent(scored.score).padStart(4)).append("  ")
                    .append(c.title)
                listOfNotNull(c.artist, c.album, c.date?.take(4)).takeIf { it.isNotEmpty() }
                    ?.let { out.append(" — ").append(it.joinToString(" — ")) }
                out.append("  [").append(c.source)
                if (c.kind.isNotBlank()) out.append('/').append(c.kind)
                c.storefront?.let { out.append('/').append(it) }
                out.append("]\n")
                if (scored.reasons.isNotEmpty()) {
                    out.append("        ").append(scored.reasons.joinToString(", ")).append('\n')
                }
                c.durationMs?.let {
                    out.append("        runs ").append(duration(it))
                    if (durationMs != null) {
                        val off = kotlin.math.abs(it - durationMs) / 1000
                        out.append(", ").append(off).append("s from this file")
                    }
                    out.append('\n')
                }
            }
            if (ranked.size > LISTED) {
                out.append("  … and ").append(ranked.size - LISTED).append(" more\n")
            }
        }

        out.append('\n').append(verdict(ranked, all, threshold)).append('\n')
        return out.toString()
    }

    /**
     * Which of the four failures this was.
     *
     * Stated in the report rather than left to be worked out, because the
     * person sending it should not have to interpret it and the person reading
     * it should not have to guess what they were looking at.
     */
    private fun verdict(
        ranked: List<Matching.Scored>,
        all: List<Candidate>,
        threshold: Double,
    ): String {
        val best = ranked.firstOrNull()
        return when {
            all.isEmpty() ->
                "Verdict: no source had anything for those words. Either the name was " +
                        "read wrongly, or nothing online is filed under it."
            best == null ->
                "Verdict: results came back but none could be scored."
            best.score >= threshold ->
                "Verdict: the top match was confident enough to apply on its own."
            else ->
                "Verdict: results came back and the best scored " + percent(best.score) +
                        ", under the " + percent(threshold) + " needed to apply on its " +
                        "own — so it was offered rather than taken. If the right one is " +
                        "in the list above, the scoring is what needs fixing; if it is " +
                        "not there at all, the search is."
        }
    }

    private fun percent(value: Double): String = (value * 100).toInt().toString() + "%"

    private fun duration(ms: Int?): String {
        if (ms == null || ms <= 0) return "not known"
        val seconds = ms / 1000
        return (seconds / 60).toString() + ":" + (seconds % 60).toString().padStart(2, '0')
    }
}
