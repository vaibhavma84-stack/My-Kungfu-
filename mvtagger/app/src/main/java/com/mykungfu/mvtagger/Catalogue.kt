package com.mykungfu.mvtagger

import android.content.Context
import android.net.Uri
import com.mykungfu.mvtagger.core.FilenameParser
import com.mykungfu.mvtagger.core.Json
import com.mykungfu.mvtagger.core.Languages
import com.mykungfu.mvtagger.core.LyricsLanguage
import com.mykungfu.mvtagger.core.MediaClassifier
import com.mykungfu.mvtagger.core.MediaKind
import com.mykungfu.mvtagger.core.Resolution
import com.mykungfu.mvtagger.core.Sidecar
import com.mykungfu.mvtagger.core.VideoTags
import com.mykungfu.mvtagger.core.TextScript
import com.mykungfu.mvtagger.core.Transliterate

/**
 * One finished file in the output folder.
 *
 * Read from the tags inside the file rather than from its name, because that is
 * the whole point of having written them there: the file says what it is, and
 * it will still say so on any other device.
 */
data class Entry(
    val documentId: String,
    /** The folder document it sits in, so the file can be rewritten in place. */
    val parentDocumentId: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val kind: MediaKind,
    val title: String?,
    val artist: String?,
    val album: String?,
    /** ISO 639-1 where known, null when the file does not say. */
    val language: String?,
    val showName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: String? = null,
    /** Folders from the output root down to the file, for showing where it is. */
    val folder: List<String> = emptyList(),
    /** Whether a cover was found and a thumbnail kept for it. */
    val hasArtwork: Boolean = false,
    /** 4K, 1080p and so on, read from the picture rather than from the name. */
    val quality: String? = null,
) {
    /** The line shown in the list. */
    val heading: String
        get() = when (kind) {
            MediaKind.TV_EPISODE -> buildString {
                if (season != null && episode != null) {
                    append("S").append(season.toString().padStart(2, '0'))
                    append("E").append(episode.toString().padStart(2, '0'))
                    append("  ")
                }
                append(title ?: FilenameParser.stripExtension(name))
            }
            else -> title ?: FilenameParser.stripExtension(name)
        }

    val subheading: String?
        get() = when (kind) {
            MediaKind.TV_EPISODE -> showName
            MediaKind.MOVIE -> year
            MediaKind.MUSIC_VIDEO -> listOfNotNull(artist, album).joinToString(" · ")
                .takeIf { it.isNotBlank() }
        }

    /**
     * The same line, minus anything the headings above the row already say.
     *
     * A song sits under its artist and then its album, or under its film and
     * then its singer, so repeating both on every row is noise -- but which of
     * the two is above depends on the language and on whether the group
     * collapsed to a single section, so the row cannot assume either. Passing
     * the headings in lets it show whichever half is missing, and nothing when
     * both are already there.
     */
    fun subheadingExcluding(shown: Collection<String?>): String? {
        if (kind != MediaKind.MUSIC_VIDEO) return subheading
        val already = shown.filterNotNull().map { it.trim().lowercase() }.toSet()
        return listOfNotNull(artist, album)
            .map { it.trim() }
            .filter { it.isNotBlank() && it.lowercase() !in already }
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
    }

    /** What the music-video list is grouped by. */
    val languageLabel: String
        get() = language?.let { Languages.displayName(it) } ?: "Not known"
}

/**
 * The finished library: what is in the output folder, grouped the way someone
 * looks for something rather than the way it is stored.
 *
 * Building this means opening each file to read its tags, which is quick per
 * file and slow over a few hundred. So the result is remembered, keyed by the
 * file's size and modification time -- a file that has not changed is not
 * opened again, and a rescan after adding a handful only reads the handful.
 */
object Catalogue {

    private const val PREFS = "mvtagger-collection"
    // Bumped when a field the app needs is added, so an index written by an
    // older build is rescanned rather than loaded with the field missing.
    private const val KEY_INDEX = "entries2"

    /** Folder names the app itself creates, used when a file has no tags. */
    private val KIND_BY_FOLDER = mapOf(
        "music videos" to MediaKind.MUSIC_VIDEO,
        "movies" to MediaKind.MOVIE,
        "films" to MediaKind.MOVIE,
        "tv shows" to MediaKind.TV_EPISODE,
        "series" to MediaKind.TV_EPISODE,
    )

    fun scan(
        context: Context,
        outputTree: Uri,
        onProgress: (Int) -> Unit = {},
    ): List<Entry> {
        val resolver = context.contentResolver
        val cached = load(context).associateBy { it.documentId + "|" + it.size + "|" + it.modified }
        val out = ArrayList<Entry>()

        // Breadth-first with the path carried along, so a file that carries no
        // tags can still be placed by the folder it sits in.
        val queue = ArrayDeque<Pair<String, List<String>>>()
        queue += Saf.rootDocumentId(outputTree) to emptyList()

        while (queue.isNotEmpty()) {
            val (docId, path) = queue.removeFirst()
            val children = runCatching { Saf.listChildren(resolver, outputTree, docId) }
                .getOrDefault(emptyList())
            for (child in children) {
                if (child.isDirectory) {
                    if (path.size < 8) queue += child.documentId to (path + child.name)
                    continue
                }
                if (!Saf.isVideo(child.name, child.mimeType)) continue

                val key = child.documentId + "|" + child.size + "|" + child.lastModified
                // A remembered entry is only usable while its thumbnail is
                // still there. The cache directory is Android's to clear, and
                // raising the thumbnail size retires it deliberately; either
                // way the cover has to be cut again, which means opening the
                // file again. An entry that never had a cover is not reopened
                // looking for one.
                val known = cached[key]?.takeIf {
                    !it.hasArtwork || ArtCache.has(context, it.documentId)
                }
                out += known?.copy(
                    folder = path,
                    parentDocumentId = docId,
                    // The thumbnail lives in the cache directory, which Android
                    // may clear at any time, so its presence is checked rather
                    // than remembered.
                    hasArtwork = ArtCache.has(context, known.documentId),
                ) ?: read(context, outputTree, child, path)
                onProgress(out.size)
            }
        }

        save(context, out)
        return out
    }

    private fun read(
        context: Context,
        tree: Uri,
        doc: Saf.Doc,
        path: List<String>,
    ): Entry {
        val uri = Saf.documentUri(tree, doc.documentId)
        // Inside the file first. For a container that cannot hold tags at all,
        // the .json written beside it is the only record there is -- and until
        // this read it, a correction to an MKV episode was written and then
        // looked for in the one place it could never be, so the library went
        // back to the filename and the correction appeared to have been lost.
        val tags = if (Sidecar.canEmbed(doc.name)) {
            TagJob.readExisting(context, uri, doc.name)
        } else {
            sidecarTags(context, tree, doc.parentDocumentId, doc.name)
        }

        // The file's own word first; the folder the app filed it in second; the
        // filename last.
        val kind = when {
            tags != null && !tags.isEmpty -> tags.mediaKind
            else -> KIND_BY_FOLDER[path.firstOrNull()?.lowercase()]
                ?: MediaClassifier.classify(doc.name).kind
        }

        val fromName by lazy { MediaClassifier.classify(doc.name) }
        val parsed by lazy { FilenameParser.parse(doc.name) }

        val title = tags?.title?.ifBlank { null }
            ?: if (kind == MediaKind.MUSIC_VIDEO) parsed.title else fromName.name
        val language = tags?.language?.ifBlank { null }
            ?: LyricsLanguage.detect(tags?.lyrics ?: tags?.syncedLyrics)
            ?: guessLanguage(title, tags?.artist, path)

        // Shrunk and kept now, while the file is already open, rather than
        // decoding a full-size cover later for a list row.
        val embedded = tags?.artwork?.let { ArtCache.store(context, doc.documentId, it.bytes) }
        val hasArtwork = embedded
            ?: readArtworkSidecar(context, tree, doc)
            ?: false

        return Entry(
            documentId = doc.documentId,
            parentDocumentId = doc.parentDocumentId,
            name = doc.name,
            size = doc.size,
            modified = doc.lastModified,
            kind = kind,
            title = title,
            artist = tags?.artist?.ifBlank { null } ?: parsed.artist,
            album = tags?.album?.ifBlank { null },
            language = language,
            showName = tags?.showName?.ifBlank { null }
                ?: fromName.name.takeIf { kind == MediaKind.TV_EPISODE },
            season = tags?.seasonNumber ?: fromName.season,
            episode = tags?.episodeNumber ?: fromName.episode,
            year = tags?.year ?: fromName.year,
            folder = path,
            hasArtwork = hasArtwork,
            quality = TagJob.videoSize(context, uri)
                ?.let { (width, height) -> Resolution.label(width, height) },
        )
    }

    /**
     * The details written beside a file that cannot hold them inside it.
     *
     * Only consulted for those files. An MP4 says what it is from within, and a
     * stale .json left over from an earlier name should never be able to
     * contradict it.
     */
    fun sidecarTags(
        context: Context,
        tree: Uri,
        parentDocumentId: String,
        fileName: String,
    ): VideoTags? {
        val name = Sidecar.jsonName(FilenameParser.stripExtension(fileName))
        val found = runCatching {
            Saf.findChild(context.contentResolver, tree, parentDocumentId, name)
        }.getOrNull() ?: return null
        val text = Saf.readText(
            context.contentResolver, Saf.documentUri(tree, found.documentId)
        ) ?: return null
        return runCatching { Sidecar.parse(text) }.getOrNull()
    }

    /**
     * The cover for one of those, which is a file rather than an atom.
     *
     * Returns null when there is none to find, so the caller can tell "no cover
     * here" from "there was one and it would not decode".
     */
    private fun readArtworkSidecar(context: Context, tree: Uri, doc: Saf.Doc): Boolean? {
        val base = FilenameParser.stripExtension(doc.name)
        val resolver = context.contentResolver
        for (extension in listOf("jpg", "png")) {
            val found = runCatching {
                Saf.findChild(resolver, tree, doc.parentDocumentId, base + "." + extension)
            }.getOrNull() ?: continue
            val bytes = runCatching {
                resolver.openInputStream(Saf.documentUri(tree, found.documentId))
                    ?.use { it.readBytes() }
            }.getOrNull() ?: continue
            return ArtCache.store(context, doc.documentId, bytes)
        }
        return null
    }

    /**
     * A language for a file that does not state one.
     *
     * Script is the only honest signal here: a Devanagari title is Hindi, and a
     * romanised one is genuinely ambiguous, so it is left unknown rather than
     * guessed at. "Not known" is a useful thing for the list to say; a wrong
     * language quietly filed under English is not.
     */
    private fun guessLanguage(title: String?, artist: String?, path: List<String>): String? {
        val text = listOfNotNull(title, artist).joinToString(" ")
        if (text.isNotBlank() && TextScript.hasNonLatin(text)) {
            Languages.fromScript(TextScript.dominant(text))?.let { return it }
        }
        // A folder the user named after a language, if they organised that way.
        for (segment in path) Languages.normalise(segment)?.let { return it }
        return null
    }

    // --- remembering what was read -------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_INDEX, null) ?: return emptyList()
        return runCatching {
            Json.parseOrNull(raw).array.mapNotNull { entry ->
                val id = entry["id"].string ?: return@mapNotNull null
                Entry(
                    documentId = id,
                    parentDocumentId = entry["parent"].string ?: return@mapNotNull null,
                    name = entry["name"].string ?: return@mapNotNull null,
                    size = entry["size"].string?.toLongOrNull() ?: 0L,
                    modified = entry["modified"].string?.toLongOrNull() ?: 0L,
                    kind = runCatching { MediaKind.valueOf(entry["kind"].string ?: "") }
                        .getOrDefault(MediaKind.MUSIC_VIDEO),
                    title = entry["title"].string,
                    artist = entry["artist"].string,
                    album = entry["album"].string,
                    language = entry["language"].string,
                    showName = entry["show"].string,
                    season = entry["season"].int,
                    episode = entry["episode"].int,
                    year = entry["year"].string,
                    folder = entry["folder"].array.mapNotNull { it.string },
                    hasArtwork = entry["art"].string == "true",
                    quality = entry["quality"].string,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, entries: List<Entry>) {
        val json = StringBuilder("[")
        for ((index, e) in entries.withIndex()) {
            if (index > 0) json.append(',')
            json.append('{')
            json.append(field("id", e.documentId)).append(',')
            json.append(field("parent", e.parentDocumentId)).append(',')
            json.append(field("name", e.name)).append(',')
            json.append(quote("size")).append(':').append(e.size).append(',')
            json.append(quote("modified")).append(':').append(e.modified).append(',')
            json.append(field("kind", e.kind.name))
            e.title?.let { json.append(',').append(field("title", it)) }
            e.artist?.let { json.append(',').append(field("artist", it)) }
            e.album?.let { json.append(',').append(field("album", it)) }
            e.language?.let { json.append(',').append(field("language", it)) }
            e.showName?.let { json.append(',').append(field("show", it)) }
            e.season?.let { json.append(',').append(quote("season")).append(':').append(it) }
            e.episode?.let { json.append(',').append(quote("episode")).append(':').append(it) }
            e.year?.let { json.append(',').append(field("year", it)) }
            if (e.hasArtwork) json.append(',').append(field("art", "true"))
            e.quality?.let { json.append(',').append(field("quality", it)) }
            if (e.folder.isNotEmpty()) {
                json.append(',').append(quote("folder")).append(":[")
                json.append(e.folder.joinToString(",") { quote(it) })
                json.append(']')
            }
            json.append('}')
        }
        json.append(']')
        prefs(context).edit().putString(KEY_INDEX, json.toString()).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_INDEX).apply()
        ArtCache.clear(context)
    }

    private fun field(name: String, value: String) = quote(name) + ":" + quote(value)

    private fun quote(text: String): String {
        val sb = StringBuilder("\"")
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code))
                else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    // --- grouping -------------------------------------------------------------

    /** A run of files under a heading of their own, inside a [Group]. */
    data class Section(val label: String?, val entries: List<Entry>)

    /**
     * A heading and the files under it, in one or more sections.
     *
     * Most kinds want a flat list under each heading and get a single unlabelled
     * section. Music videos want two levels -- artist, then the film or album
     * within that artist -- which is what sections are for.
     */
    data class Group(val label: String, val sections: List<Section>) {
        val entries: List<Entry> get() = sections.flatMap { it.entries }
    }

    /**
     * A group with no second level.
     *
     * Not a secondary constructor: `List<Section>` and `List<Entry>` both erase
     * to `List` on the JVM, so the two would collide.
     */
    private fun flat(label: String, entries: List<Entry>) =
        Group(label, listOf(Section(null, entries)))

    /** Shown when a song does not say what it is from. */
    private const val NO_ALBUM = "Single"
    private const val NO_ARTIST = "Artist not known"
    private const val NO_FILM = "Film not known"

    /**
     * Languages whose popular music is film music.
     *
     * For these the album is the film, and the film is what someone looks for:
     * the songs of one picture belong together, whoever sang each of them. So
     * the two levels go the other way round -- film first, singers within it.
     * Add a language code here to treat it the same way.
     */
    private val FILM_SONG_LANGUAGES = setOf("hi")

    /**
     * The entries of one kind, grouped the way that kind is looked for.
     *
     * Music videos have two levels. Normally that is the artist and then the
     * album; for a film-song language it is the film and then the singers, per
     * [FILM_SONG_LANGUAGES]. Episodes go by series, films by year.
     */
    fun group(entries: List<Entry>, kind: MediaKind, language: String? = null): List<Group> {
        val of = entries.filter { it.kind == kind }
        return when (kind) {
            MediaKind.MUSIC_VIDEO -> {
                val wanted = if (language == null) of
                else of.filter { it.language == language }
                // Kept as two runs rather than one alphabet: a film heading and
                // an artist heading look alike, and interleaving them would
                // leave no way to tell which a heading was.
                val (filmSongs, rest) = wanted.partition { it.language in FILM_SONG_LANGUAGES }
                filmGroups(filmSongs) + artistGroups(rest)
            }
            MediaKind.TV_EPISODE -> of.groupBy { it.showName ?: "Unknown series" }
                .toSortedMap(compareBy { it.lowercase() })
                .map { (show, items) ->
                    flat(show, items.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })))
                }
            MediaKind.MOVIE -> of.groupBy { it.year ?: "Year not known" }
                .toSortedMap(compareByDescending { it })
                .map { (year, items) -> flat(year, items.sortedBy { sortKey(it) }) }
        }
    }

    /** Artist, then the album within them. */
    private fun artistGroups(songs: List<Entry>): List<Group> =
        songs.groupBy { it.artist.orBlank(NO_ARTIST) }
            .toList()
            .sortedBy { (artist, _) -> Transliterate.fold(artist) }
            .map { (artist, theirs) ->
                Group(artist, sectionsBy(theirs, NO_ALBUM) { it.album })
            }

    /** Film, then the singers within it. */
    private fun filmGroups(songs: List<Entry>): List<Group> =
        songs.groupBy { it.album.orBlank(NO_FILM) }
            .toList()
            .sortedBy { (film, _) -> Transliterate.fold(film) }
            .map { (film, from) ->
                Group(film, sectionsBy(from, NO_ARTIST) { it.artist })
            }

    /**
     * The second level of a music-video group.
     *
     * Entries naming nothing are gathered into one section at the end rather
     * than each becoming a section of one, and a group that ends up with a
     * single section loses the sub-heading entirely -- one heading directly
     * above another says nothing the rows do not already say.
     */
    private fun sectionsBy(
        songs: List<Entry>,
        noneLabel: String,
        field: (Entry) -> String?,
    ): List<Section> {
        val by = songs.groupBy { field(it)?.trim()?.ifBlank { null } }
        val named = by.filterKeys { it != null }
            .toList()
            .sortedBy { (label, _) -> Transliterate.fold(label!!) }
            .map { (label, items) -> Section(label, items.sortedBy { sortKey(it) }) }
        val loose = by[null]?.let {
            listOf(Section(noneLabel, it.sortedBy { song -> sortKey(song) }))
        } ?: emptyList()

        val sections = named + loose
        return if (sections.size == 1) listOf(Section(null, sections.first().entries))
        else sections
    }

    private fun String?.orBlank(fallback: String): String =
        this?.trim()?.ifBlank { null } ?: fallback

    /** Shown for an episode whose file never said what series it belongs to. */
    const val NO_SERIES = "Series not named"

    /**
     * Stands in for "season not known" while drilling in.
     *
     * A null season already means "not opened into a season yet", so an
     * episode that names no season needs a value of its own rather than
     * sharing that one.
     */
    const val SEASON_UNKNOWN = Int.MIN_VALUE

    /** The series on the shelf, with everything filed under each. */
    fun series(entries: List<Entry>): List<Pair<String, List<Entry>>> =
        entries.filter { it.kind == MediaKind.TV_EPISODE }
            .groupBy { it.showName.orBlank(NO_SERIES) }
            .toList()
            .sortedBy { (name, _) -> Transliterate.fold(name) }

    /** The seasons of one series, in order. */
    fun seasons(entries: List<Entry>, name: String): List<Pair<Int?, List<Entry>>> =
        (series(entries).firstOrNull { it.first == name }?.second ?: emptyList())
            .groupBy { it.season }
            .toList()
            .sortedBy { (number, _) -> number ?: Int.MAX_VALUE }

    /** The episodes of one season, in the order they were broadcast. */
    fun episodes(entries: List<Entry>, name: String, season: Int): List<Entry> {
        val wanted = if (season == SEASON_UNKNOWN) null else season
        return (series(entries).firstOrNull { it.first == name }?.second ?: emptyList())
            .filter { it.season == wanted }
            .sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { sortKey(it) }))
    }

    /** Languages present among the music videos, most files first. */
    fun languagesPresent(entries: List<Entry>): List<Pair<String?, Int>> =
        entries.filter { it.kind == MediaKind.MUSIC_VIDEO }
            .groupBy { it.language }
            .map { (code, items) -> code to items.size }
            .sortedWith(compareByDescending<Pair<String?, Int>> { it.second }.thenBy {
                it.first ?: "zz"
            })

    fun count(entries: List<Entry>, kind: MediaKind): Int = entries.count { it.kind == kind }

    /**
     * Sorted by what the file is called, folded the same way titles are matched
     * so a Devanagari title lands beside its Latin spelling rather than after
     * everything else.
     */
    private fun sortKey(entry: Entry): String =
        Transliterate.fold(entry.heading).ifBlank { entry.name.lowercase() }
}
