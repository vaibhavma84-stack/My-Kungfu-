package com.mykungfu.mvtagger.core

/** One line of subtitle, and when it is on screen. */
data class Cue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/**
 * A subtitle track: the cues plus what language they are in.
 *
 * [language] is an ISO 639-1 code where one is known, because that is what goes
 * in the filename a player looks for (`Film.en.srt`) and in the MP4 track
 * header.
 */
data class SubtitleTrack(
    val cues: List<Cue>,
    val language: String? = null,
    /** Where it came from, for the message after saving. */
    val source: String? = null,
    val forced: Boolean = false,
) {
    val isEmpty: Boolean get() = cues.isEmpty()
    val durationMs: Long get() = cues.maxOfOrNull { it.endMs } ?: 0L
}

/**
 * Reading and writing the subtitle formats that actually turn up.
 *
 * SubRip is what nearly everything ships as, WebVTT is what streaming sites
 * hand out, and SubStation Alpha is what fansubs use. All three are read; only
 * SubRip is written, because it is the one every player understands.
 */
object Subtitles {

    // --- SubRip -------------------------------------------------------------

    /** `00:01:23,456` and the WebVTT spelling `00:01:23.456`. */
    private val SRT_TIME = Regex("""(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    /** A line holding both timestamps of a cue. */
    private val SRT_RANGE = Regex(
        """(\d{1,3}:\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*(\d{1,3}:\d{2}:\d{2}[,.]\d{1,3})"""
    )

    /** Inline markup that should not be shown as text. */
    private val TAGS = Regex("""</?[a-zA-Z][^>]*>""")

    /** SubStation override codes, e.g. `{\an8}`. */
    private val ASS_OVERRIDES = Regex("""\{[^}]*\}""")

    fun parseTimestamp(text: String): Long? {
        val m = SRT_TIME.find(text) ?: return null
        val (h, min, sec, frac) = m.destructured
        val millis = frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return h.toLong() * 3_600_000 + min.toLong() * 60_000 + sec.toLong() * 1_000 + millis
    }

    fun formatTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3_600_000
        val min = (safe / 60_000) % 60
        val sec = (safe / 1_000) % 60
        val millis = safe % 1_000
        return String.format("%02d:%02d:%02d,%03d", h, min, sec, millis)
    }

    /**
     * Reads SubRip or WebVTT.
     *
     * The two differ only in the millisecond separator and a header, so one
     * reader covers both rather than having two that drift apart.
     */
    fun parseSrt(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val lines = text.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var i = 0
        while (i < lines.size) {
            val range = SRT_RANGE.find(lines[i])
            if (range == null) {
                i++
                continue
            }
            val start = parseTimestamp(range.groupValues[1])
            val end = parseTimestamp(range.groupValues[2])
            i++

            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && SRT_RANGE.find(lines[i]) == null) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            // A cue number on its own line belongs to the *next* cue; putting it
            // back keeps the numbering from being swallowed into this one.
            var content = body.toString().trimEnd()
            val lastLine = content.substringAfterLast('\n', content)
            if (content.isNotEmpty() && lastLine.trim().toIntOrNull() != null &&
                content.contains('\n')
            ) {
                content = content.substringBeforeLast('\n').trimEnd()
            }

            val cleaned = clean(content)
            if (start != null && end != null && cleaned.isNotBlank()) {
                cues += Cue(start, maxOf(end, start + 1), cleaned)
            }
        }
        return cues
    }

    /**
     * Reads SubStation Alpha, which stores cues as comma-separated `Dialogue:`
     * rows with the text last.
     */
    fun parseAss(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val line = raw.trim()
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            // Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            val fields = line.removePrefix("Dialogue:").trim().split(',', limit = 10)
            if (fields.size < 10) continue
            val start = parseAssTime(fields[1]) ?: continue
            val end = parseAssTime(fields[2]) ?: continue
            val body = fields[9].replace("\\N", "\n").replace("\\n", "\n")
            val cleaned = clean(ASS_OVERRIDES.replace(body, ""))
            if (cleaned.isNotBlank()) cues += Cue(start, maxOf(end, start + 1), cleaned)
        }
        return cues
    }

    /** SubStation times look like `0:01:23.45`, with hundredths. */
    private fun parseAssTime(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size != 3) return null
        val h = parts[0].trim().toLongOrNull() ?: return null
        val m = parts[1].trim().toLongOrNull() ?: return null
        val s = parts[2].trim().toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
    }

    /** Picks the reader by what the text looks like, not by its extension. */
    fun parse(text: String): List<Cue> = when {
        text.contains("Dialogue:", ignoreCase = true) &&
                text.contains("[Events]", ignoreCase = true) -> parseAss(text)
        else -> parseSrt(text)
    }

    /** Strips markup and blank lines, leaving what should be read on screen. */
    private fun clean(text: String): String =
        TAGS.replace(text, "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .lines().map { it.trim() }.filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()

    /** The cues as a SubRip file, which is what every player reads. */
    fun toSrt(cues: List<Cue>): String {
        val out = StringBuilder()
        for ((index, cue) in cues.sortedBy { it.startMs }.withIndex()) {
            out.append(index + 1).append('\n')
            out.append(formatTimestamp(cue.startMs))
            out.append(" --> ")
            out.append(formatTimestamp(cue.endMs)).append('\n')
            out.append(cue.text).append("\n\n")
        }
        return out.toString()
    }

    /**
     * Overlapping and out-of-order cues, made into a clean sequence.
     *
     * A text track inside an MP4 shows exactly one sample at a time, so
     * overlaps have to be resolved before writing; two subtitles that overlap
     * in an SRT would otherwise cut each other off at the wrong moment.
     */
    fun tidy(cues: List<Cue>): List<Cue> {
        val sorted = cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }
            .sortedBy { it.startMs }
        val out = ArrayList<Cue>(sorted.size)
        for (cue in sorted) {
            val previous = out.lastOrNull()
            if (previous != null && cue.startMs < previous.endMs) {
                // Trim the earlier one rather than dropping either.
                out[out.size - 1] = previous.copy(endMs = cue.startMs)
                if (out.last().durationMs <= 0) out.removeAt(out.size - 1)
            }
            out += cue
        }
        return out.filter { it.durationMs > 0 }
    }

    /** `Film.en.srt`, the name players look for beside a video. */
    fun sidecarName(base: String, language: String?): String =
        if (language.isNullOrBlank()) "$base.srt" else "$base.$language.srt"
}
