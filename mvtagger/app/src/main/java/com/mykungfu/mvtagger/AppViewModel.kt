package com.mykungfu.mvtagger

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mykungfu.mvtagger.core.AlbumInfo
import com.mykungfu.mvtagger.core.ArtistInfo
import com.mykungfu.mvtagger.core.Candidate
import com.mykungfu.mvtagger.core.Clips
import com.mykungfu.mvtagger.core.Downloads
import com.mykungfu.mvtagger.core.CreditNames
import com.mykungfu.mvtagger.core.FilmTitle
import com.mykungfu.mvtagger.core.FilenameParser
import com.mykungfu.mvtagger.core.Languages
import com.mykungfu.mvtagger.core.LyricsLanguage
import com.mykungfu.mvtagger.core.Matching
import com.mykungfu.mvtagger.core.MediaClassifier
import com.mykungfu.mvtagger.core.MediaKind
import com.mykungfu.mvtagger.core.Organiser
import com.mykungfu.mvtagger.core.ParsedMedia
import com.mykungfu.mvtagger.core.Sidecar
import com.mykungfu.mvtagger.core.SubtitleTrack
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
    /** The folder it sits in, so a subtitle file beside it can be found. */
    val parentDocumentId: String,
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

/**
 * The download panel: a link, what it turned out to be, and how far along.
 *
 * Kept as plain values rather than the extractor's own types so that nothing
 * outside [YouTube] depends on which library is doing the fetching.
 */
data class GetState(
    val open: Boolean = false,
    val link: String = "",
    val looking: Boolean = false,
    val title: String? = null,
    val uploader: String? = null,
    val durationSeconds: Long = 0,
    val video: Downloads.Choice? = null,
    val audio: Downloads.Option? = null,
    val progress: String? = null,
    val note: String? = null,
)

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
    /**
     * Names the artist could be, for one-tap correction.
     *
     * Offered because the credit for a Hindi song is one string holding the
     * music director, the singer and the lyricist with nothing to say which is
     * which. Where MusicBrainz knows the roles the right one is filled in
     * already; where it does not, guessing would be worse than letting the
     * person who can see the song pick in one tap.
     */
    val artistChoices: List<String> = emptyList(),
    /** Films this could belong to, same idea. */
    val albumChoices: List<String> = emptyList(),
    /** Subtitles found or fetched for this file, ready to go into it. */
    val subtitles: SubtitleTrack? = null,
    /**
     * True when this is a file already finished and filed, opened to correct
     * something, rather than a new one on its way through.
     *
     * It changes what saving means. A new file is written into the output
     * folder and the source is left alone; a finished one is the copy, so
     * saving rewrites it in place and there is nothing to fall back on if that
     * goes wrong -- which is why [TagJob.retag] checks the new file before
     * letting go of the old.
     */
    val editingExisting: Boolean = false,
    /**
     * Whether a lookup has been run for this file.
     *
     * Not the same as having candidates: a search that came back with nothing
     * is the most useful one to be able to report, and it leaves the list
     * exactly as empty as a search never run.
     */
    val searched: Boolean = false,
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

/** The two things the app is for: work to do, and what has been done. */
enum class MainTab { TO_DO, COLLECTION }

/**
 * Which way the finished library is being looked at.
 *
 * Browsing is the shelf. The other two are questions about the whole
 * collection rather than a way through it, which is why they are a mode and
 * not another folder.
 */
enum class CollectionView { BROWSE, DUPLICATES, IPAD }

/** A file handed to the player, and enough to label it while it runs. */
data class Playing(
    val uri: Uri,
    val title: String,
    val mimeType: String,
    /**
     * The words, as they were written -- timed or not. Null until the lookup
     * has been done, which happens after the player is already on screen: a
     * song should start when it is asked to, not once its lyrics are in.
     */
    val lyrics: String? = null,
)

data class UiState(
    val settings: Settings = Settings(),
    val items: List<Item> = emptyList(),
    val busy: String? = null,
    val message: String? = null,
    val detail: Detail? = null,
    val showSettings: Boolean = false,
    val get: GetState = GetState(),
    val tab: MainTab = MainTab.TO_DO,
    /** Everything in the output folder, read from the tags inside the files. */
    val collection: List<Entry> = emptyList(),
    val collectionKind: MediaKind = MediaKind.MUSIC_VIDEO,
    /** Null means every language; only applies to music videos. */
    val collectionLanguage: String? = null,
    val collectionScanned: Boolean = false,
    /**
     * How far into the collection is opened.
     *
     * Everything on one screen is not how anyone looks for anything. You know
     * the artist -- or, for a film song, the film -- and then the song; you
     * know the series, then the season, then the number. So null is the shelf,
     * a folder is what is inside it, and a season narrows a series further.
     *
     * Films are the exception and stay flat: a film is one thing, not a
     * shelf of things, so there is nothing to open into.
     */
    val collectionFolder: String? = null,
    val collectionSeason: Int? = null,
    /** The file open in the player, if any. */
    val playing: Playing? = null,
    val collectionView: CollectionView = CollectionView.BROWSE,
    /**
     * The files picked out to be dealt with together, by document id.
     *
     * Empty means nothing is being selected at all, which is also what turns
     * the ordinary tap back into playing rather than choosing.
     */
    val selection: Set<String> = emptySet(),
) {
    /** Something to come back out of, for the back button and the heading. */
    val insideFolder: Boolean get() = collectionFolder != null
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val settings get() = _state.value.settings

    init {
        if (settings.sourceTrees.isNotEmpty()) rescan()
        // The remembered index shows immediately; a fresh scan happens when the
        // tab is first opened, so startup is not spent reading the whole output
        // folder that may not even be looked at.
        _state.value = _state.value.copy(collection = Catalogue.load(app))
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

    fun showTab(tab: MainTab) {
        _state.value = _state.value.copy(tab = tab)
        if (tab == MainTab.COLLECTION && !_state.value.collectionScanned) scanCollection()
    }

    fun setCollectionKind(kind: MediaKind) {
        _state.value = _state.value.copy(
            collectionKind = kind,
            collectionLanguage = null,
            collectionFolder = null,
            collectionSeason = null,
            collectionView = CollectionView.BROWSE,
        )
    }

    fun showCollectionView(view: CollectionView) {
        _state.value = _state.value.copy(
            collectionView = view,
            collectionFolder = null,
            collectionSeason = null,
        )
    }

    fun openFolder(name: String?) {
        _state.value = _state.value.copy(collectionFolder = name, collectionSeason = null)
    }

    fun openSeason(number: Int?) {
        _state.value = _state.value.copy(collectionSeason = number)
    }

    /** One step back out: episodes to seasons, seasons or songs to the shelf. */
    fun play(
        uri: Uri,
        title: String,
        mimeType: String,
        tree: Uri? = null,
        parentDocumentId: String? = null,
        fileName: String? = null,
    ) {
        _state.value = _state.value.copy(playing = Playing(uri, title, mimeType))

        // The words come afterwards. Finding them means reading the head of
        // the file or a document beside it, and neither is worth a moment's
        // delay before the video starts.
        viewModelScope.launch {
            val words = withContext(Dispatchers.IO) {
                LyricsSource.of(
                    getApplication<Application>(), uri, tree, parentDocumentId, fileName
                )
            } ?: return@launch
            val current = _state.value.playing
            // Unless something else is playing by now, in which case these are
            // the wrong words for it.
            if (current?.uri == uri) {
                _state.value = _state.value.copy(playing = current.copy(lyrics = words))
            }
        }
    }

    /**
     * Cuts a piece out of what is playing and writes it to the Clips folder.
     *
     * Returns what to tell the person, because this is asked for from inside
     * the player, where the ordinary message bar is behind the video.
     *
     * The source is opened for reading only. Whatever happens here, the video
     * that was playing is the same file it was before -- a trim that can eat
     * the original is a trim nobody dares use, and this app has never modified
     * a source file.
     */
    suspend fun cutClip(playing: Playing, fromMs: Long, toMs: Long): String =
        withContext(Dispatchers.IO) {
            Clips.refuse(fromMs, toMs)?.let { return@withContext it }

            val app = getApplication<Application>()
            val resolver = app.contentResolver
            val tree = settings.outputUri
                ?: return@withContext "Choose an output folder in Settings first; " +
                        "that is where clips go."

            // Asked before anything is created, so a file that cannot be cut
            // does not leave an empty one behind to explain.
            val verdict = Remux.inspect(app, playing.uri)
            if (!verdict.possible) {
                return@withContext "This one cannot be cut without re-encoding it. " +
                        verdict.reason
            }

            val folder = Saf.ensurePath(resolver, tree, listOf(Clips.FOLDER))
                ?: return@withContext "The Clips folder could not be made."
            val made = Saf.createFile(
                resolver, tree, folder, Clips.fileName(playing.title, fromMs, toMs), "video/mp4"
            ) ?: return@withContext "The clip file could not be created."

            try {
                val cut = Remux.cut(app, playing.uri, made.uri, fromMs, toMs)
                val moved = fromMs - cut.startedAtMs
                buildString {
                    append("Saved ").append(made.name).append(" in ").append(Clips.FOLDER)
                    // Only worth mentioning when it is enough to notice. Every
                    // cut lands slightly early; a quarter of a second is not
                    // news, three seconds is.
                    if (moved > 250L) {
                        append(". It begins at ").append(Clips.stamp(cut.startedAtMs))
                        append(", the nearest keyframe before your mark -- cutting ")
                        append("without re-encoding cannot start anywhere else.")
                    }
                }
            } catch (e: Exception) {
                // Never leave half a clip looking like a whole one.
                runCatching { Saf.delete(resolver, made.uri) }
                "The clip could not be written: " + (e.message ?: e.javaClass.simpleName)
            }
        }

    // --- fetching a video from a link ----------------------------------------

    /**
     * Set while a download is running, cleared to stop it.
     *
     * Volatile because it is written from the main thread and read from the
     * one doing the copying, several times a second.
     */
    @Volatile
    private var stopFetching = false

    fun openGet(open: Boolean) {
        _state.value = _state.value.copy(get = _state.value.get.copy(open = open, note = null))
    }

    fun setLink(text: String) {
        _state.value = _state.value.copy(
            get = _state.value.get.copy(link = text, note = null),
        )
    }

    /**
     * Asks what is at the link.
     *
     * Everything that can go wrong here goes wrong at the site rather than in
     * this app -- a video that is private, a link that is not one, or YouTube
     * having changed something the extractor has not caught up with. All three
     * are a sentence on the screen rather than a crash.
     */
    fun lookUp() = viewModelScope.launch {
        val link = _state.value.get.link.trim()
        if (!YouTube.looksLikeYouTube(link)) {
            _state.value = _state.value.copy(
                get = _state.value.get.copy(note = "That does not look like a YouTube link."),
            )
            return@launch
        }

        _state.value = _state.value.copy(
            get = _state.value.get.copy(looking = true, note = null, video = null, audio = null),
        )

        val found = withContext(Dispatchers.IO) { runCatching { YouTube.about(link) } }
        val get = _state.value.get

        found.onSuccess { video ->
            val best = Downloads.bestVideo(video.options)
            _state.value = _state.value.copy(
                get = get.copy(
                    looking = false,
                    title = video.title,
                    uploader = video.uploader,
                    durationSeconds = video.durationSeconds,
                    video = best,
                    audio = Downloads.bestAudio(video.options),
                    note = best?.warning,
                ),
            )
        }.onFailure { trouble ->
            _state.value = _state.value.copy(
                get = get.copy(
                    looking = false,
                    note = "That link could not be read: " +
                            (trouble.message ?: trouble.javaClass.simpleName) +
                            ". If this keeps happening for every link, YouTube has " +
                            "changed something and the app needs a newer extractor.",
                ),
            )
        }
    }

    fun stopFetch() {
        stopFetching = true
    }

    /**
     * Fetches what was found into the first to-do folder, where the tagger
     * will find it on the next scan.
     *
     * A video above 720p arrives as picture and sound separately, so those are
     * fetched into this app's own cache and joined into one MP4 afterwards.
     * The pieces are deleted whatever happens: a cancelled download must not
     * leave a gigabyte behind, and half a video must never look like a whole
     * one.
     */
    fun fetch(audioOnly: Boolean) = viewModelScope.launch {
        val get = _state.value.get
        val tree = settings.sourceTrees.firstOrNull()?.let(Uri::parse)
        if (tree == null) {
            _state.value = _state.value.copy(
                get = get.copy(note = "Add a to-do folder in Settings first; that is " +
                        "where downloads land."),
            )
            return@launch
        }

        val choice = get.video
        val sound = get.audio
        if (audioOnly && sound == null || !audioOnly && choice?.video == null) {
            _state.value = _state.value.copy(get = get.copy(note = "Nothing to fetch yet."))
            return@launch
        }

        stopFetching = false
        val app = getApplication<Application>()
        val resolver = app.contentResolver

        val outcome = withContext(Dispatchers.IO) {
            val container = if (audioOnly) sound!!.container else choice!!.video!!.container
            val name = Downloads.fileName(get.title, container)
            val mime = if (audioOnly) "audio/mp4" else "video/mp4"
            val root = Saf.rootDocumentId(tree)
            val made = Saf.createFile(resolver, tree, root, name, mime)
                ?: return@withContext "The file could not be created in the to-do folder."

            val pieces = ArrayList<java.io.File>()
            try {
                if (audioOnly || choice!!.audio == null) {
                    val from = if (audioOnly) sound!!.id else choice!!.video!!.id
                    Fetcher.toDocument(app, from, made.uri, { !stopFetching }) { done, total ->
                        report(done, total, "Downloading")
                    }
                } else {
                    // Two pieces, then one file. Fetched into the cache rather
                    // than the user's folder, so nothing half-finished is ever
                    // visible to anything else.
                    val video = java.io.File(app.cacheDir, "fetch-video.tmp")
                    val audio = java.io.File(app.cacheDir, "fetch-audio.tmp")
                    pieces += video
                    pieces += audio

                    Fetcher.toFile(choice.video!!.id, video, { !stopFetching }) { done, total ->
                        report(done, total, "Downloading the picture")
                    }
                    Fetcher.toFile(choice.audio!!.id, audio, { !stopFetching }) { done, total ->
                        report(done, total, "Downloading the sound")
                    }
                    _state.value = _state.value.copy(
                        get = _state.value.get.copy(progress = "Joining them…"),
                    )
                    Remux.join(video, audio, made.uri, app)
                }
                "Saved " + made.name + " to the to-do folder."
            } catch (stopped: InterruptedException) {
                runCatching { Saf.delete(resolver, made.uri) }
                "Stopped. Nothing was kept."
            } catch (trouble: Exception) {
                runCatching { Saf.delete(resolver, made.uri) }
                "That download did not finish: " +
                        (trouble.message ?: trouble.javaClass.simpleName)
            } finally {
                for (piece in pieces) runCatching { piece.delete() }
            }
        }

        _state.value = _state.value.copy(
            get = _state.value.get.copy(progress = null, note = outcome),
        )
        // So it appears in the list it was fetched for.
        rescan()
    }

    /** Percentages only, because a redraw per chunk is a redraw per 256 kilobytes. */
    private fun report(done: Long, total: Long, what: String) {
        val text = if (total > 0L) {
            what + "… " + (done * 100 / total) + "%"
        } else {
            what + "… " + (done / (1024 * 1024)) + " MB"
        }
        val get = _state.value.get
        if (get.progress != text) {
            _state.value = _state.value.copy(get = get.copy(progress = text))
        }
    }

    /** The lyrics switch in the player, kept for the next song. */
    fun showLyrics(on: Boolean) {
        applySettings(settings.copy(showLyrics = on))
    }

    fun stopPlaying() {
        _state.value = _state.value.copy(playing = null)
    }

    fun upFromFolder() {
        val state = _state.value
        _state.value = when {
            state.collectionSeason != null -> state.copy(collectionSeason = null)
            else -> state.copy(collectionFolder = null)
        }
    }

    fun setCollectionLanguage(language: String?) {
        _state.value = _state.value.copy(collectionLanguage = language)
    }

    /**
     * Reads the output folder and what each file says about itself.
     *
     * Opening every file is quick each but slow over hundreds, so the result is
     * remembered and only files whose size or date changed are read again.
     */
    fun scanCollection() = viewModelScope.launch {
        val tree = settings.outputUri
        if (tree == null) {
            _state.value = _state.value.copy(
                message = "Choose an output folder in Settings first.",
            )
            return@launch
        }
        /*
           A folder that cannot be reached is not a folder with nothing in it.

           They look identical from here -- both list no children -- and the
           difference is the whole story when the library lives on a memory
           card or a drive that plugs in. Scanning anyway would find nothing,
           write "nothing" over the remembered index, and leave an empty
           Collection tab that reads as a library that has been lost. So the
           question is asked first, and a drive in a drawer costs a sentence
           rather than a rescan.
        */
        val app = getApplication<Application>()
        if (!Saf.canRead(app.contentResolver, tree)) {
            _state.value = _state.value.copy(
                busy = null,
                message = "The output folder could not be reached. If it is on a memory " +
                        "card or a drive, connect it and open this tab again. Nothing " +
                        "has been lost.",
            )
            return@launch
        }

        _state.value = _state.value.copy(busy = "Reading your collection…")
        val found = withContext(Dispatchers.IO) {
            runCatching { Catalogue.scan(app, tree) }.getOrDefault(emptyList())
        }
        /*
           Whatever was open may not be there any more.

           Correcting an episode almost always changes the series name, the
           season or the title, and all three move the file: the folder being
           looked at empties out and keeps its old heading. That reads exactly
           like a correction that did not save -- the screen is unchanged
           because the thing that changed is somewhere else. So a folder that
           has gone is stepped out of rather than stared at.
        */
        val before = _state.value
        var folder = before.collectionFolder
        var season = before.collectionSeason
        var moved = false

        val open = folder
        if (open != null) {
            val shelf = if (before.collectionKind == MediaKind.TV_EPISODE) {
                Catalogue.series(found).map { it.first }
            } else {
                Catalogue.group(found, before.collectionKind, before.collectionLanguage)
                    .map { it.label }
            }
            if (open !in shelf) {
                folder = null
                season = null
                moved = true
            } else if (season != null && before.collectionKind == MediaKind.TV_EPISODE) {
                val seasons = Catalogue.seasons(found, open)
                    .map { it.first ?: Catalogue.SEASON_UNKNOWN }
                if (season !in seasons) {
                    season = null
                    moved = true
                }
            }
        }

        _state.value = _state.value.copy(
            collection = found,
            collectionScanned = true,
            collectionFolder = folder,
            collectionSeason = season,
            busy = null,
            message = when {
                found.isEmpty() -> "Nothing in the output folder yet."
                // Said out loud, because otherwise being bounced up a level
                // looks like the app losing its place rather than following
                // the file to where it now lives.
                moved -> "That file has moved, so this is the list it is in now."
                else -> null
            },
        )
    }

    fun showSettings(show: Boolean) {
        _state.value = _state.value.copy(showSettings = show)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun forgetProgress() {
        store.forgetOutcomes()
        Catalogue.forget(getApplication<Application>())
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
                        parentDocumentId = doc.parentDocumentId,
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
                    language = base.language
                        ?: LyricsLanguage.detect(base.lyrics ?: base.syncedLyrics)
                        ?: p.language ?: settings.preferredLanguage,
                )
            }
        }
    }

    /**
     * Opens a file that is already finished and filed, to correct it.
     *
     * Everything shown comes from the tags inside the file, because that is
     * where they were written and what other devices will read. The lookup and
     * every field work as they do for a new file; only saving differs, and that
     * is [Detail.editingExisting].
     */
    /** A collection entry as the rest of the app handles a file. */
    private fun itemFor(entry: Entry, tree: Uri) = Item(
        id = "collection|" + entry.documentId,
        treeUri = tree,
        documentId = entry.documentId,
        parentDocumentId = entry.parentDocumentId,
        name = entry.name,
        size = entry.size,
        kind = entry.kind,
        guess = entry.heading,
        status = ItemStatus.SAVED,
    )

    /**
     * What a finished file says about itself.
     *
     * Inside it first, then the .json beside it, which is the only record a
     * container that cannot hold tags has.
     */
    private fun tagsOf(item: Item, tree: Uri, kind: MediaKind): VideoTags {
        val app = getApplication<Application>()
        return TagJob.readExisting(app, item.uri, item.name).takeIf { !it.isEmpty }
            ?: Catalogue.sidecarTags(app, tree, item.parentDocumentId, item.name)
            ?: VideoTags(mediaKind = kind)
    }

    // --- more than one at a time ---------------------------------------------

    fun toggleSelected(documentId: String) {
        val now = _state.value.selection
        _state.value = _state.value.copy(
            selection = if (documentId in now) now - documentId else now + documentId,
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selection = emptySet())
    }

    fun selectAll(entries: List<Entry>) {
        _state.value = _state.value.copy(selection = entries.map { it.documentId }.toSet())
    }

    private fun selected(): List<Entry> {
        val chosen = _state.value.selection
        return _state.value.collection.filter { it.documentId in chosen }
    }

    /**
     * The same change to every file picked out.
     *
     * Each one is a full rewrite -- the details live inside the file, so there
     * is no smaller edit to make -- which is why this says how far it has got
     * rather than appearing to hang for several minutes.
     */
    private fun applyToSelection(
        what: String,
        change: (Entry, VideoTags) -> VideoTags?,
    ) = viewModelScope.launch {
        val tree = settings.outputUri
        val files = selected()
        if (tree == null || files.isEmpty()) {
            _state.value = _state.value.copy(message = "Nothing is selected.")
            return@launch
        }

        var done = 0
        var failed = 0
        for ((index, entry) in files.withIndex()) {
            _state.value = _state.value.copy(
                busy = what + " " + (index + 1) + " of " + files.size + "…"
            )
            val ok = withContext(Dispatchers.IO) {
                val item = itemFor(entry, tree)
                val wanted = change(entry, tagsOf(item, tree, entry.kind))
                    ?: return@withContext true
                TagJob.retag(
                    context = getApplication<Application>(),
                    outputTree = tree,
                    documentId = entry.documentId,
                    parentDocumentId = entry.parentDocumentId,
                    currentName = entry.name,
                    tags = wanted,
                    settings = settings,
                ).ok
            }
            if (ok) done++ else failed++
        }

        _state.value = _state.value.copy(busy = null, selection = emptySet())
        scanCollection()
        _state.value = _state.value.copy(
            message = done.toString() + " changed" +
                    (if (failed > 0) ", " + failed + " could not be" else "") + "."
        )
    }

    fun batchSetLanguage(code: String?) =
        applyToSelection("Setting the language on") { _, tags -> tags.copy(language = code) }

    fun batchSetArtist(name: String) =
        applyToSelection("Setting the artist on") { _, tags ->
            name.trim().ifBlank { null }?.let { tags.copy(artist = it) }
        }

    /**
     * Looks up everything picked out, and applies only what it is sure of.
     *
     * Deliberately stricter than doing one by hand. Looking at a match and
     * taking it is a decision; twenty of them unattended is a decision nobody
     * made, so anything under the threshold is left exactly as it was and
     * counted, rather than applied and discovered later.
     */
    fun batchLookup() = viewModelScope.launch {
        val tree = settings.outputUri
        val files = selected()
        if (tree == null || files.isEmpty()) {
            _state.value = _state.value.copy(message = "Nothing is selected.")
            return@launch
        }

        var applied = 0
        var unsure = 0
        var failed = 0

        for ((index, entry) in files.withIndex()) {
            _state.value = _state.value.copy(
                busy = "Looking up " + (index + 1) + " of " + files.size + "…"
            )
            val outcome = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val item = itemFor(entry, tree)
                val detail = Detail(
                    item,
                    seedFromName(item, tagsOf(item, tree, entry.kind)),
                    durationMs = TagJob.durationMs(app, item.uri),
                    editingExisting = true,
                )
                val (ranked, alternatives) = search(detail)
                val best = ranked.firstOrNull()
                if (best == null || best.score < settings.autoApplyThreshold) {
                    null
                } else {
                    val enriched = enrich(detail.copy(alternatives = alternatives), best.candidate)
                    TagJob.retag(
                        context = app,
                        outputTree = tree,
                        documentId = entry.documentId,
                        parentDocumentId = entry.parentDocumentId,
                        currentName = entry.name,
                        tags = enriched.tags,
                        settings = settings,
                    )
                }
            }
            when {
                outcome == null -> unsure++
                outcome.ok -> applied++
                else -> failed++
            }
        }

        _state.value = _state.value.copy(busy = null, selection = emptySet())
        scanCollection()
        _state.value = _state.value.copy(
            message = applied.toString() + " updated, " + unsure +
                    " not sure enough to change on their own" +
                    (if (failed > 0) ", " + failed + " failed" else "") + "."
        )
    }

    fun openCollectionEntry(entry: Entry) = viewModelScope.launch {
        val tree = settings.outputUri
        if (tree == null) {
            _state.value = _state.value.copy(message = "Choose an output folder in Settings first.")
            return@launch
        }
        if (entry.parentDocumentId.isBlank()) {
            _state.value = _state.value.copy(
                message = "Tap refresh to read the output folder, then try again.",
            )
            return@launch
        }
        val item = itemFor(entry, tree)
        _state.value = _state.value.copy(
            detail = Detail(
                item, VideoTags(mediaKind = entry.kind),
                loading = "Reading the file…", editingExisting = true,
            )
        )
        val loaded = withContext(Dispatchers.IO) {
            tagsOf(item, tree, entry.kind) to
                    TagJob.durationMs(getApplication<Application>(), item.uri)
        }
        _state.value = _state.value.copy(
            detail = Detail(
                item,
                seedFromName(item, loaded.first),
                durationMs = loaded.second,
                editingExisting = true,
            )
        )
    }

    /**
     * Empties every field, so a file can be started again from nothing.
     *
     * For a file whose details are a mixture -- half from one match, half from
     * another -- where clearing is quicker than auditing each field.
     *
     * It is not a fix for a bad match on its own, and should not be reached for
     * as one. The filename is left alone, and the filename is usually where a
     * wrong answer came from; clearing and looking up again asks the same
     * question and gets the same answer. Correcting the field and looking up is
     * what changes the question.
     *
     * Nothing is written here. The file on disk is untouched until Save.
     */
    fun clearTags() {
        val detail = _state.value.detail ?: return
        _state.value = _state.value.copy(
            detail = detail.copy(
                tags = VideoTags(mediaKind = detail.tags.mediaKind),
                candidates = emptyList(),
                alternatives = emptyList(),
                chosen = null,
                artistChoices = emptyList(),
                albumChoices = emptyList(),
                artist = null,
                album = null,
                searched = false,
            )
        )
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
                searched = true,
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
                /*
                   What the fields say, before what the filename said.

                   The filename is where the series name came from in the first
                   place, so searching it again returns the same wrong series
                   and there is no way out: correcting "Series" to "House of the
                   Dragon" and looking up again searched for "House of the
                   Dragon The House That Dragons Built" exactly as before, found
                   the aftershow exactly as before, and offered the one answer
                   that was already there. Editing a field has to change what is
                   asked, or it is not editing anything.
                */
                val asked = ParsedMedia(
                    kind = MediaKind.TV_EPISODE,
                    name = detail.tags.showName?.trim()?.ifBlank { null } ?: media.name,
                    season = detail.tags.seasonNumber ?: media.season,
                    episode = detail.tags.episodeNumber ?: media.episode,
                    episodeTitle = detail.tags.title?.trim()?.ifBlank { null }
                        ?: media.episodeTitle,
                    year = detail.tags.year ?: media.year,
                )
                val found = Lookup.episode(asked, settings.tmdbApiKey)
                // Scored on length, which is the one thing that separates the
                // right series from another one with the same episode number.
                Matching.rankEpisodes(found, detail.durationMs) to found
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

        // Lyrics first, because they decide the language and the language
        // decides the cover: for a Hindi song the right picture is the film's
        // poster rather than a soundtrack sleeve.
        if (settings.fetchLyrics && tags.mediaKind == MediaKind.MUSIC_VIDEO &&
            tags.lyrics.isNullOrBlank()
        ) {
            Lookup.lyrics(tags, detail.durationMs)?.let {
                tags = tags.copy(lyrics = it.plain ?: tags.lyrics, syncedLyrics = it.synced)
            }
        }

        // Hundreds of words of the actual language beats a three-word title or
        // the storefront a song happens to sell in, so the lyrics are asked
        // first and the weaker signals only fill in where they say nothing.
        val language = LyricsLanguage.detect(tags.lyrics ?: tags.syncedLyrics)
            ?: tags.language
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

        // The singer belongs in the artist field, not the music director and
        // not all three run together. Only applied when MusicBrainz actually
        // says who sang; otherwise the credit is left as the source gave it.
        var artistChoices = emptyList<String>()
        var albumChoices = emptyList<String>()
        if (tags.mediaKind == MediaKind.MUSIC_VIDEO) {
            val credits = Lookup.credits(candidate, detail.alternatives)
            if (!credits.isEmpty) {
                tags = tags.copy(
                    artist = credits.singerLine ?: tags.artist,
                    composer = credits.composerLine ?: tags.composer,
                    lyricist = credits.lyricistLine ?: tags.lyricist,
                    // A film soundtrack is "music by" its director, which is
                    // what the album artist means for this kind of release.
                    albumArtist = credits.composerLine ?: tags.albumArtist,
                )
            }

            val parsed = FilenameParser.parse(detail.item.name)
            artistChoices = (
                credits.singers +
                    CreditNames.split(candidate.artist) +
                    parsed.extras
                ).map { it.trim() }
                .filter { it.length >= 2 }
                .distinct()
                .take(8)
            albumChoices = listOfNotNull(
                candidate.film,
                FilmTitle.cleanAlbum(candidate.album).ifBlank { null },
                parsed.album,
            ).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4)
        }

        // Subtitles, for the kinds of file that have them. Existing ones are
        // preferred over anything downloaded: a file sitting next to the video
        // has exact timings, and a track inside it at least matches this cut of
        // the film, which a subtitle from the internet may not.
        var subtitles = detail.subtitles
        if (tags.mediaKind != MediaKind.MUSIC_VIDEO && subtitles == null) {
            val app = getApplication<Application>()
            val wanted = settings.subtitleLanguageList
            subtitles = SubtitleFinder.beside(app, detail.item)
                ?: SubtitleFinder.embedded(app, detail.item.uri, wanted.firstOrNull())
                ?: if (settings.fetchSubtitles) {
                    Lookup.subtitles(
                        apiKey = settings.openSubtitlesApiKey,
                        username = settings.openSubtitlesUsername,
                        password = settings.openSubtitlesPassword,
                        query = tags.showName ?: tags.title.orEmpty(),
                        kind = tags.mediaKind,
                        season = tags.seasonNumber,
                        episode = tags.episodeNumber,
                        year = tags.year,
                        languages = wanted,
                    )
                } else null
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

        return detail.copy(
            tags = tags, artist = artist, album = album, chosen = candidate,
            artistChoices = artistChoices, albumChoices = albumChoices,
            subtitles = subtitles,
        )
    }

    /** Writes the open file into the output folder. */
    fun save() = viewModelScope.launch {
        val detail = _state.value.detail ?: return@launch
        if (settings.outputTree == null) {
            _state.value = _state.value.copy(message = "Choose an output folder in Settings first.")
            return@launch
        }
        if (detail.editingExisting) {
            saveEdit(detail)
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
                subtitles = detail.subtitles,
            )
        }

        val status = if (outcome.ok) ItemStatus.SAVED else ItemStatus.FAILED
        store.recordOutcome(detail.item.id, status, outcome.message)
        _state.value = _state.value.copy(
            detail = if (outcome.ok) null else detail.copy(loading = null),
            // A deleted original is no longer there to open, so it leaves the
            // list rather than sitting in it as a row that cannot be tapped.
            items = if (outcome.deletedOriginal) {
                _state.value.items.filterNot { it.id == detail.item.id }
            } else {
                _state.value.items.map {
                    if (it.id == detail.item.id) {
                        it.copy(status = status, note = outcome.message)
                    } else it
                }
            },
            message = outcome.message,
            collectionScanned = false,
        )
    }

    /**
     * Writes a correction back to a file that is already in the collection.
     *
     * Nothing in the to-do list changes -- this file left it long ago. The
     * collection is marked for rereading instead, because the file may now have
     * a different name, sit in a different folder, and certainly says something
     * different about itself.
     */
    private suspend fun saveEdit(detail: Detail) {
        _state.value = _state.value.copy(
            detail = detail.copy(loading = "Writing the file again…")
        )
        val outcome = withContext(Dispatchers.IO) {
            TagJob.retag(
                context = getApplication<Application>(),
                outputTree = detail.item.treeUri,
                documentId = detail.item.documentId,
                parentDocumentId = detail.item.parentDocumentId,
                currentName = detail.item.name,
                tags = detail.tags,
                settings = settings,
            )
        }
        _state.value = _state.value.copy(
            detail = if (outcome.ok) null else detail.copy(loading = null),
            message = outcome.message,
            collectionScanned = false,
        )
        if (outcome.ok) scanCollection()
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
                        subtitles = enriched.subtitles,
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
                items = if (outcome?.deletedOriginal == true) {
                    _state.value.items.filterNot { it.id == item.id }
                } else {
                    _state.value.items.map {
                        if (it.id == item.id) it.copy(status = status, note = note) else it
                    }
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
