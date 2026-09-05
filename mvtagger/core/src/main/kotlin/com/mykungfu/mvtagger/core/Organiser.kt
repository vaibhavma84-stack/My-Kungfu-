package com.mykungfu.mvtagger.core

/**
 * Where a finished file goes in the output folder.
 *
 * Tagging writes a new file rather than editing in place, so there is a free
 * choice of where to put it -- and putting it somewhere organised is more
 * useful than dropping it back next to the original. The source folder is left
 * exactly as it was, which also means a bad match is never destructive.
 *
 * The templates use the same tokens as [RenameTemplate], with `/` separating
 * folders. A segment whose tokens are all empty is dropped rather than becoming
 * a folder called "Unknown", so a half-identified file lands one level up
 * instead of in a junk drawer.
 */
object Organiser {

    const val MUSIC_VIDEOS = "Music Videos/{artist}"
    const val MOVIES = "Movies/{title}[ ({year})]"
    const val TV_EPISODES = "TV Shows/{show}[/Season {season2}]"

    /*
       The three that have no catalogue behind them are grouped by whatever
       they belong to -- a podcast series, a programme, a course -- which is
       the show field under another name. A file with nothing in that field
       lands one level up rather than in a folder called "Unknown".
    */
    const val PODCASTS = "Podcasts[/{show}]"
    const val FITNESS = "Fitness[/{show}]"
    const val LEARNING = "Learning[/{show}]"

    fun defaultFor(kind: MediaKind): String = when (kind) {
        MediaKind.MUSIC_VIDEO -> MUSIC_VIDEOS
        MediaKind.MOVIE -> MOVIES
        MediaKind.TV_EPISODE -> TV_EPISODES
        MediaKind.PODCAST -> PODCASTS
        MediaKind.FITNESS -> FITNESS
        MediaKind.LEARNING -> LEARNING
    }

    /**
     * Folder names from the output root down to the file, already safe to use.
     * An empty list means "put it straight in the output folder".
     */
    fun folder(template: String, tags: VideoTags): List<String> =
        RenameTemplate.render(template, tags)
            .split('/')
            .map { RenameTemplate.sanitise(it) }
            .filter { it.isNotBlank() }

    /** Folder segments plus the filename, for showing the user where it will go. */
    fun previewPath(
        folderTemplate: String,
        nameTemplate: String,
        tags: VideoTags,
        extension: String,
    ): String? {
        val name = RenameTemplate.fileName(nameTemplate, tags, extension) ?: return null
        return (folder(folderTemplate, tags) + name).joinToString("/")
    }
}
