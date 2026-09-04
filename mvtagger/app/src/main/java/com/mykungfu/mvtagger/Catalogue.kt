package com.mykungfu.mvtagger

import android.content.Context
import android.net.Uri
import com.mykungfu.mvtagger.core.FilenameParser
import com.mykungfu.mvtagger.core.Json
import com.mykungfu.mvtagger.core.Languages
import com.mykungfu.mvtagger.core.MediaClassifier
import com.mykungfu.mvtagger.core.MediaKind
import com.mykungfu.mvtagger.core.Sidecar
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
    private const val KEY_INDEX = "entries"

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
                val known = cached[key]
                out += known?.copy(
                    folder = path,
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
        val tags = if (Sidecar.canEmbed(doc.name)) {
            TagJob.readExisting(context, uri, doc.name)
        } else {
            null
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
            ?: guessLanguage(title, tags?.artist, path)

        // Shrunk and kept now, while the file is already open, rather than
        // decoding a full-size cover later for a list row.
        val hasArtwork = tags?.artwork
            ?.let { ArtCache.store(context, doc.documentId, it.bytes) }
            ?: false

        return Entry(
            documentId = doc.documentId,
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
        )
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

    /** A heading and the files under it. */
    data class Group(val label: String, val entries: List<Entry>)

    /**
     * The entries of one kind, grouped the way that kind is looked for:
     * music videos by language, episodes by series, films by year.
     */
    fun group(entries: List<Entry>, kind: MediaKind, language: String? = null): List<Group> {
        val of = entries.filter { it.kind == kind }
        return when (kind) {
            MediaKind.MUSIC_VIDEO -> {
                val wanted = if (language == null) of
                else of.filter { it.language == language }
                wanted.groupBy { it.languageLabel }
                    .toSortedMap(compareBy { it })
                    .map { (label, items) -> Group(label, items.sortedBy { sortKey(it) }) }
            }
            MediaKind.TV_EPISODE -> of.groupBy { it.showName ?: "Unknown series" }
                .toSortedMap(compareBy { it.lowercase() })
                .map { (show, items) ->
                    Group(show, items.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })))
                }
            MediaKind.MOVIE -> of.groupBy { it.year ?: "Year not known" }
                .toSortedMap(compareByDescending { it })
                .map { (year, items) -> Group(year, items.sortedBy { sortKey(it) }) }
        }
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
