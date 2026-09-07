package com.mykungfu.mvtagger.core

/**
 * Lyrics with times against them, and lyrics without.
 *
 * The app has fetched both since early on and written them into files and
 * beside them, where every one of them has been for something else to read.
 * This reads them back so they can go on the screen while the song plays,
 * which is what a lyric fetched for a music video was always for.
 *
 * The format is the `.lrc` one, which is thirty years old and barely specified:
 *
 *     [ar:Arijit Singh]
 *     [00:12.30]Tum hi ho
 *     [00:15.10][01:42.10]Ab tum hi ho
 *
 * A line may carry several times, because a chorus is sung more than once and
 * the format would rather not repeat the words. Lines in square brackets whose
 * first part is not a number are metadata about the song rather than words in
 * it, and are dropped.
 *
 * A file with no times in it at all is not a failure. Plenty of lyrics arrive
 * as plain text, and plain text on the screen is still worth having -- it just
 * cannot follow along, so it is offered as something to scroll instead.
 */
object Lrc {

    /** One line of words, and when it starts being sung. */
    class Line(val atMs: Long, val text: String)

    /**
     * What was in the file.
     *
     * [lines] is empty when nothing carried a time, in which case [plain] is
     * the words as they were written.
     */
    class Song(val lines: List<Line>, val plain: String?) {
        val isSynced: Boolean get() = lines.isNotEmpty()
        val isEmpty: Boolean get() = lines.isEmpty() && plain.isNullOrBlank()
    }

    /** `[01:02.34]`, `[01:02:34]` and `[1:02]` are all seen in the wild. */
    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** `[ar:...]`, `[ti:...]`, `[offset:+250]` and the rest. */
    private val META = Regex("""^\[([a-zA-Z#]+):(.*)]$""")

    fun parse(text: String?): Song? {
        if (text.isNullOrBlank()) return null

        val lines = ArrayList<Line>()
        val loose = ArrayList<String>()
        var offset = 0L

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val meta = META.matchEntire(line)
            if (meta != null) {
                // The one piece of metadata that changes what is shown. The
                // convention is that a positive offset means the words are
                // early and should be pushed later, which is the reading every
                // player that supports it uses.
                if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    offset = meta.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0L
                }
                // And then it is done with: falling through to the words below
                // would file "[ar:Arijit Singh]" as something he sang.
                continue
            }

            val stamps = STAMP.findAll(line).takeWhile { it.range.first <= line.length }.toList()
            val leading = stamps.filter { it.range.first == 0 || startsRun(line, it) }
            if (leading.isEmpty()) {
                loose += line
                continue
            }

            val words = line.substring(leading.last().range.last + 1).trim()
            for (stamp in leading) {
                lines += Line(msOf(stamp, offset), words)
            }
        }

        // Sorted because a chorus repeated with a second timestamp arrives out
        // of order by definition, and stable so two lines sharing a time keep
        // the order they were written in.
        val ordered = lines.sortedBy { it.atMs }
        val plain = if (ordered.isEmpty()) {
            loose.joinToString("\n").ifBlank { null }
        } else {
            ordered.filter { it.text.isNotBlank() }.joinToString("\n") { it.text }.ifBlank { null }
        }

        val song = Song(ordered, plain)
        return if (song.isEmpty) null else song
    }

    /**
     * Whether this timestamp is part of the run at the front of the line
     * rather than one that happens to appear inside the words.
     */
    private fun startsRun(line: String, stamp: MatchResult): Boolean =
        line.take(stamp.range.first).all { it == '[' || it == ']' || it.isDigit() ||
                it == ':' || it == '.' }

    private fun msOf(stamp: MatchResult, offset: Long): Long {
        val minutes = stamp.groupValues[1].toLong()
        val seconds = stamp.groupValues[2].toLong()
        val fraction = stamp.groupValues[3]
        // Two digits are hundredths, three are milliseconds, which is the one
        // place this format quietly disagrees with itself.
        val sub = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return (minutes * 60_000 + seconds * 1000 + sub + offset).coerceAtLeast(0L)
    }

    /**
     * Which line is being sung at [positionMs], or -1 before the first one.
     *
     * The last line at or before the position, which is what makes a line stay
     * up until the next one arrives rather than flashing and going.
     */
    fun indexAt(lines: List<Line>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) / 2
            if (lines[middle].atMs <= positionMs) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }
}
