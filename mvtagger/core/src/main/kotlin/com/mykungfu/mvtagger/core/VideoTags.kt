package com.mykungfu.mvtagger.core

/**
 * Artwork bytes plus what they are, so the right MP4 type indicator gets
 * written and the right extension is used for a sidecar.
 */
data class Artwork(val bytes: ByteArray, val mime: String) {

    val isPng: Boolean get() = mime.contains("png", ignoreCase = true)

    /** Equality by content, so re-applying identical artwork is not a change. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Artwork) return false
        return mime == other.mime && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * mime.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "Artwork($mime, ${bytes.size} bytes)"

    companion object {
        /** Sniffs the format from the leading bytes; ignores what a server claimed. */
        fun of(bytes: ByteArray): Artwork? {
            if (bytes.size < 12) return null
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            return when {
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
                    Artwork(bytes, "image/jpeg")
                bytes.copyOfRange(0, 4).contentEquals(png) -> Artwork(bytes, "image/png")
                else -> null
            }
        }
    }
}

/**
 * The metadata this app reads, edits and writes.
 *
 * Field names follow the MP4 atoms rather than any one music service, with two
 * borrowed from how Indian film music is actually credited: [album] carries the
 * film name (a Hindi song's "album" is its film soundtrack) and [composer] the
 * music director. [lyricist] has no standard atom and is written as a freeform
 * one, the same way Picard does it.
 */
data class VideoTags(
    /** What the file is. Decides the `stik` atom and the rename template. */
    val mediaKind: MediaKind = MediaKind.MUSIC_VIDEO,
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    /** Album, or for film music the name of the film. */
    val album: String? = null,
    /** `2019` or `2019-05-01`; whatever the source gave, not reformatted. */
    val date: String? = null,
    val genre: String? = null,
    val comment: String? = null,
    val description: String? = null,
    /** Apple's long description atom, which is not capped the way `desc` is. */
    val longDescription: String? = null,
    /** Background on the performer, embedded so it travels with the file. */
    val artistBio: String? = null,
    /** Background on the album, or on the film for a soundtrack. */
    val albumInfo: String? = null,
    /** Composer, or for film music the music director. */
    val composer: String? = null,
    val lyricist: String? = null,
    /** Plain lyrics, written to the `©lyr` atom that players read. */
    val lyrics: String? = null,
    /**
     * Timestamped lyrics in LRC form. MP4 has no atom for these, so they go to
     * a freeform one and to a `.lrc` file beside the video, which is where a
     * player actually looks for them.
     */
    val syncedLyrics: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    /** ISO 639-1 where one exists, else 639-3: `hi`, `en`, `pa`, `ta`. */
    val language: String? = null,
    /** Where the metadata came from, e.g. `iTunes` or `MusicBrainz`. */
    val source: String? = null,
    /** The source's own id for the match, so a lookup can be traced back. */
    val sourceId: String? = null,
    /** Series name, for a TV episode. */
    val showName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** Broadcaster or streaming service the episode came from. */
    val network: String? = null,
    val artwork: Artwork? = null,
) {
    /** The four-digit year, if [date] starts with one. */
    val year: String?
        get() = date?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

    val isEmpty: Boolean
        get() = listOf(
            title, artist, albumArtist, album, date, genre,
            comment, description, longDescription, artistBio, albumInfo,
            composer, lyricist, lyrics, syncedLyrics, language, showName, network
        ).all { it.isNullOrBlank() } &&
                trackNumber == null && seasonNumber == null &&
                episodeNumber == null && artwork == null

    /**
     * [other] wins wherever it has something to say. Used to lay a chosen
     * online match over what the file already carried, without blanking fields
     * the match did not cover.
     */
    fun overlaidWith(other: VideoTags): VideoTags = VideoTags(
        mediaKind = other.mediaKind,
        title = other.title?.ifBlank { null } ?: title,
        artist = other.artist?.ifBlank { null } ?: artist,
        albumArtist = other.albumArtist?.ifBlank { null } ?: albumArtist,
        album = other.album?.ifBlank { null } ?: album,
        date = other.date?.ifBlank { null } ?: date,
        genre = other.genre?.ifBlank { null } ?: genre,
        comment = other.comment?.ifBlank { null } ?: comment,
        description = other.description?.ifBlank { null } ?: description,
        longDescription = other.longDescription?.ifBlank { null } ?: longDescription,
        artistBio = other.artistBio?.ifBlank { null } ?: artistBio,
        albumInfo = other.albumInfo?.ifBlank { null } ?: albumInfo,
        composer = other.composer?.ifBlank { null } ?: composer,
        lyricist = other.lyricist?.ifBlank { null } ?: lyricist,
        lyrics = other.lyrics?.ifBlank { null } ?: lyrics,
        syncedLyrics = other.syncedLyrics?.ifBlank { null } ?: syncedLyrics,
        trackNumber = other.trackNumber ?: trackNumber,
        trackTotal = other.trackTotal ?: trackTotal,
        language = other.language?.ifBlank { null } ?: language,
        source = other.source?.ifBlank { null } ?: source,
        sourceId = other.sourceId?.ifBlank { null } ?: sourceId,
        showName = other.showName?.ifBlank { null } ?: showName,
        seasonNumber = other.seasonNumber ?: seasonNumber,
        episodeNumber = other.episodeNumber ?: episodeNumber,
        network = other.network?.ifBlank { null } ?: network,
        artwork = other.artwork ?: artwork,
    )
}
