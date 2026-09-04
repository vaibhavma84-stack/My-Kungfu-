package com.mykungfu.mvtagger.core

/**
 * The fallback for containers this app cannot write tags into.
 *
 * Tags belong *inside* the file -- that is the whole point, so the metadata
 * survives being copied to an iPad. Only MP4-family containers have somewhere
 * to put them, so for a `.webm` or `.mkv` the choice is between losing the
 * lookup and writing files beside the video. These sidecars are the second
 * option, and the app says plainly which one happened.
 *
 * The names follow what media players already look for: `.lrc` next to a video
 * is picked up for lyrics, and a `-poster.jpg` or `.nfo` is read by most
 * library apps.
 */
object Sidecar {

    fun jsonName(base: String) = "$base.json"
    fun lrcName(base: String) = "$base.lrc"
    fun artworkName(base: String, art: Artwork) =
        base + "-poster." + if (art.isPng) "png" else "jpg"

    /** True when [Mp4Metadata] can write tags into a file with this name. */
    fun canEmbed(fileName: String): Boolean =
        FilenameParser.extensionOf(fileName).lowercase() in
                setOf("mp4", "m4v", "m4a", "mov", "qt")

    /** Everything known about the track, as JSON, for the `.json` sidecar. */
    fun json(tags: VideoTags, fileName: String? = null): String {
        val sb = StringBuilder("{\n")
        val fields = LinkedHashMap<String, String?>()
        fileName?.let { fields["file"] = it }
        fields["title"] = tags.title
        fields["artist"] = tags.artist
        fields["albumArtist"] = tags.albumArtist
        fields["album"] = tags.album
        fields["date"] = tags.date
        fields["year"] = tags.year
        fields["genre"] = tags.genre
        fields["language"] = tags.language
        fields["languageName"] = tags.language?.let { Languages.displayName(it) }
        fields["composer"] = tags.composer
        fields["lyricist"] = tags.lyricist
        fields["comment"] = tags.comment
        fields["description"] = tags.description
        fields["longDescription"] = tags.longDescription
        fields["artistBio"] = tags.artistBio
        fields["albumInfo"] = tags.albumInfo
        fields["lyrics"] = tags.lyrics
        fields["source"] = tags.source
        fields["sourceId"] = tags.sourceId
        // Without these an episode's sidecar says nothing about which episode
        // it is, which is most of what there is to know about one.
        fields["kind"] = tags.mediaKind.name
        fields["show"] = tags.showName
        fields["network"] = tags.network

        val parts = ArrayList<String>()
        for ((k, v) in fields) if (!v.isNullOrBlank()) {
            parts += "  " + quote(k) + ": " + quote(v)
        }
        tags.trackNumber?.let { parts += "  " + quote("trackNumber") + ": " + it }
        tags.trackTotal?.let { parts += "  " + quote("trackTotal") + ": " + it }
        tags.seasonNumber?.let { parts += "  " + quote("season") + ": " + it }
        tags.episodeNumber?.let { parts += "  " + quote("episode") + ": " + it }
        sb.append(parts.joinToString(",\n"))
        sb.append("\n}\n")
        return sb.toString()
    }

    private fun quote(text: String): String {
        val sb = StringBuilder("\"")
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    // Anything below a space has to be escaped; everything else,
                    // Devanagari included, is written as-is in UTF-8.
                    if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code))
                    else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * An `.lrc` file. Timestamped lyrics are used as they are; plain lyrics get
     * the header only, which is still what a player expects to find.
     */
    /**
     * Reads a `.json` sidecar back.
     *
     * This existed only as a writer, which made it a note to nobody. A file
     * that cannot hold tags inside it -- an MKV episode, most often -- had its
     * details written here and then had them read from nowhere: the library
     * went back to the filename, and a correction saved to the sidecar was
     * invisible the moment the folder was rescanned. It looked exactly like
     * the save had failed.
     *
     * Returns null for anything that is not a readable sidecar, so a stray
     * .json sitting beside a video cannot poison an entry.
     */
    fun parse(text: String): VideoTags? {
        val json = Json.parseOrNull(text)
        fun str(name: String) = json[name].string?.trim()?.ifBlank { null }

        val kind = runCatching { MediaKind.valueOf(json["kind"].string ?: "") }
            .getOrDefault(MediaKind.MUSIC_VIDEO)

        val tags = VideoTags(
            mediaKind = kind,
            title = str("title"),
            artist = str("artist"),
            albumArtist = str("albumArtist"),
            album = str("album"),
            date = str("date") ?: str("year"),
            genre = str("genre"),
            language = str("language"),
            composer = str("composer"),
            lyricist = str("lyricist"),
            comment = str("comment"),
            description = str("description"),
            longDescription = str("longDescription"),
            artistBio = str("artistBio"),
            albumInfo = str("albumInfo"),
            lyrics = str("lyrics"),
            source = str("source"),
            sourceId = str("sourceId"),
            showName = str("show"),
            network = str("network"),
            trackNumber = json["trackNumber"].int,
            trackTotal = json["trackTotal"].int,
            seasonNumber = json["season"].int,
            episodeNumber = json["episode"].int,
        )
        return if (tags.isEmpty) null else tags
    }

    fun lrc(tags: VideoTags): String? {
        val body = tags.syncedLyrics?.takeIf { it.isNotBlank() }
            ?: tags.lyrics?.takeIf { it.isNotBlank() }
            ?: return null
        val header = buildList {
            tags.title?.let { add("[ti:$it]") }
            tags.artist?.let { add("[ar:$it]") }
            tags.album?.let { add("[al:$it]") }
            add("[re:" + Mp4Metadata.ENCODER_NAME + "]")
        }
        return header.joinToString("\n") + "\n" + body.trimEnd() + "\n"
    }
}
