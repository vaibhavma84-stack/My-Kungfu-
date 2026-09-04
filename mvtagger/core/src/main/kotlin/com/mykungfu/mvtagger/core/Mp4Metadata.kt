package com.mykungfu.mvtagger.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Reads and writes the iTunes-style metadata that lives in
 * `moov > udta > meta > ilst` of an MP4/M4V/MOV file.
 *
 * Why hand-rolled rather than a tagging library: the ones that handle MP4 are
 * either audio-only in practice, drag in a JitPack build, or are LGPL. The
 * atom layout is small and well specified, so it is written out here and
 * covered by tests instead.
 *
 * ### The part that is easy to get wrong
 *
 * `stco`/`co64` inside `moov` hold **absolute file offsets** of every chunk of
 * audio and video. Growing `moov` therefore moves the media and silently breaks
 * playback unless every one of those offsets is corrected. [write] rebuilds the
 * file, works out where each original top-level box ends up, and rewrites the
 * offsets through that map. It also moves `moov` in front of the media while it
 * is there, which is what "fast start" means and costs nothing here.
 *
 * Nothing is ever written in place: [write] streams to a new file, and the
 * caller swaps it in only once it is complete.
 */
object Mp4Metadata {

    /** The file is not something this writer can safely rewrite. */
    class UnsupportedContainer(message: String) : Exception(message)

    // Atom names. The leading byte is 0xA9 -- the copyright sign in Latin-1.
    private const val C = "©"
    private const val TITLE = "${C}nam"
    private const val ARTIST = "${C}ART"
    private const val ALBUM_ARTIST = "aART"
    private const val ALBUM = "${C}alb"
    private const val DATE = "${C}day"
    private const val GENRE = "${C}gen"
    private const val GENRE_ID3 = "gnre"
    private const val COMMENT = "${C}cmt"
    private const val COMPOSER = "${C}wrt"
    private const val ENCODER = "${C}too"
    private const val DESCRIPTION = "desc"
    private const val LONG_DESCRIPTION = "ldes"
    /** Apple's media kind: 6 music video, 9 movie, 10 TV show. See [MediaKind]. */
    private const val MEDIA_KIND = "stik"
    private const val TV_SHOW = "tvsh"
    private const val TV_SEASON = "tvsn"
    private const val TV_EPISODE = "tves"
    private const val TV_NETWORK = "tvnn"
    private const val LYRICS = "${C}lyr"
    private const val TRACK = "trkn"
    private const val COVER = "covr"
    private const val FREEFORM = "----"

    private const val MEAN = "com.apple.iTunes"
    private const val FF_LYRICIST = "LYRICIST"
    private const val FF_SYNCED_LYRICS = "LYRICS_SYNCED"
    private const val FF_LANGUAGE = "LANGUAGE"
    private const val FF_ARTIST_BIO = "ARTIST_BIO"
    private const val FF_ALBUM_INFO = "ALBUM_INFO"
    private const val FF_SOURCE = "MVTAGGER_SOURCE"
    private const val FF_SOURCE_ID = "MVTAGGER_SOURCE_ID"

    /** Data box type indicators. */
    private const val TYPE_IMPLICIT = 0
    private const val TYPE_UTF8 = 1
    private const val TYPE_INT = 21
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14

    /** A `moov` larger than this is not metadata, it is a broken file. */
    private const val MAX_MOOV = 128L * 1024 * 1024

    /** Written into `©too` so it is obvious which tool last touched the file. */
    const val ENCODER_NAME = "Media Centre"

    // ------------------------------------------------------------------ read

    /** Everything the file already carries. Missing metadata reads as empty tags. */
    fun read(src: Mp4.ByteSource): VideoTags {
        val moovRef = Mp4.topLevelBoxes(src).firstOrNull { it.type == "moov" }
            ?: throw UnsupportedContainer("no moov box: not an MP4-family file")
        if (moovRef.size > MAX_MOOV) throw UnsupportedContainer("moov box implausibly large")
        val moov = src.readFully(moovRef.start, moovRef.size.toInt())
        val ilst = findIlst(moov) ?: return VideoTags()
        return readIlst(moov, ilst)
    }

    private fun findIlst(moov: ByteArray): Mp4.BoxRef? {
        val moovHeader = headerSizeOf(moov, 0)
        val udta = Mp4.child(moov, moovHeader, moov.size, "udta") ?: return null
        val meta = Mp4.child(
            moov, udta.payloadStart.toInt(), udta.end.toInt(), "meta"
        ) ?: return null
        return Mp4.child(moov, metaChildrenStart(moov, meta), meta.end.toInt(), "ilst")
    }

    /**
     * Where `meta`'s children begin.
     *
     * In MP4 `meta` is a full box, so four bytes of version and flags come
     * first. QuickTime `.mov` files often write it as a plain box with no such
     * prefix. Both appear in the wild, so rather than trust the extension this
     * looks for the `hdlr` that always comes first either way.
     */
    private fun metaChildrenStart(buf: ByteArray, meta: Mp4.BoxRef): Int {
        val p = meta.payloadStart.toInt()
        if (p + 8 <= meta.end.toInt() && Mp4.latin1(buf, p + 4, 4) == "hdlr") return p
        return p + 4
    }

    private fun readIlst(buf: ByteArray, ilst: Mp4.BoxRef): VideoTags {
        var tags = VideoTags()
        for (item in Mp4.children(buf, ilst.payloadStart.toInt(), ilst.end.toInt())) {
            val kids = Mp4.children(buf, item.payloadStart.toInt(), item.end.toInt())
            val data = kids.firstOrNull { it.type == "data" } ?: continue
            val dStart = data.payloadStart.toInt()
            if (dStart + 8 > data.end.toInt()) continue
            val indicator = Mp4.be32(buf, dStart) and 0x00FFFFFF
            val payload = buf.copyOfRange(dStart + 8, data.end.toInt())

            fun text(): String? =
                String(payload, Charsets.UTF_8).takeIf { it.isNotBlank() }

            tags = when (item.type) {
                TITLE -> tags.copy(title = text())
                ARTIST -> tags.copy(artist = text())
                ALBUM_ARTIST -> tags.copy(albumArtist = text())
                ALBUM -> tags.copy(album = text())
                DATE -> tags.copy(date = text())
                GENRE -> tags.copy(genre = text())
                GENRE_ID3 -> {
                    // Numeric ID3 genre, one-based. Only used if no free-text genre.
                    val id = if (payload.size >= 2) Mp4.be16(payload, 0) else 0
                    if (tags.genre == null) tags.copy(genre = Id3Genres.name(id - 1)) else tags
                }
                COMMENT -> tags.copy(comment = text())
                DESCRIPTION -> tags.copy(description = text())
                LONG_DESCRIPTION -> tags.copy(longDescription = text())
                TV_SHOW -> tags.copy(showName = text())
                TV_NETWORK -> tags.copy(network = text())
                TV_SEASON -> if (payload.size >= 4)
                    tags.copy(seasonNumber = Mp4.be32(payload, 0).takeIf { it > 0 }) else tags
                TV_EPISODE -> if (payload.size >= 4)
                    tags.copy(episodeNumber = Mp4.be32(payload, 0).takeIf { it > 0 }) else tags
                MEDIA_KIND -> {
                    val v = if (payload.isNotEmpty()) payload[payload.size - 1].toInt() else -1
                    MediaKind.fromStik(v)?.let { tags.copy(mediaKind = it) } ?: tags
                }
                COMPOSER -> tags.copy(composer = text())
                LYRICS -> tags.copy(lyrics = text())
                TRACK -> if (payload.size >= 6) tags.copy(
                    trackNumber = Mp4.be16(payload, 2).takeIf { it > 0 },
                    trackTotal = Mp4.be16(payload, 4).takeIf { it > 0 },
                ) else tags
                COVER -> {
                    val mime = when (indicator) {
                        TYPE_PNG -> "image/png"
                        TYPE_JPEG -> "image/jpeg"
                        else -> null
                    }
                    val art = if (mime != null) Artwork(payload, mime) else Artwork.of(payload)
                    if (art != null) tags.copy(artwork = art) else tags
                }
                FREEFORM -> {
                    val name = kids.firstOrNull { it.type == "name" }?.let {
                        String(
                            buf, it.payloadStart.toInt() + 4,
                            (it.payloadSize - 4).toInt(), Charsets.UTF_8
                        )
                    }
                    val value = String(payload, Charsets.UTF_8).takeIf { it.isNotBlank() }
                    when (name) {
                        FF_LYRICIST -> tags.copy(lyricist = value)
                        FF_SYNCED_LYRICS -> tags.copy(syncedLyrics = value)
                        FF_LANGUAGE -> tags.copy(language = value)
                        FF_ARTIST_BIO -> tags.copy(artistBio = value)
                        FF_ALBUM_INFO -> tags.copy(albumInfo = value)
                        FF_SOURCE -> tags.copy(source = value)
                        FF_SOURCE_ID -> tags.copy(sourceId = value)
                        else -> tags
                    }
                }
                else -> tags
            }
            // An implicit-typed text atom is still text; nothing above depends
            // on the indicator except artwork, so no special case is needed.
            if (indicator == TYPE_IMPLICIT && item.type == COVER && tags.artwork == null) {
                Artwork.of(payload)?.let { tags = tags.copy(artwork = it) }
            }
        }
        return tags
    }

    // ----------------------------------------------------------------- write

    /**
     * Streams [src] to [out] with [tags] in place of whatever metadata it had.
     *
     * The media data is copied byte for byte; only `moov` is rebuilt.
     */
    fun write(
        src: Mp4.ByteSource,
        tags: VideoTags,
        out: OutputStream,
        subtitles: SubtitleTrack? = null,
    ) {
        val top = Mp4.topLevelBoxes(src)
        if (top.isEmpty()) throw UnsupportedContainer("no boxes found: not an MP4-family file")
        if (top.any { it.type == "moof" }) throw UnsupportedContainer(
            "fragmented MP4 (moof): chunk offsets are relative and cannot be remapped"
        )
        val moovRef = top.firstOrNull { it.type == "moov" }
            ?: throw UnsupportedContainer("no moov box: not an MP4-family file")
        if (moovRef.size > MAX_MOOV) throw UnsupportedContainer("moov box implausibly large")

        val moov = src.readFully(moovRef.start, moovRef.size.toInt())

        // A subtitle track is a whole new track: its own trak in moov and its
        // own samples in the file. The samples go in an mdat of their own after
        // everything else, so nothing that already exists has to move.
        val movie = readMovieHeader(moov)
        val picture = videoSize(moov)
        val text = subtitles?.cues?.takeIf { it.isNotEmpty() }?.let {
            Mp4TextTrack.build(
                cues = it,
                trackId = movie.nextTrackId,
                movieTimescale = movie.timescale,
                language = subtitles.language,
                width = picture.first,
                height = picture.second,
            )
        }

        val rebuilt = rebuildMoov(moov, tags, text?.trak)
        val newMoov = rebuilt.bytes

        // Everything except the old moov keeps its bytes. free/skip are dropped:
        // they exist to be reclaimed, and the offset map handles the shift.
        val ftyp = top.firstOrNull { it.type == "ftyp" && it.start == 0L }
        val rest = top.filter {
            it.start != moovRef.start && it.start != ftyp?.start &&
                    it.type != "free" && it.type != "skip"
        }

        val newStart = HashMap<Long, Long>()
        var cursor = 0L
        if (ftyp != null) {
            newStart[ftyp.start] = cursor
            cursor += ftyp.size
        }
        cursor += newMoov.size // moov goes here, in front of the media
        for (b in rest) {
            newStart[b.start] = cursor
            cursor += b.size
        }

        // The new track is skipped: its offset is not an old one to be moved but
        // a new one, filled in below once the layout is settled.
        patchChunkOffsets(newMoov, skipTrakAt = rebuilt.addedTrakAt) { old ->
            val holder = top.firstOrNull { old >= it.start && old < it.end }
                ?: throw UnsupportedContainer("chunk offset $old lies outside the file")
            val moved = newStart[holder.start] ?: throw UnsupportedContainer(
                "chunk offset $old points into the ${holder.type} box, which is not copied"
            )
            moved + (old - holder.start)
        }

        val subtitleMdat = text?.let { box("mdat", it.samples) }
        if (text != null && subtitleMdat != null) {
            // The samples sit right after the header of the mdat that follows
            // everything else.
            val at = rebuilt.addedTrakAt + text.chunkOffsetAt
            Mp4.putBe64(newMoov, at, cursor + 8)
        }

        if (ftyp != null) copyBox(src, ftyp, out)
        out.write(newMoov)
        for (b in rest) copyBox(src, b, out)
        subtitleMdat?.let { out.write(it) }
        out.flush()
    }

    private fun copyBox(src: Mp4.ByteSource, box: Mp4.BoxRef, out: OutputStream) {
        val buffer = ByteArray(256 * 1024)
        var remaining = box.size
        var pos = box.start
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val n = src.readAt(pos, buffer, 0, want)
            if (n <= 0) throw java.io.EOFException("file ended inside ${box.type}")
            out.write(buffer, 0, n)
            pos += n
            remaining -= n
        }
    }

    private fun headerSizeOf(buf: ByteArray, at: Int): Int =
        if (Mp4.be32(buf, at) == 1) 16 else 8

    private class RebuiltMoov(val bytes: ByteArray, val addedTrakAt: Int)

    /**
     * `moov` with its `udta` replaced by one carrying [tags], and optionally an
     * extra track appended.
     *
     * Reports where the added track landed, because its chunk offset has to be
     * filled in later and finding it again by searching would be guesswork on a
     * file that may already contain subtitle tracks of its own.
     */
    private fun rebuildMoov(
        moov: ByteArray,
        tags: VideoTags,
        extraTrak: ByteArray? = null,
    ): RebuiltMoov {
        val header = headerSizeOf(moov, 0)
        val kids = Mp4.children(moov, header, moov.size)
        val oldUdta = kids.firstOrNull { it.type == "udta" }

        val body = ByteArrayOutputStream()
        for (k in kids) if (k.type != "udta") {
            body.write(moov, k.start.toInt(), k.size.toInt())
        }

        // Inside the finished moov, everything is shifted by its 8-byte header.
        var addedAt = -1
        if (extraTrak != null) {
            addedAt = body.size() + 8
            body.write(extraTrak)
        }
        body.write(buildUdta(moov, oldUdta, tags))

        val bytes = box("moov", body.toByteArray())
        if (extraTrak != null) bumpNextTrackId(bytes)
        return RebuiltMoov(bytes, addedAt)
    }

    private class MovieHeader(val timescale: Int, val nextTrackId: Int)

    /**
     * The movie timescale and the next free track number, from `mvhd`.
     *
     * A track header states its duration in the movie's units rather than its
     * own, and a new track needs a number nothing else is using.
     */
    private fun readMovieHeader(moov: ByteArray): MovieHeader {
        val header = headerSizeOf(moov, 0)
        val mvhd = Mp4.child(moov, header, moov.size, "mvhd")
            ?: return MovieHeader(1000, 2)
        val p = mvhd.payloadStart.toInt()
        val version = moov[p].toInt() and 0xFF
        return if (version == 1) {
            MovieHeader(
                timescale = Mp4.be32(moov, p + 20),
                nextTrackId = Mp4.be32(moov, p + 108),
            )
        } else {
            MovieHeader(
                timescale = Mp4.be32(moov, p + 12),
                nextTrackId = Mp4.be32(moov, p + 96),
            )
        }
    }

    /** Claims the track number just handed out, so the file stays consistent. */
    private fun bumpNextTrackId(moov: ByteArray) {
        val header = headerSizeOf(moov, 0)
        val mvhd = Mp4.child(moov, header, moov.size, "mvhd") ?: return
        val p = mvhd.payloadStart.toInt()
        val version = moov[p].toInt() and 0xFF
        val at = if (version == 1) p + 108 else p + 96
        if (at + 4 > moov.size) return
        val current = Mp4.be32(moov, at)
        if (current in 1..0x7FFFFFFE) Mp4.putBe32(moov, at, current + 1)
    }

    /**
     * The picture size, so the subtitle box covers the frame instead of sitting
     * in a corner. Falls back to a sane default when the header is unusual.
     */
    private fun videoSize(moov: ByteArray): Pair<Int, Int> {
        val header = headerSizeOf(moov, 0)
        for (trak in Mp4.children(moov, header, moov.size).filter { it.type == "trak" }) {
            val tkhd = Mp4.child(moov, trak.payloadStart.toInt(), trak.end.toInt(), "tkhd")
                ?: continue
            val p = tkhd.payloadStart.toInt()
            val version = moov[p].toInt() and 0xFF
            val widthAt = if (version == 1) p + 88 else p + 76
            if (widthAt + 8 > tkhd.end.toInt()) continue
            val width = Mp4.be32(moov, widthAt) ushr 16
            val height = Mp4.be32(moov, widthAt + 4) ushr 16
            if (width in 1..16384 && height in 1..16384) return width to height
        }
        return 1280 to 720
    }

    /**
     * A `udta` holding the new `meta`, keeping any other children the file had
     * (`chpl` chapters and QuickTime `©xyz` location among them).
     */
    private fun buildUdta(moov: ByteArray, oldUdta: Mp4.BoxRef?, tags: VideoTags): ByteArray {
        val body = ByteArrayOutputStream()
        if (oldUdta != null) {
            for (k in Mp4.children(moov, oldUdta.payloadStart.toInt(), oldUdta.end.toInt())) {
                if (k.type != "meta") body.write(moov, k.start.toInt(), k.size.toInt())
            }
        }
        body.write(buildMeta(tags))
        return box("udta", body.toByteArray())
    }

    private fun buildMeta(tags: VideoTags): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0, 0, 0, 0)) // full box: version 0, flags 0
        body.write(HDLR_MDIR)
        body.write(buildIlst(tags))
        return box("meta", body.toByteArray())
    }

    /** The `hdlr` that marks the metadata as iTunes-style. */
    private val HDLR_MDIR: ByteArray = run {
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0, 0, 0, 0))            // version + flags
        payload.write(byteArrayOf(0, 0, 0, 0))            // pre_defined
        payload.write(Mp4.typeBytes("mdir"))              // handler type
        payload.write(Mp4.typeBytes("appl"))              // reserved[0]
        payload.write(ByteArray(8))                       // reserved[1..2]
        payload.write(0)                                  // empty, null-terminated name
        box("hdlr", payload.toByteArray())
    }

    private fun buildIlst(tags: VideoTags): ByteArray {
        val body = ByteArrayOutputStream()
        fun text(type: String, value: String?) {
            if (!value.isNullOrBlank()) body.write(textItem(type, value))
        }
        text(TITLE, tags.title)
        text(ARTIST, tags.artist)
        text(ALBUM_ARTIST, tags.albumArtist)
        text(ALBUM, tags.album)
        text(DATE, tags.date)
        text(GENRE, tags.genre)
        text(COMMENT, tags.comment)
        text(DESCRIPTION, tags.description)
        text(LONG_DESCRIPTION, tags.longDescription)
        text(COMPOSER, tags.composer)
        text(LYRICS, tags.lyrics)

        if (tags.trackNumber != null) {
            val v = ByteArray(8)
            Mp4.putBe16(v, 2, tags.trackNumber)
            Mp4.putBe16(v, 4, tags.trackTotal ?: 0)
            body.write(item(TRACK, dataBox(TYPE_IMPLICIT, v)))
        }
        tags.artwork?.let {
            val kind = if (it.isPng) TYPE_PNG else TYPE_JPEG
            body.write(item(COVER, dataBox(kind, it.bytes)))
        }
        if (!tags.lyricist.isNullOrBlank()) body.write(freeform(FF_LYRICIST, tags.lyricist))
        if (!tags.syncedLyrics.isNullOrBlank())
            body.write(freeform(FF_SYNCED_LYRICS, tags.syncedLyrics))
        if (!tags.language.isNullOrBlank()) body.write(freeform(FF_LANGUAGE, tags.language))
        if (!tags.artistBio.isNullOrBlank()) body.write(freeform(FF_ARTIST_BIO, tags.artistBio))
        if (!tags.albumInfo.isNullOrBlank()) body.write(freeform(FF_ALBUM_INFO, tags.albumInfo))
        if (!tags.source.isNullOrBlank()) body.write(freeform(FF_SOURCE, tags.source))
        if (!tags.sourceId.isNullOrBlank()) body.write(freeform(FF_SOURCE_ID, tags.sourceId))

        text(TV_SHOW, tags.showName)
        text(TV_NETWORK, tags.network)
        if (tags.seasonNumber != null) {
            val v = ByteArray(4)
            Mp4.putBe32(v, 0, tags.seasonNumber)
            body.write(item(TV_SEASON, dataBox(TYPE_INT, v)))
        }
        if (tags.episodeNumber != null) {
            val v = ByteArray(4)
            Mp4.putBe32(v, 0, tags.episodeNumber)
            body.write(item(TV_EPISODE, dataBox(TYPE_INT, v)))
        }

        // Marks what the file is. Without it Apple's apps file an imported MP4
        // under Home Videos, where the artwork, artist and episode numbering are
        // not shown at all -- so this one byte is what makes the tagging visible.
        body.write(item(MEDIA_KIND, dataBox(TYPE_IMPLICIT,
            byteArrayOf(tags.mediaKind.stik.toByte()))))
        body.write(textItem(ENCODER, ENCODER_NAME))
        return box("ilst", body.toByteArray())
    }

    private fun textItem(type: String, value: String): ByteArray =
        item(type, dataBox(TYPE_UTF8, value.toByteArray(Charsets.UTF_8)))

    private fun item(type: String, vararg parts: ByteArray): ByteArray = box(type, *parts)

    private fun dataBox(typeIndicator: Int, value: ByteArray): ByteArray {
        val payload = ByteArray(8 + value.size)
        Mp4.putBe32(payload, 0, typeIndicator) // version(0) in the top byte, then type
        Mp4.putBe32(payload, 4, 0)             // locale
        System.arraycopy(value, 0, payload, 8, value.size)
        return box("data", payload)
    }

    /** A `----` atom: `mean` (the namespace), `name` (the key), then the value. */
    private fun freeform(name: String, value: String): ByteArray {
        fun labelled(type: String, text: String): ByteArray {
            val t = text.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(4 + t.size) // version + flags, then raw text
            System.arraycopy(t, 0, payload, 4, t.size)
            return box(type, payload)
        }
        return box(
            FREEFORM,
            labelled("mean", MEAN),
            labelled("name", name),
            dataBox(TYPE_UTF8, value.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.sumOf { it.size }
        val out = ByteArray(8 + payload)
        Mp4.putBe32(out, 0, out.size)
        System.arraycopy(Mp4.typeBytes(type), 0, out, 4, 4)
        var at = 8
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    // -------------------------------------------------------- chunk offsets

    /**
     * Rewrites every `stco`/`co64` entry in [moov] through [remap].
     *
     * Walks only the path the offsets can be on -- `trak > mdia > minf > stbl`
     * -- rather than every box, so a stray four bytes that happen to spell
     * `stco` inside some other payload cannot be mistaken for a real one.
     */
    private fun patchChunkOffsets(
        moov: ByteArray,
        skipTrakAt: Int = -1,
        remap: (Long) -> Long,
    ) {
        val header = headerSizeOf(moov, 0)
        for (trak in Mp4.children(moov, header, moov.size).filter { it.type == "trak" }) {
            if (trak.start.toInt() == skipTrakAt) continue
            val mdia = Mp4.child(moov, trak.payloadStart.toInt(), trak.end.toInt(), "mdia")
                ?: continue
            val minf = Mp4.child(moov, mdia.payloadStart.toInt(), mdia.end.toInt(), "minf")
                ?: continue
            val stbl = Mp4.child(moov, minf.payloadStart.toInt(), minf.end.toInt(), "stbl")
                ?: continue
            for (b in Mp4.children(moov, stbl.payloadStart.toInt(), stbl.end.toInt())) {
                when (b.type) {
                    "stco" -> patchTable(moov, b, 4, remap)
                    "co64" -> patchTable(moov, b, 8, remap)
                }
            }
        }
    }

    private fun patchTable(
        moov: ByteArray,
        box: Mp4.BoxRef,
        width: Int,
        remap: (Long) -> Long,
    ) {
        val p = box.payloadStart.toInt()
        if (p + 8 > box.end.toInt()) return
        val count = Mp4.be32(moov, p + 4)
        var at = p + 8
        repeat(count) {
            if (at + width > box.end.toInt()) return
            val old = if (width == 4) Mp4.be32(moov, at).toLong() and 0xFFFFFFFFL
            else Mp4.be64(moov, at)
            val new = remap(old)
            if (width == 4) {
                if (new > 0xFFFFFFFFL) throw UnsupportedContainer(
                    "tagging would push a chunk past 4 GB, which a 32-bit stco cannot hold"
                )
                Mp4.putBe32(moov, at, new.toInt())
            } else {
                Mp4.putBe64(moov, at, new)
            }
            at += width
        }
    }
}
