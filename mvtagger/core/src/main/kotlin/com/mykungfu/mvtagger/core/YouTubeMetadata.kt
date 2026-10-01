package com.mykungfu.mvtagger.core

/**
 * Reading a YouTube search result as something to tag a file with.
 *
 * This is where most of the collection came from, and it is the only source
 * that has a good deal of it. Nothing called "Fantasy Pool Party" is for sale
 * in any shop; it is a live set, and it is on YouTube under exactly that name.
 * Three reports on that one file ended with the shops having nothing, which is
 * the whole argument for asking the place the file came from.
 *
 * Two things have to be true of it, and they pull against each other.
 *
 * A YouTube title is not catalogue metadata. It is the same kind of string a
 * filename is -- a song, an artist, a label, "Official Video", a resolution,
 * all run together by whoever uploaded it -- so it goes through
 * [FilenameParser], the same cleaning the filename itself gets. Writing a
 * YouTube title into a file verbatim would put "(Official Music Video) [4K]"
 * in a library.
 *
 * And agreement with YouTube is weaker evidence than it looks, because the
 * filename is usually a YouTube title already. A result whose name matches the
 * file proves only that the file was downloaded from there, which was never in
 * doubt. What YouTube adds that the filename does not carry is the channel and
 * the running time, and the running time is the one piece that cannot be
 * circular. [Matching] is where that argument is enforced.
 */
object YouTubeMetadata {

    const val SOURCE = "YouTube"

    /** What [Candidate.kind] says for one of these, so the scoring can tell. */
    const val KIND = "youtube"

    /**
     * A quoted run inside a title, which is the song.
     *
     *     Megan Thee Stallion "Fantasy Pool Party"
     *
     * The convention is a channel's own: the channel is named first and the
     * thing being performed is in quotes. Both halves are needed and neither
     * is where a dash or a pipe would put it, so nothing else in this app
     * would have found them.
     */
    private val QUOTED = Regex("""["“‘']([^"“”‘’']{2,})["”’']""")

    /**
     * Channel suffixes that are not part of anybody's name.
     *
     * Short on purpose. " - Topic" is YouTube's own mark on an auto-generated
     * channel, and a Vevo channel is literally the artist's name with VEVO
     * stuck on the end. Everything else a channel calls itself is its name,
     * and the scoring is allowed to judge it.
     */
    private val CHANNEL_SUFFIXES = listOf(" - Topic", "VEVO", " - Official", " Official")

    /** The artist a channel name amounts to, or null if it amounts to nothing. */
    fun channelAsArtist(channel: String?): String? {
        var name = channel?.trim() ?: return null
        for (suffix in CHANNEL_SUFFIXES) {
            if (name.endsWith(suffix, ignoreCase = true)) {
                name = name.dropLast(suffix.length).trim()
            }
        }
        return name.ifBlank { null }
    }

    /**
     * One search result, read for what it can be trusted to say.
     *
     * Returns null for a result with no title, and for a live broadcast, which
     * has no length and is not a recording of anything yet.
     *
     * No date. A search result carries only a relative one -- "2 years ago" --
     * and a year worked out from that is a guess, which is not what this app
     * writes into files.
     */
    fun candidate(
        videoTitle: String?,
        channel: String?,
        durationSeconds: Long,
        thumbnailUrl: String?,
        watchUrl: String?,
    ): Candidate? {
        val raw = videoTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val quoted = QUOTED.find(raw)
        val title: String?
        val fromTitle: String?
        if (quoted != null) {
            title = quoted.groupValues[1].trim()
            // Whatever came before the quotes is who was performing.
            fromTitle = raw.substring(0, quoted.range.first).trim()
                .trim('-', '–', '—', '|', ',', ':')
                .trim()
                .ifBlank { null }
        } else {
            val parsed = FilenameParser.parse(raw)
            title = parsed.title?.trim()?.ifBlank { null }
            fromTitle = parsed.artist?.trim()?.ifBlank { null }
        }

        val name = title ?: return null
        return Candidate(
            source = SOURCE,
            id = watchUrl?.takeIf { it.isNotBlank() } ?: raw,
            title = name,
            // The uploader is the better answer where the title named nobody:
            // a channel is an account with a name, where a title is a habit.
            artist = fromTitle ?: channelAsArtist(channel),
            durationMs = if (durationSeconds > 0) (durationSeconds * 1000L).toInt() else null,
            artworkUrls = listOfNotNull(thumbnailUrl?.takeIf { it.isNotBlank() }),
            kind = KIND,
            mediaKind = MediaKind.MUSIC_VIDEO,
        )
    }
}
