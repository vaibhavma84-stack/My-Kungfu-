package com.mykungfu.mvtagger

import com.mykungfu.mvtagger.core.AlbumInfo
import com.mykungfu.mvtagger.core.ArtistInfo
import com.mykungfu.mvtagger.core.Artwork
import com.mykungfu.mvtagger.core.ArtworkPlan
import com.mykungfu.mvtagger.core.Candidate
import com.mykungfu.mvtagger.core.Credits
import com.mykungfu.mvtagger.core.ITunes
import com.mykungfu.mvtagger.core.Languages
import com.mykungfu.mvtagger.core.LrcLib
import com.mykungfu.mvtagger.core.Lyrics
import com.mykungfu.mvtagger.core.Matching
import com.mykungfu.mvtagger.core.MediaKind
import com.mykungfu.mvtagger.core.MusicBrainz
import com.mykungfu.mvtagger.core.OpenSubtitles
import com.mykungfu.mvtagger.core.SubtitleTrack
import com.mykungfu.mvtagger.core.Subtitles
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

    /** Further attempts cost round trips for rapidly diminishing returns. */
    private const val MAX_QUERY_ATTEMPTS = 3

    /** A match this strong will not be improved on by searching again. */
    private const val GOOD_ENOUGH_TO_STOP = 0.75

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
        val attempts = parsed.queries
            .ifEmpty { listOfNotNull(parsed.query.takeIf { it.isNotBlank() }) }
        if (attempts.isEmpty()) return MusicResult(emptyList(), emptyList())

        val found = ArrayList<Candidate>()
        var ranked: List<Matching.Scored> = emptyList()

        // One query is not enough for Indian film music: whether the song, the
        // film or the singer is in the filename depends entirely on who
        // uploaded it. Each attempt costs a handful of round trips on a phone,
        // so this stops the moment something clearly lands and caps the rest.
        for ((index, query) in attempts.take(MAX_QUERY_ATTEMPTS).withIndex()) {
            for (store in storefronts) {
                for (entity in listOf("musicVideo", "song")) {
                    Net.getTextOrNull(ITunes.searchUrl(query, entity, store, limit = 10))
                        ?.let { found += ITunes.parse(it, store) }
                }
            }

            // MusicBrainz second: slower and thinner on film music, but the only
            // source that states a release language.
            val mbQuery = MusicBrainz.recordingQuery(
                parsed.title, parsed.artist, query, parsed.album
            )
            Net.getTextOrNull(MusicBrainz.recordingSearchUrl(mbQuery, limit = 10))
                ?.let { found += MusicBrainz.parseRecordings(it) }

            ranked = Matching.rank(
                found.distinctBy { it.source + ":" + it.id },
                parsed, durationMs, preferredLanguage,
            )
            val best = ranked.firstOrNull()?.score ?: 0.0
            if (best >= GOOD_ENOUGH_TO_STOP) break
            if (index == attempts.lastIndex) break
        }

        val deduped = found.distinctBy { it.source + ":" + it.id }
        return MusicResult(ranked = ranked, all = deduped)
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

    /**
     * Who sang it, who wrote it, who composed it.
     *
     * MusicBrainz is the only source here that records roles rather than a
     * run-together credit string, so this is the one way to put the singer --
     * and not the music director -- in the artist field. It needs the recording
     * to be in MusicBrainz at all, which for Indian film music is far from
     * certain; when it is not, nothing is returned and the caller leaves the
     * credit alone rather than guessing which name is the singer.
     */
    fun credits(chosen: Candidate, alternatives: List<Candidate>): Credits {
        val mbid = if (chosen.source == "MusicBrainz") {
            chosen.id
        } else {
            // The chosen match came from iTunes, but the same recording may
            // also have turned up from MusicBrainz in the same search.
            alternatives.firstOrNull {
                it.source == "MusicBrainz" &&
                        Matching.tokenOverlap(it.title, chosen.title) >= 0.8
            }?.id
        } ?: return Credits()

        return Net.getTextOrNull(MusicBrainz.recordingCreditsUrl(mbid))
            ?.let { MusicBrainz.parseRecordingCredits(it) }
            ?: Credits()
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

    /**
     * Subtitles from OpenSubtitles.
     *
     * Three calls: search, then a login for a download token, then the link the
     * download endpoint hands back. The token is kept for the session because
     * logging in per file would burn the account's quota for nothing.
     *
     * Returns null the moment anything is missing -- no key, no account, no
     * match -- because a file with no subtitles is a normal outcome and not
     * worth failing a save over.
     */
    fun subtitles(
        apiKey: String,
        username: String,
        password: String,
        query: String,
        kind: MediaKind,
        season: Int? = null,
        episode: Int? = null,
        year: String? = null,
        languages: List<String> = listOf("en"),
    ): SubtitleTrack? {
        if (apiKey.isBlank() || query.isBlank()) return null

        val found = Net.getTextOrNull(
            OpenSubtitles.searchUrl(
                query = query,
                languages = languages.joinToString(","),
                kind = kind,
                season = season,
                episode = episode,
                year = year,
            ),
            OpenSubtitles.headers(apiKey),
        ) ?: return null

        val best = OpenSubtitles.rank(OpenSubtitles.parseSearch(found), languages)
            .firstOrNull() ?: return null

        val token = downloadToken(apiKey, username, password) ?: return null
        val link = Net.postJsonOrNull(
            OpenSubtitles.downloadUrl(),
            OpenSubtitles.downloadBody(best.fileId),
            OpenSubtitles.headers(apiKey, token),
        )?.let { OpenSubtitles.parseDownloadLink(it) } ?: return null

        val text = Net.getTextOrNull(link) ?: return null
        val cues = Subtitles.parse(text)
        if (cues.isEmpty()) return null

        return SubtitleTrack(
            cues = cues,
            language = best.language ?: languages.firstOrNull(),
            source = "OpenSubtitles",
        )
    }

    /** Kept for the session: their downloads are rationed per account. */
    private var cachedToken: String? = null

    @Synchronized
    private fun downloadToken(apiKey: String, username: String, password: String): String? {
        cachedToken?.let { return it }
        if (username.isBlank() || password.isBlank()) return null
        val token = Net.postJsonOrNull(
            OpenSubtitles.loginUrl(),
            OpenSubtitles.loginBody(username, password),
            OpenSubtitles.headers(apiKey),
        )?.let { OpenSubtitles.parseLoginToken(it) }
        cachedToken = token
        return token
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
