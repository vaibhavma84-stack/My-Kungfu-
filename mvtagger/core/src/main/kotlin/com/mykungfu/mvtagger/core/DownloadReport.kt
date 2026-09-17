package com.mykungfu.mvtagger.core

/**
 * What happened when a download was attempted, in words that can be pasted
 * back to whoever maintains this.
 *
 * The same idea as [SearchReport] and for the same reason. Downloading from
 * YouTube cannot be tested from where this code is written -- there is no
 * phone and no YouTube -- so "it does not work" is a sentence with a dozen
 * possible causes and no way to tell them apart from a distance.
 *
 * This narrows it to one. The interesting line is the list of streams: if the
 * site offered a dozen and none were usable, the choosing is at fault and is
 * fixable in an afternoon; if it offered none at all, the extractor is out of
 * date and the fix is a version; if it never got that far, the message says
 * which door was shut.
 */
object DownloadReport {

    fun of(
        link: String,
        extractorVersion: String,
        offered: List<Downloads.Option>,
        chosen: Downloads.Choice?,
        stage: String,
        failure: String?,
    ): String = buildString {
        appendLine("Media Centre download report")
        appendLine("Link: " + link.ifBlank { "(none)" })
        appendLine("Extractor: " + extractorVersion)
        appendLine("Stage: " + stage)
        failure?.let { appendLine("Trouble: " + it) }
        appendLine()

        appendLine("Streams offered: " + offered.size)
        if (offered.isNotEmpty()) {
            describe("Complete", offered.filter { it.isProgressive })
            describe("Video only", offered.filter { it.hasVideo && !it.hasAudio })
            describe("Sound only", offered.filter { it.hasAudio && !it.hasVideo })
        }
        appendLine()

        val video = chosen?.video
        if (video == null) {
            appendLine("Chosen: nothing usable")
        } else {
            append("Chosen: ").append(video.label).append(" ").append(video.container)
            chosen.audio?.let { append(" joined to ").append(it.label).append(" ").append(it.container) }
            appendLine()
            if (chosen.cappedFrom > 0) appendLine("Passed over: " + chosen.cappedFrom + "p")
            chosen.warning?.let { appendLine("Warning: " + it) }
        }
    }

    private fun size(bytes: Long): String? = Downloads.size(bytes)

    private fun StringBuilder.describe(what: String, streams: List<Downloads.Option>) {
        if (streams.isEmpty()) return
        append("  ").append(what).append(": ")
        appendLine(
            streams.joinToString(", ") {
                it.label + " " + it.container + (size(it.bytes)?.let { size -> " " + size } ?: "")
            }
        )
    }
}
