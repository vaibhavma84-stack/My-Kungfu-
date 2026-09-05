package com.mykungfu.mvtagger.core

/**
 * The same thing, more than once.
 *
 * A library that has been added to for years collects these without anyone
 * deciding to: the same song downloaded twice under two spellings, an episode
 * kept at 720p and then again at 4K, a film re-downloaded because the first
 * copy was never watched. They are invisible while browsing, because two copies
 * of one song look exactly like two songs.
 *
 * Two questions, which are really one:
 *
 * **Duplicates** -- which files are the same thing.
 * **Upgrades** -- which of them is the one worth keeping.
 *
 * Answering the first without the second is not much use. "You have this twice"
 * invites the question "so which do I delete", and the honest answer is usually
 * the smaller picture, which the app already knows.
 *
 * ## What counts as the same
 *
 * Deliberately strict, because the cost of the two mistakes is not the same. A
 * missed duplicate leaves a file on the disk. A wrong one invites someone to
 * delete a song they wanted, and there is no undoing that from here.
 *
 * So: an episode is the same when the series, season and number all match; a
 * film when the title and year match; a song when the title and artist match.
 * Names are folded the way matching folds them, so a Devanagari title and its
 * romanised spelling are recognised as one thing rather than two.
 */
object Duplicates {

    /** One thing that exists more than once, best copy first. */
    class Group<T>(
        val label: String,
        val copies: List<T>,
        /**
         * True when the copies differ in picture size, so one is an upgrade on
         * another rather than two of the same. That is a different decision:
         * a duplicate is waste, an upgrade is a choice already made.
         */
        val isUpgrade: Boolean,
    ) {
        val best: T get() = copies.first()
        val rest: List<T> get() = copies.drop(1)
    }

    /**
     * What each file says it is, as far as this needs to know.
     *
     * Taken as plain values rather than the app's own type so the rule can be
     * tested without one.
     */
    class Item(
        val kind: MediaKind,
        val title: String?,
        val artist: String?,
        val album: String?,
        val showName: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val year: String? = null,
        /** The long edge, which decides which copy is the better one. */
        val pixels: Int = 0,
        val bytes: Long = 0,
    )

    /**
     * Groups whatever is here more than once.
     *
     * [of] maps the caller's own type onto what this needs, so the app can pass
     * its collection entries straight in.
     */
    fun <T> find(items: List<T>, of: (T) -> Item): List<Group<T>> {
        val byKey = LinkedHashMap<String, MutableList<T>>()
        val labels = HashMap<String, String>()

        for (item in items) {
            val described = of(item)
            val key = keyOf(described) ?: continue
            byKey.getOrPut(key) { ArrayList() } += item
            labels.putIfAbsent(key, labelOf(described))
        }

        return byKey.entries
            .filter { it.value.size > 1 }
            .map { (key, copies) ->
                // Best first: the bigger picture, and where that ties, the
                // bigger file, which is the better encode of the same frame.
                val sorted = copies.sortedWith(
                    compareByDescending<T> { of(it).pixels }.thenByDescending { of(it).bytes }
                )
                val sizes = sorted.map { of(it).pixels }.filter { it > 0 }.distinct()
                Group(
                    label = labels[key] ?: "",
                    copies = sorted,
                    isUpgrade = sizes.size > 1,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * What makes two files the same thing, or null when there is not enough to
     * say.
     *
     * Null matters as much as the rest. A file with no title is not the same as
     * every other file with no title, and grouping them would be the one
     * mistake that gets something deleted.
     */
    private fun keyOf(item: Item): String? = when (item.kind) {
        MediaKind.TV_EPISODE -> {
            val show = fold(item.showName)
            val season = item.season
            val episode = item.episode
            if (show.isBlank() || season == null || episode == null) null
            else "tv:" + show + ":" + season + ":" + episode
        }

        MediaKind.MOVIE -> {
            val title = fold(item.title)
            // The year is what separates two films of the same name, and there
            // are more of those than one would think.
            if (title.isBlank()) null else "film:" + title + ":" + (item.year ?: "")
        }

        MediaKind.MUSIC_VIDEO -> {
            val title = fold(item.title)
            val by = fold(item.artist).ifBlank { fold(item.album) }
            // A title on its own is not enough: covers, remakes and film songs
            // reuse names constantly.
            if (title.isBlank() || by.isBlank()) null else "song:" + title + ":" + by
        }

        /*
           Podcasts, workouts and lessons.

           Nothing online says what these are, so there is no catalogue number
           to match on and the title is all there is. Which is why they are
           matched on the title *and* whatever they belong to: two episodes
           called "Introduction" in two different courses are not the same
           file, and a rule that said they were would offer to delete one.
        */
        MediaKind.PODCAST, MediaKind.FITNESS, MediaKind.LEARNING -> {
            val title = fold(item.title)
            val within = fold(item.showName)
            if (title.isBlank()) null
            else item.kind.name.lowercase() + ":" + within + ":" + title
        }
    }

    private fun labelOf(item: Item): String = when (item.kind) {
        MediaKind.TV_EPISODE -> buildString {
            append(item.showName.orEmpty())
            if (item.season != null && item.episode != null) {
                append("  S").append(item.season.toString().padStart(2, '0'))
                append("E").append(item.episode.toString().padStart(2, '0'))
            }
        }
        MediaKind.MOVIE ->
            item.title.orEmpty() + (item.year?.let { " (" + it + ")" } ?: "")
        MediaKind.MUSIC_VIDEO ->
            listOfNotNull(item.artist, item.title).joinToString(" — ").ifBlank {
                item.title.orEmpty()
            }
        MediaKind.PODCAST, MediaKind.FITNESS, MediaKind.LEARNING ->
            listOfNotNull(item.showName, item.title).joinToString(" — ").ifBlank {
                item.title.orEmpty()
            }
    }

    /**
     * Folded the way matching folds a name, so a Devanagari title and its
     * romanised spelling are one thing rather than two.
     */
    private fun fold(text: String?): String =
        Transliterate.fold(text.orEmpty()).replace(Regex("""[^\p{L}\p{N}]+"""), "")
}
