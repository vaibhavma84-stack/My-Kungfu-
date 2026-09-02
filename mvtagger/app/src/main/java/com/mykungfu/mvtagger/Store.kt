package com.mykungfu.mvtagger

import android.content.Context
import android.net.Uri
import com.mykungfu.mvtagger.core.MediaKind
import com.mykungfu.mvtagger.core.Organiser
import com.mykungfu.mvtagger.core.RenameTemplate

/**
 * What the user chose, and where the library stands.
 *
 * Everything lives in one SharedPreferences file. The library itself is not
 * persisted -- it is rescanned from the source folders, which takes a second or
 * two and can never disagree with what is actually on disk. Only the outcome of
 * each file is remembered, so a folder already dealt with does not look
 * untouched after a restart.
 */
data class Settings(
    val sourceTrees: List<String> = emptyList(),
    val outputTree: String? = null,
    val musicNameTemplate: String = RenameTemplate.defaultFor(MediaKind.MUSIC_VIDEO),
    val movieNameTemplate: String = RenameTemplate.defaultFor(MediaKind.MOVIE),
    val episodeNameTemplate: String = RenameTemplate.defaultFor(MediaKind.TV_EPISODE),
    val musicFolderTemplate: String = Organiser.MUSIC_VIDEOS,
    val movieFolderTemplate: String = Organiser.MOVIES,
    val episodeFolderTemplate: String = Organiser.TV_EPISODES,
    /** Blank unless the user wants film posters from TMDb. */
    val tmdbApiKey: String = "",
    /** Nudges the ranking and is written to the file when nothing better is known. */
    val preferredLanguage: String? = null,
    val fetchLyrics: Boolean = true,
    val fetchArtwork: Boolean = true,
    val fetchBackground: Boolean = true,
    /** Score at or above which a batch run applies a match without asking. */
    val autoApplyThreshold: Double = 0.80,
    /** Also write .json/.lrc/poster files for containers that cannot be tagged. */
    val writeSidecars: Boolean = true,
) {
    fun nameTemplateFor(kind: MediaKind): String = when (kind) {
        MediaKind.MUSIC_VIDEO -> musicNameTemplate
        MediaKind.MOVIE -> movieNameTemplate
        MediaKind.TV_EPISODE -> episodeNameTemplate
    }

    fun folderTemplateFor(kind: MediaKind): String = when (kind) {
        MediaKind.MUSIC_VIDEO -> musicFolderTemplate
        MediaKind.MOVIE -> movieFolderTemplate
        MediaKind.TV_EPISODE -> episodeFolderTemplate
    }

    val outputUri: Uri? get() = outputTree?.let(Uri::parse)
    val isReady: Boolean get() = sourceTrees.isNotEmpty() && outputTree != null
}

/** How far a file has got. */
enum class ItemStatus { NEW, MATCHED, SAVED, SKIPPED, FAILED }

class Store(context: Context) {

    private val prefs = context.getSharedPreferences("mvtagger", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        sourceTrees = prefs.getStringSet(KEY_SOURCES, emptySet())!!.toList().sorted(),
        outputTree = prefs.getString(KEY_OUTPUT, null),
        musicNameTemplate = prefs.getString(KEY_MUSIC_NAME, null)
            ?: RenameTemplate.defaultFor(MediaKind.MUSIC_VIDEO),
        movieNameTemplate = prefs.getString(KEY_MOVIE_NAME, null)
            ?: RenameTemplate.defaultFor(MediaKind.MOVIE),
        episodeNameTemplate = prefs.getString(KEY_EPISODE_NAME, null)
            ?: RenameTemplate.defaultFor(MediaKind.TV_EPISODE),
        musicFolderTemplate = prefs.getString(KEY_MUSIC_FOLDER, null) ?: Organiser.MUSIC_VIDEOS,
        movieFolderTemplate = prefs.getString(KEY_MOVIE_FOLDER, null) ?: Organiser.MOVIES,
        episodeFolderTemplate = prefs.getString(KEY_EPISODE_FOLDER, null) ?: Organiser.TV_EPISODES,
        tmdbApiKey = prefs.getString(KEY_TMDB, "") ?: "",
        preferredLanguage = prefs.getString(KEY_LANGUAGE, null),
        fetchLyrics = prefs.getBoolean(KEY_LYRICS, true),
        fetchArtwork = prefs.getBoolean(KEY_ARTWORK, true),
        fetchBackground = prefs.getBoolean(KEY_BACKGROUND, true),
        autoApplyThreshold = prefs.getFloat(KEY_THRESHOLD, 0.80f).toDouble(),
        writeSidecars = prefs.getBoolean(KEY_SIDECARS, true),
    )

    fun save(settings: Settings) {
        prefs.edit().apply {
            putStringSet(KEY_SOURCES, settings.sourceTrees.toSet())
            putString(KEY_OUTPUT, settings.outputTree)
            putString(KEY_MUSIC_NAME, settings.musicNameTemplate)
            putString(KEY_MOVIE_NAME, settings.movieNameTemplate)
            putString(KEY_EPISODE_NAME, settings.episodeNameTemplate)
            putString(KEY_MUSIC_FOLDER, settings.musicFolderTemplate)
            putString(KEY_MOVIE_FOLDER, settings.movieFolderTemplate)
            putString(KEY_EPISODE_FOLDER, settings.episodeFolderTemplate)
            putString(KEY_TMDB, settings.tmdbApiKey)
            putString(KEY_LANGUAGE, settings.preferredLanguage)
            putBoolean(KEY_LYRICS, settings.fetchLyrics)
            putBoolean(KEY_ARTWORK, settings.fetchArtwork)
            putBoolean(KEY_BACKGROUND, settings.fetchBackground)
            putFloat(KEY_THRESHOLD, settings.autoApplyThreshold.toFloat())
            putBoolean(KEY_SIDECARS, settings.writeSidecars)
        }.apply()
    }

    // --- per-file outcomes ---------------------------------------------------

    private fun outcomeKey(id: String) = "outcome:" + id

    fun outcome(id: String): Pair<ItemStatus, String?>? {
        val raw = prefs.getString(outcomeKey(id), null) ?: return null
        val status = runCatching { ItemStatus.valueOf(raw.substringBefore('|')) }.getOrNull()
            ?: return null
        return status to raw.substringAfter('|', "").ifBlank { null }
    }

    fun recordOutcome(id: String, status: ItemStatus, note: String?) {
        prefs.edit().putString(outcomeKey(id), status.name + "|" + (note ?: "")).apply()
    }

    fun forgetOutcomes() {
        val editor = prefs.edit()
        for (key in prefs.all.keys) if (key.startsWith("outcome:")) editor.remove(key)
        editor.apply()
    }

    private companion object {
        const val KEY_SOURCES = "sourceTrees"
        const val KEY_OUTPUT = "outputTree"
        const val KEY_MUSIC_NAME = "musicNameTemplate"
        const val KEY_MOVIE_NAME = "movieNameTemplate"
        const val KEY_EPISODE_NAME = "episodeNameTemplate"
        const val KEY_MUSIC_FOLDER = "musicFolderTemplate"
        const val KEY_MOVIE_FOLDER = "movieFolderTemplate"
        const val KEY_EPISODE_FOLDER = "episodeFolderTemplate"
        const val KEY_TMDB = "tmdbApiKey"
        const val KEY_LANGUAGE = "preferredLanguage"
        const val KEY_LYRICS = "fetchLyrics"
        const val KEY_ARTWORK = "fetchArtwork"
        const val KEY_BACKGROUND = "fetchBackground"
        const val KEY_THRESHOLD = "autoApplyThreshold"
        const val KEY_SIDECARS = "writeSidecars"
    }
}
