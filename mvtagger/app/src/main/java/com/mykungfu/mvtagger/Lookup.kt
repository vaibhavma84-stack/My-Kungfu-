package com.mykungfu.mvtagger

import com.mykungfu.mvtagger.core.AlbumInfo
import com.mykungfu.mvtagger.core.ArtistInfo
import com.mykungfu.mvtagger.core.Artwork
import com.mykungfu.mvtagger.core.ArtworkPlan
import com.mykungfu.mvtagger.core.Candidate
import com.mykungfu.mvtagger.core.ITunes
import com.mykungfu.mvtagger.core.Languages
import com.mykungfu.mvtagger.core.LrcLib
import com.mykungfu.mvtagger.core.Lyrics
import com.mykungfu.mvtagger.core.Matching
import com.mykungfu.mvtagger.core.MediaKind
import com.mykungfu.mvtagger.core.MusicBrainz
import com.mykungfu.mvtagger.core.ParsedMedia
import com.mykungfu.mvtagger.core.ParsedName
import com.mykungfu.mvtagger.core.Tmdb
import com.mykungfu.mvtagger.core.TvMaze
import com.mykungfu.mvtagger.core.VideoTags
import com.mykungfu.mvtagger.core.Wikipedia

/**
 * Turns a parsed filename into ranked answers, by asking the services in
 * [com.mykungfu.mvtagger.core] over the network.
 *
 * Every call here blocks, and is meant to be run off the main thread. Nothing
 * throws for a source being unavailable: a service that is down or rate-limited
 * contributes nothing and the rest of the lookup carries on, because a partial
 * answer is far more useful than an error.
 */
object Lookup {

    data class MusicResult(
        val ranked: List<Matching.Scored>,
        /** Everything found, used by the artwork rule to reach album covers. */
        val all: List<Candidate>,
    )

    /**
     * Music videos.
     *
     * Both iTunes entities are asked for, and on more than one storefront. The
     * `song` entity is what carries the album cover -- and for a Hindi track
     * the album is the film, so that is where the film's artwork comes from.
     */
    fun music(
        parsed: ParsedName,
        durationMs: Int? = null,
        preferredLanguage: String? = null,
        storefronts: List<String> = ITunes.STOREFRONTS,
    ): MusicResult {
        val query = parsed.query.ifBlank { return MusicResult(emptyList(), emptyList()) }
        val found = ArrayList<Candidate>()

        for (store in storefronts) {
            for (entity in listOf("musicVideo", "song")) {
                Net.getTextOrNull(ITunes.searchUrl(query, entity, store, limit = 10))
                    ?.let { found += ITunes.parse(it, store) }
            }
        }

        // MusicBrainz second: slower and thinner on film music, but the only
        // source that states a release language.
        val mbQuery = MusicBrainz.recordingQuery(parsed.title, parsed.artist, query)
        Net.getTextOrNull(MusicBrainz.recordingSearchUrl(mbQuery, limit = 10))
            ?.let { found += MusicBrainz.parseRecordings(it) }

        val deduped = found.distinctBy { it.source + ":" + it.id }
        return MusicResult(
            ranked = Matching.rank(deduped, parsed, durationMs, preferredLanguage),
            all = deduped,
        )
    }

    /** Films. iTunes has posters and plots for these and still needs no key. */
    fun movie(media: ParsedMedia, storefronts: List<String> = ITunes.STOREFRONTS): List<Candidate> {
        val found = ArrayList<Candidate>()
        for (store in storefronts) {
            Net.getTextOrNull(ITunes.searchUrl(media.query, "movie", store, limit = 10))
                ?.let { found += ITunes.parse(it, store) }
        }
        val yearMatched = found.filter { media.year == null || it.year == media.year }
        return (yearMatched.ifEmpty { found }).distinctBy { it.source + ":" + it.id }
    }

    /**
     * Episodes. TVmaze answers "season 2, episode 4 of this show" directly,
     * which is exactly the question an `S02E04` filename asks.
     */
    fun episode(media: ParsedMedia): List<Candidate> {
        val season = media.season ?: return emptyList()
        val number = media.episode ?: return emptyList()
        val shows = Net.getTextOrNull(TvMaze.searchShowsUrl(media.name))
            ?.let { TvMaze.parseShows(it) } ?: return emptyList()

        val out = ArrayList<Candidate>()
        for (show in shows.take(3)) {
            Net.getTextOrNull(TvMaze.episodeUrl(show.id, season, number))
                ?.let { body -> TvMaze.parseEpisode(body, show)?.let { out += it } }
        }
        return out
    }

    /** Lyrics: the exact endpoint first, then a plain search. */
    fun lyrics(tags: VideoTags, durationMs: Int? = null): Lyrics? {
        val artist = tags.artist?.takeIf { it.isNotBlank() }
        val title = tags.title?.takeIf { it.isNotBlank() } ?: return null

        if (artist != null) {
            val exact = Net.getTextOrNull(
                LrcLib.getUrl(artist, title, tags.album, durationMs?.let { it / 1000 })
            )?.let { LrcLib.parseOne(it) }
            if (exact != null) return exact
        }

        val query = listOfNotNull(artist, title).joinToString(" ")
        return Net.getTextOrNull(LrcLib.searchUrl(query))
            ?.let { LrcLib.parseSearch(it) }
            ?.firstOrNull()
    }

    /** Background on a performer: MusicBrainz for the facts, Wikipedia for the prose. */
    fun artist(name: String): ArtistInfo? {
        if (name.isBlank()) return null
        var info = Net.getTextOrNull(MusicBrainz.artistSearchUrl(name))
            ?.let { MusicBrainz.parseArtists(it).firstOrNull() }

        info?.id?.let { mbid ->
            Net.getTextOrNull(MusicBrainz.artistUrl(mbid))
                ?.let { info = MusicBrainz.parseArtistDetail(it, info) }
        }

        val summary = wikipedia(Wikipedia.artistTerms(name))
        if (summary == null) return info ?: ArtistInfo(name = name)

        val base = info ?: ArtistInfo(name = name)
        return base.copy(
            summary = summary.extract,
            summarySource = "Wikipedia",
            imageUrl = summary.imageUrl ?: base.imageUrl,
            pageUrl = summary.pageUrl ?: base.pageUrl,
        )
    }

    /**
     * Background on an album. For film music this is really background on the
     * film, so the Wikipedia search is told to look for one.
     */
    fun album(title: String, artist: String?, language: String?): AlbumInfo? {
        if (title.isBlank()) return null
        val filmMusic = language != null && language in Languages.INDIAN_FILM

        val info = Net.getTextOrNull(MusicBrainz.releaseGroupSearchUrl(title, artist))
            ?.let { MusicBrainz.parseReleaseGroups(it).firstOrNull() }

        val soundtrack = filmMusic || info?.isSoundtrack == true ||
                ArtworkPlan.looksLikeSoundtrack(title)

        // "Brahmastra (Original Motion Picture Soundtrack)" will not find a
        // Wikipedia page; "Brahmastra" as a film will.
        val cleanTitle = title.replace(
            Regex("""\s*[(\[][^)\]]*(soundtrack|motion picture)[^)\]]*[)\]]""",
                RegexOption.IGNORE_CASE),
            ""
        ).trim().ifBlank { title }

        val summary = wikipedia(Wikipedia.albumTerms(cleanTitle, artist, soundtrack))
        val base = info ?: AlbumInfo(title = title, artist = artist)
        if (summary == null) return base
        return base.copy(
            summary = summary.extract,
            summarySource = "Wikipedia",
            coverUrl = base.coverUrl ?: summary.imageUrl,
            pageUrl = summary.pageUrl,
        )
    }

    private fun wikipedia(terms: List<String>): Wikipedia.Summary? {
        for (term in terms) {
            val titles = Net.getTextOrNull(Wikipedia.searchUrl(term, limit = 3))
                ?.let { Wikipedia.parseSearchTitles(it) } ?: continue
            for (pageTitle in titles.take(2)) {
                Net.getTextOrNull(Wikipedia.summaryUrl(pageTitle))
                    ?.let { Wikipedia.parseSummary(it) }
                    ?.let { return it }
            }
        }
        return null
    }

    /** Film posters, only when a TMDb key has been entered in Settings. */
    fun tmdbPosters(apiKey: String?, title: String, year: String?): List<String> {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        return Net.getTextOrNull(Tmdb.searchMovieUrl(key, title, year))
            ?.let { Tmdb.parseMovies(it) }
            ?.firstOrNull()
            ?.posterUrls()
            ?: emptyList()
    }

    /**
     * The picture to embed. Tries each URL in the plan's order and takes the
     * first that is genuinely an image.
     */
    fun artwork(urls: List<String>): Artwork? {
        for (url in urls.take(8)) {
            val bytes = Net.getBytesOrNull(url) ?: continue
            Artwork.of(bytes)?.let { return it }
        }
        return null
    }

    /** The artwork plan for a chosen match, film poster included where relevant. */
    fun artworkFor(
        chosen: Candidate,
        alternatives: List<Candidate>,
        language: String?,
        tmdbKey: String?,
    ): Artwork? {
        val album = chosen.album
        val posters = if (language in Languages.INDIAN_FILM && album != null) {
            val film = album.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim()
            tmdbPosters(tmdbKey, film, chosen.year)
        } else emptyList()
        return artwork(ArtworkPlan.urls(chosen, alternatives, language, posters))
    }

    /** Cover art for a film or an episode, which have no album to draw on. */
    fun artworkForScreen(candidate: Candidate, tmdbKey: String?): Artwork? {
        val posters = if (candidate.mediaKind == MediaKind.MOVIE) {
            tmdbPosters(tmdbKey, candidate.title, candidate.year)
        } else emptyList()
        return artwork(posters + candidate.artworkUrls)
    }
}
