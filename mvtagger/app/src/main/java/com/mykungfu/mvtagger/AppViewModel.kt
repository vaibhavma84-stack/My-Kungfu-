package com.mykungfu.mvtagger

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mykungfu.mvtagger.core.AlbumInfo
import com.mykungfu.mvtagger.core.ArtistInfo
import com.mykungfu.mvtagger.core.Candidate
import com.mykungfu.mvtagger.core.FilenameParser
import com.mykungfu.mvtagger.core.Languages
import com.mykungfu.mvtagger.core.Matching
import com.mykungfu.mvtagger.core.MediaClassifier
import com.mykungfu.mvtagger.core.MediaKind
import com.mykungfu.mvtagger.core.Organiser
import com.mykungfu.mvtagger.core.Sidecar
import com.mykungfu.mvtagger.core.VideoTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One video in the library. */
data class Item(
    val id: String,
    val treeUri: Uri,
    val documentId: String,
    val name: String,
    val size: Long,
    val kind: MediaKind,
    /** What the filename alone suggested, for the list line. */
    val guess: String,
    val status: ItemStatus,
    val note: String? = null,
) {
    val uri: Uri get() = Saf.documentUri(treeUri, documentId)
    val extension: String get() = FilenameParser.extensionOf(name)
}

/** The file currently open, and everything looked up for it. */
data class Detail(
    val item: Item,
    val tags: VideoTags,
    val candidates: List<Matching.Scored> = emptyList(),
    val alternatives: List<Candidate> = emptyList(),
    val artist: ArtistInfo? = null,
    val album: AlbumInfo? = null,
    val durationMs: Int? = null,
    val loading: String? = null,
    val chosen: Candidate? = null,
    /** Whether this file's streams can move into an MP4, and why not if they cannot. */
    val conversion: Remux.Verdict? = null,
) {
    /** True when saving will repackage this file into MP4 on the way out. */
    fun willConvert(settings: Settings): Boolean =
        !Sidecar.canEmbed(item.name) && settings.convertToMp4 && conversion?.possible == true

    /** Where this would be written, shown before anything is committed. */
    fun destination(settings: Settings): String? = Organiser.previewPath(
        settings.folderTemplateFor(tags.mediaKind),
        settings.nameTemplateFor(tags.mediaKind),
        tags,
        if (willConvert(settings)) "mp4" else item.extension,
    )
}

data class UiState(
    val settings: Settings = Settings(),
    val items: List<Item> = emptyList(),
    val busy: String? = null,
    val message: String? = null,
    val detail: Detail? = null,
    val showSettings: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val settings get() = _state.value.settings

    init {
        if (settings.sourceTrees.isNotEmpty()) rescan()
    }

    // --- settings ------------------------------------------------------------

    private fun update(settings: Settings) {
        store.save(settings)
        _state.value = _state.value.copy(settings = settings)
    }

    fun addSourceFolder(uri: Uri) {
        Saf.persist(getApplication<Application>(), uri)
        update(settings.copy(sourceTrees = (settings.sourceTrees + uri.toString()).distinct()))
        rescan()
    }

    fun removeSourceFolder(uri: String) {
        update(settings.copy(sourceTrees = settings.sourceTrees - uri))
        rescan()
    }

    fun setOutputFolder(uri: Uri) {
        Saf.persist(getApplication<Application>(), uri)
        update(settings.copy(outputTree = uri.toString()))
    }

    fun applySettings(settings: Settings) = update(settings)

    fun showSettings(show: Boolean) {
        _state.value = _state.value.copy(showSettings = show)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun forgetProgress() {
        store.forgetOutcomes()
        rescan()
    }

    // --- library -------------------------------------------------------------

    fun rescan() = viewModelScope.launch {
        _state.value = _state.value.copy(busy = "Scanning folders…")
        val found = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val out = ArrayList<Item>()
            for (tree in settings.sourceTrees) {
                val treeUri = Uri.parse(tree)
                val docs = runCatching { Saf.scanVideos(resolver, treeUri) }.getOrDefault(emptyList())
                for (doc in docs) {
                    val id = tree + "|" + doc.documentId
                    val media = MediaClassifier.classify(doc.name)
                    val recorded = store.outcome(id)
                    out += Item(
                        id = id,
                        treeUri = treeUri,
                        documentId = doc.documentId,
                        name = doc.name,
                        size = doc.size,
                        kind = media.kind,
                        guess = describe(media.kind, doc.name),
                        status = recorded?.first ?: ItemStatus.NEW,
                        note = recorded?.second,
                    )
                }
            }
            out.sortedBy { it.name.lowercase() }
        }
        _state.value = _state.value.copy(
            items = found,
            busy = null,
            message = if (found.isEmpty() && settings.sourceTrees.isNotEmpty())
                "No videos found in the folders you picked." else null,
        )
    }

    private fun describe(kind: MediaKind, name: String): String = when (kind) {
        MediaKind.TV_EPISODE -> {
            val m = MediaClassifier.classify(name)
            buildString {
                append(m.name)
                if (m.season != null && m.episode != null) {
                    append(" · S").append(m.season.toString().padStart(2, '0'))
                    append("E").append(m.episode.toString().padStart(2, '0'))
                }
            }
        }
        MediaKind.MOVIE -> {
            val m = MediaClassifier.classify(name)
            m.name + (m.year?.let { " · " + it } ?: "")
        }
        MediaKind.MUSIC_VIDEO -> {
            val p = FilenameParser.parse(name)
            listOfNotNull(p.artist, p.title).joinToString(" — ").ifBlank { p.query }
        }
    }

    // --- one file ------------------------------------------------------------

    fun open(item: Item) = viewModelScope.launch {
        _state.value = _state.value.copy(
            detail = Detail(item, VideoTags(mediaKind = item.kind), loading = "Reading the file…")
        )
        val loaded = withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val existing = TagJob.readExisting(app, item.uri, item.name)
            val duration = TagJob.durationMs(app, item.uri)
            // Start from what the file already says, topped up with what the
            // filename suggests, so nothing is blank before a lookup runs.
            val seeded = seedFromName(item, existing)
            // Worked out now rather than at save time, so the screen can say up
            // front whether the tags will end up inside the file.
            val verdict = if (Sidecar.canEmbed(item.name)) null else Remux.inspect(app, item.uri)
            Triple(seeded, duration, verdict)
        }
        _state.value = _state.value.copy(
            detail = Detail(
                item, loaded.first,
                durationMs = loaded.second,
                conversion = loaded.third,
            )
        )
    }

    private fun seedFromName(item: Item, existing: VideoTags): VideoTags {
        val media = MediaClassifier.classify(item.name)
        val base = existing.copy(mediaKind = existing.mediaKind.takeIf { !existing.isEmpty } ?: item.kind)
        return when (item.kind) {
            MediaKind.TV_EPISODE -> base.copy(
                mediaKind = MediaKind.TV_EPISODE,
                showName = base.showName ?: media.name,
                seasonNumber = base.seasonNumber ?: media.season,
                episodeNumber = base.episodeNumber ?: media.episode,
                title = base.title ?: media.episodeTitle,
            )
            MediaKind.MOVIE -> base.copy(
                mediaKind = MediaKind.MOVIE,
                title = base.title ?: media.name,
                date = base.date ?: media.year,
            )
            MediaKind.MUSIC_VIDEO -> {
                val p = FilenameParser.parse(item.name)
                base.copy(
                    mediaKind = MediaKind.MUSIC_VIDEO,
                    title = base.title ?: p.title,
                    artist = base.artist ?: p.artist,
                    album = base.album ?: p.album,
                    date = base.date ?: p.year,
                    trackNumber = base.trackNumber ?: p.trackNumber,
                    language = base.language ?: p.language ?: settings.preferredLanguage,
                )
            }
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null)
    }

    fun editTags(tags: VideoTags) {
        val detail = _state.value.detail ?: return
        _state.value = _state.value.copy(detail = detail.copy(tags = tags))
    }

    /** Runs the online search for the open file. */
    fun lookup() = viewModelScope.launch {
        val detail = _state.value.detail ?: return@launch
        _state.value = _state.value.copy(detail = detail.copy(loading = "Searching…"))

        val result = withContext(Dispatchers.IO) { search(detail) }

        _state.value = _state.value.copy(
            detail = detail.copy(
                candidates = result.first,
                alternatives = result.second,
                loading = null,
            ),
            message = if (result.first.isEmpty())
                "Nothing found online. You can still type the details in by hand." else null,
        )
    }

    private fun search(detail: Detail): Pair<List<Matching.Scored>, List<Candidate>> {
        val item = detail.item
        val media = MediaClassifier.classify(item.name)
        return when (item.kind) {
            MediaKind.MUSIC_VIDEO -> {
                val parsed = FilenameParser.parse(item.name)
                val r = Lookup.music(parsed, detail.durationMs, settings.preferredLanguage)
                r.ranked to r.all
            }
            MediaKind.MOVIE -> {
                val found = Lookup.movie(media)
                val parsed = FilenameParser.parse(item.name)
                Matching.rank(found, parsed, detail.durationMs) to found
            }
            MediaKind.TV_EPISODE -> {
                val found = Lookup.episode(media)
                // Already an exact season-and-episode answer, so ranking would
                // only obscure it; the order TVmaze gave is the right order.
                found.map { Matching.Scored(it, 0.95, listOf("season and episode matched")) } to found
            }
        }
    }

    /**
     * Takes a match: fills the fields, then fetches artwork, lyrics and
     * background for it. Anything that fails simply does not appear.
     */
    fun choose(scored: Matching.Scored) = viewModelScope.launch {
        val detail = _state.value.detail ?: return@launch
        val candidate = scored.candidate
        _state.value = _state.value.copy(
            detail = detail.copy(loading = "Fetching artwork and lyrics…", chosen = candidate)
        )

        val enriched = withContext(Dispatchers.IO) { enrich(detail, candidate) }
        _state.value = _state.value.copy(detail = enriched.copy(loading = null))
    }

    private fun enrich(detail: Detail, candidate: Candidate): Detail {
        var tags = detail.tags.overlaidWith(candidate.toTags())
        val language = tags.language
            ?: Languages.guess(title = tags.title, storefront = candidate.storefront)
            ?: settings.preferredLanguage
        tags = tags.copy(language = language)

        if (settings.fetchArtwork && tags.artwork == null) {
            val art = when (tags.mediaKind) {
                MediaKind.MUSIC_VIDEO -> Lookup.artworkFor(
                    candidate, detail.alternatives, language, settings.tmdbApiKey
                )
                else -> Lookup.artworkForScreen(candidate, settings.tmdbApiKey)
            }
            if (art != null) tags = tags.copy(artwork = art)
        }

        if (settings.fetchLyrics && tags.mediaKind == MediaKind.MUSIC_VIDEO &&
            tags.lyrics.isNullOrBlank()
        ) {
            Lookup.lyrics(tags, detail.durationMs)?.let {
                tags = tags.copy(lyrics = it.plain ?: tags.lyrics, syncedLyrics = it.synced)
            }
        }

        var artist: ArtistInfo? = null
        var album: AlbumInfo? = null
        if (settings.fetchBackground) {
            tags.artist?.let { artist = Lookup.artist(it) }
            tags.album?.let { album = Lookup.album(it, tags.artist, language) }
            // Embedded, not just displayed: the point is that it travels with
            // the file to whatever plays it next.
            artist?.summary?.let { tags = tags.copy(artistBio = it) }
            album?.summary?.let { tags = tags.copy(albumInfo = it) }
        }

        return detail.copy(tags = tags, artist = artist, album = album, chosen = candidate)
    }

    /** Writes the open file into the output folder. */
    fun save() = viewModelScope.launch {
        val detail = _state.value.detail ?: return@launch
        if (settings.outputTree == null) {
            _state.value = _state.value.copy(message = "Choose an output folder in Settings first.")
            return@launch
        }
        _state.value = _state.value.copy(detail = detail.copy(loading = "Saving…"))

        val outcome = withContext(Dispatchers.IO) {
            TagJob.save(
                context = getApplication<Application>(),
                sourceTree = detail.item.treeUri,
                documentId = detail.item.documentId,
                sourceName = detail.item.name,
                tags = detail.tags,
                settings = settings,
            )
        }

        val status = if (outcome.ok) ItemStatus.SAVED else ItemStatus.FAILED
        store.recordOutcome(detail.item.id, status, outcome.message)
        _state.value = _state.value.copy(
            detail = if (outcome.ok) null else detail.copy(loading = null),
            items = _state.value.items.map {
                if (it.id == detail.item.id) it.copy(status = status, note = outcome.message) else it
            },
            message = outcome.message,
        )
    }

    fun skip(item: Item) {
        store.recordOutcome(item.id, ItemStatus.SKIPPED, "Skipped")
        _state.value = _state.value.copy(
            items = _state.value.items.map {
                if (it.id == item.id) it.copy(status = ItemStatus.SKIPPED, note = "Skipped") else it
            },
            detail = null,
        )
    }

    /**
     * Does the whole library unattended, taking only matches it is confident
     * about and leaving the rest for a person to look at.
     */
    fun runBatch() = viewModelScope.launch {
        val todo = _state.value.items.filter { it.status == ItemStatus.NEW }
        if (todo.isEmpty()) {
            _state.value = _state.value.copy(message = "Nothing new to do.")
            return@launch
        }
        var saved = 0
        var unsure = 0
        var failed = 0

        for ((index, item) in todo.withIndex()) {
            _state.value = _state.value.copy(
                busy = "Auto-tagging " + (index + 1) + " of " + todo.size + "…"
            )
            val outcome = withContext(Dispatchers.IO) {
                val existing = TagJob.readExisting(getApplication<Application>(), item.uri, item.name)
                val duration = TagJob.durationMs(getApplication<Application>(), item.uri)
                val detail = Detail(item, seedFromName(item, existing), durationMs = duration)
                val (ranked, alternatives) = search(detail)
                val best = ranked.firstOrNull()

                if (best == null || best.score < settings.autoApplyThreshold) {
                    null
                } else {
                    val enriched = enrich(detail.copy(alternatives = alternatives), best.candidate)
                    TagJob.save(
                        context = getApplication<Application>(),
                        sourceTree = item.treeUri,
                        documentId = item.documentId,
                        sourceName = item.name,
                        tags = enriched.tags,
                        settings = settings,
                    )
                }
            }

            val status = when {
                outcome == null -> { unsure++; ItemStatus.MATCHED }
                outcome.ok -> { saved++; ItemStatus.SAVED }
                else -> { failed++; ItemStatus.FAILED }
            }
            val note = outcome?.message ?: "Not sure enough to do this one automatically."
            store.recordOutcome(item.id, status, note)
            _state.value = _state.value.copy(
                items = _state.value.items.map {
                    if (it.id == item.id) it.copy(status = status, note = note) else it
                }
            )
        }

        _state.value = _state.value.copy(
            busy = null,
            message = "Done: " + saved + " saved, " + unsure +
                    " need a look, " + failed + " failed.",
        )
    }
}
