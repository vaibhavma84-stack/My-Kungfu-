package com.mykungfu.mvtagger.core

import java.net.URLEncoder

/**
 * The online sources, as pure URL building and response parsing.
 *
 * No HTTP happens in this file. Every provider is a pair of functions -- one
 * that says what to fetch, one that reads what came back -- so the whole
 * lookup path can be tested against saved responses without a network, and the
 * Android side only has to supply a fetcher.
 *
 * All of these are free and need no account. The one exception is [Tmdb],
 * which is skipped entirely unless a key has been entered in Settings.
 */

fun urlEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** One possible identification of a video, from any source. */
data class Candidate(
    val source: String,
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val composer: String? = null,
    val date: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val durationMs: Int? = null,
    /** Plot or blurb, for a film or an episode. */
    val description: String? = null,
    val showName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val network: String? = null,
    val mediaKind: MediaKind = MediaKind.MUSIC_VIDEO,
    val language: String? = null,
    /** Artwork to try in order; the first that downloads wins. */
    val artworkUrls: List<String> = emptyList(),
    /** `musicVideo`, `song` or `recording` -- what the source matched. */
    val kind: String = "song",
    /** iTunes storefront this came from, which hints at the language. */
    val storefront: String? = null,
    /** MusicBrainz release id, for Cover Art Archive. */
    val releaseId: String? = null,
    val releaseGroupId: String? = null,
) {
    val year: String? get() = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

    fun toTags(): VideoTags = VideoTags(
        mediaKind = mediaKind,
        title = title,
        artist = artist,
        albumArtist = albumArtist ?: artist,
        album = album,
        date = date,
        genre = genre,
        composer = composer,
        trackNumber = trackNumber,
        trackTotal = trackTotal,
        language = language,
        source = source,
        sourceId = id,
        description = description?.take(250),
        longDescription = description,
        showName = showName,
        seasonNumber = season,
        episodeNumber = episode,
        network = network,
    )
}

/** Background on a performer, for the library's artist page. */
data class ArtistInfo(
    val name: String,
    val id: String? = null,
    val sortName: String? = null,
    val disambiguation: String? = null,
    val type: String? = null,
    val country: String? = null,
    val beginDate: String? = null,
    val endDate: String? = null,
    val genres: List<String> = emptyList(),
    val summary: String? = null,
    val summarySource: String? = null,
    val imageUrl: String? = null,
    val pageUrl: String? = null,
)

/** Background on an album, which for Indian film music is the film itself. */
data class AlbumInfo(
    val title: String,
    val id: String? = null,
    val artist: String? = null,
    val date: String? = null,
    /** `Album`, `Single`, `Soundtrack`, `EP`. */
    val type: String? = null,
    val secondaryTypes: List<String> = emptyList(),
    val trackCount: Int? = null,
    val genres: List<String> = emptyList(),
    val summary: String? = null,
    val summarySource: String? = null,
    val coverUrl: String? = null,
    val pageUrl: String? = null,
) {
    /** True when the album is a film soundtrack, so its cover is a film poster. */
    val isSoundtrack: Boolean
        get() = secondaryTypes.any { it.equals("Soundtrack", true) } ||
                type.equals("Soundtrack", true) ||
                title.contains("Original Motion Picture", true) ||
                title.contains("Soundtrack", true)
}

data class Lyrics(
    val plain: String? = null,
    /** LRC form, with `[mm:ss.xx]` timestamps. */
    val synced: String? = null,
    val source: String = "LRCLIB",
) {
    val isEmpty: Boolean get() = plain.isNullOrBlank() && synced.isNullOrBlank()
}

// --------------------------------------------------------------------- iTunes

/**
 * Apple's search endpoint. No key, generous limits, and -- the reason it is
 * first here -- the Indian storefront has deep Bollywood and regional coverage
 * that MusicBrainz does not.
 */
object ITunes {

    /** Storefronts to ask, in the order the answers are preferred. */
    val STOREFRONTS = listOf("IN", "US", "GB")

    /**
     * [entity] is `musicVideo` or `song`. Both are worth asking: the music
     * video entry names the video, but its artwork is a frame from the video,
     * whereas the song entry carries the album cover -- which is the film
     * poster for a soundtrack. See [ArtworkPlan].
     */
    fun searchUrl(query: String, entity: String = "song", country: String = "US", limit: Int = 12) =
        "https://itunes.apple.com/search?term=" + urlEncode(query) +
                "&entity=" + entity +
                "&country=" + country +
                "&limit=" + limit

    fun parse(body: String, storefront: String): List<Candidate> {
        val root = Json.parseOrNull(body)
        return root["results"].array.mapNotNull { r ->
            val title = r["trackName"].string ?: r["collectionName"].string ?: return@mapNotNull null
            val appleKind = r["kind"].string
            val kind = when (appleKind) {
                "music-video" -> "musicVideo"
                "feature-movie" -> "movie"
                "tv-episode" -> "tvEpisode"
                else -> "song"
            }
            val mediaKind = when (kind) {
                "movie" -> MediaKind.MOVIE
                "tvEpisode" -> MediaKind.TV_EPISODE
                else -> MediaKind.MUSIC_VIDEO
            }
            Candidate(
                source = "iTunes",
                id = r["trackId"].string ?: r["collectionId"].string ?: title,
                title = title,
                artist = r["artistName"].string,
                album = r["collectionName"].string,
                albumArtist = r["collectionArtistName"].string ?: r["artistName"].string,
                date = r["releaseDate"].string?.take(10),
                genre = r["primaryGenreName"].string,
                trackNumber = r["trackNumber"].int,
                trackTotal = r["trackCount"].int,
                durationMs = r["trackTimeMillis"].int,
                artworkUrls = artworkSizes(r["artworkUrl100"].string ?: r["artworkUrl60"].string),
                kind = kind,
                mediaKind = mediaKind,
                description = r["longDescription"].string ?: r["shortDescription"].string,
                showName = r["artistName"].string.takeIf { kind == "tvEpisode" },
                storefront = storefront,
                language = Languages.guess(title = title, storefront = storefront),
            )
        }
    }

    /**
     * Apple encodes the size in the artwork path, so a bigger version is a
     * string substitution away. Largest first, falling back down.
     */
    fun artworkSizes(url: String?): List<String> {
        if (url.isNullOrBlank()) return emptyList()
        val stem = Regex("""/\d+x\d+bb""").replace(url) { "/SIZExSIZEbb" }
        if (!stem.contains("SIZExSIZE")) return listOf(url)
        return listOf("1000", "600", "400").map {
            stem.replace("SIZExSIZE", it + "x" + it)
        } + url
    }
}

// ---------------------------------------------------------------- MusicBrainz

/**
 * The open music database. Slower and thinner on film music than iTunes, but it
 * is the only one of the two that will say what language a release is in, and
 * it is the key to Cover Art Archive, artist pages and album pages.
 *
 * It requires a descriptive User-Agent and allows one request a second; both
 * are the caller's job, and [USER_AGENT] is here so there is one spelling of it.
 */
object MusicBrainz {

    const val USER_AGENT =
        "MVTagger/1.0 ( https://github.com/vaibhavma84-stack/my-kungfu- )"

    /** One request per second, as the service asks. */
    const val MIN_INTERVAL_MS = 1100L

    private const val BASE = "https://musicbrainz.org/ws/2"

    fun recordingSearchUrl(query: String, limit: Int = 12) =
        BASE + "/recording/?query=" + urlEncode(query) + "&fmt=json&limit=" + limit

    /** A fielded query beats a bare one when the split is trustworthy. */
    fun recordingQuery(title: String?, artist: String?, fallback: String): String {
        val t = title?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        return when {
            t.isNotEmpty() && a.isNotEmpty() -> "recording:\"" + t + "\" AND artist:\"" + a + "\""
            t.isNotEmpty() -> "recording:\"" + t + "\""
            else -> fallback
        }
    }

    fun parseRecordings(body: String): List<Candidate> {
        val root = Json.parseOrNull(body)
        return root["recordings"].array.mapNotNull { rec ->
            val title = rec["title"].string ?: return@mapNotNull null
            val artist = rec["artist-credit"].array
                .mapNotNull { it["name"].string }
                .joinToString(", ")
                .ifBlank { null }
            val release = rec["releases"].array.firstOrNull() ?: Json.Null
            val releaseId = release["id"].string
            val rgId = release["release-group"]["id"].string
            val language = Languages.normalise(
                release["text-representation"]["language"].string
            ) ?: Languages.guess(title = title)
            Candidate(
                source = "MusicBrainz",
                id = rec["id"].string ?: title,
                title = title,
                artist = artist,
                album = release["title"].string,
                date = rec["first-release-date"].string ?: release["date"].string,
                durationMs = rec["length"].int,
                language = language,
                artworkUrls = listOfNotNull(
                    releaseId?.let { CoverArtArchive.releaseFront(it) },
                    rgId?.let { CoverArtArchive.releaseGroupFront(it) },
                ),
                kind = "recording",
                releaseId = releaseId,
                releaseGroupId = rgId,
            )
        }
    }

    fun artistSearchUrl(name: String, limit: Int = 5) =
        BASE + "/artist/?query=" + urlEncode(name) + "&fmt=json&limit=" + limit

    fun artistUrl(mbid: String) =
        BASE + "/artist/" + mbid + "?fmt=json&inc=url-rels+genres+aliases"

    fun parseArtists(body: String): List<ArtistInfo> {
        val root = Json.parseOrNull(body)
        return root["artists"].array.mapNotNull { a ->
            val name = a["name"].string ?: return@mapNotNull null
            ArtistInfo(
                name = name,
                id = a["id"].string,
                sortName = a["sort-name"].string,
                disambiguation = a["disambiguation"].string?.ifBlank { null },
                type = a["type"].string,
                country = a["country"].string ?: a["area"]["name"].string,
                beginDate = a["life-span"]["begin"].string,
                endDate = a["life-span"]["end"].string,
                genres = a["tags"].array.mapNotNull { it["name"].string }.take(6),
                summarySource = "MusicBrainz",
            )
        }
    }

    /** The detail call adds genres and the outbound links used to find Wikipedia. */
    fun parseArtistDetail(body: String, base: ArtistInfo? = null): ArtistInfo {
        val a = Json.parseOrNull(body)
        val name = a["name"].string ?: base?.name ?: return base ?: ArtistInfo("Unknown")
        val wiki = a["relations"].array
            .firstOrNull { it["type"].string == "wikipedia" }?.get("url")?.get("resource")?.string
        return ArtistInfo(
            name = name,
            id = a["id"].string ?: base?.id,
            sortName = a["sort-name"].string ?: base?.sortName,
            disambiguation = a["disambiguation"].string?.ifBlank { null } ?: base?.disambiguation,
            type = a["type"].string ?: base?.type,
            country = a["country"].string ?: a["area"]["name"].string ?: base?.country,
            beginDate = a["life-span"]["begin"].string ?: base?.beginDate,
            endDate = a["life-span"]["end"].string ?: base?.endDate,
            genres = a["genres"].array.mapNotNull { it["name"].string }.take(8)
                .ifEmpty { base?.genres ?: emptyList() },
            summary = base?.summary,
            summarySource = "MusicBrainz",
            pageUrl = wiki ?: base?.pageUrl,
        )
    }

    fun releaseGroupSearchUrl(album: String, artist: String?, limit: Int = 5): String {
        val q = if (artist.isNullOrBlank()) "releasegroup:\"" + album + "\""
        else "releasegroup:\"" + album + "\" AND artist:\"" + artist + "\""
        return BASE + "/release-group/?query=" + urlEncode(q) + "&fmt=json&limit=" + limit
    }

    fun parseReleaseGroups(body: String): List<AlbumInfo> {
        val root = Json.parseOrNull(body)
        return root["release-groups"].array.mapNotNull { g ->
            val title = g["title"].string ?: return@mapNotNull null
            val id = g["id"].string
            AlbumInfo(
                title = title,
                id = id,
                artist = g["artist-credit"].array.mapNotNull { it["name"].string }
                    .joinToString(", ").ifBlank { null },
                date = g["first-release-date"].string?.ifBlank { null },
                type = g["primary-type"].string,
                secondaryTypes = g["secondary-types"].array.mapNotNull { it.string },
                trackCount = g["releases"].array.firstOrNull()?.get("track-count")?.int,
                genres = g["tags"].array.mapNotNull { it["name"].string }.take(6),
                coverUrl = id?.let { CoverArtArchive.releaseGroupFront(it) },
                summarySource = "MusicBrainz",
            )
        }
    }
}

// ----------------------------------------------------------- Cover Art Archive

object CoverArtArchive {
    fun releaseFront(releaseId: String) =
        "https://coverartarchive.org/release/" + releaseId + "/front-500"

    fun releaseGroupFront(releaseGroupId: String) =
        "https://coverartarchive.org/release-group/" + releaseGroupId + "/front-500"
}

// --------------------------------------------------------------------- LRCLIB

/**
 * Lyrics, plain and timestamped. No key, no rate limit worth worrying about.
 *
 * The exact-match endpoint wants artist, track and duration and returns one
 * answer or a 404; the search endpoint takes a phrase and returns a list. Both
 * are used: exact first, search as the fallback.
 */
object LrcLib {

    private const val BASE = "https://lrclib.net/api"

    fun getUrl(artist: String, track: String, album: String? = null, durationSec: Int? = null): String {
        val sb = StringBuilder(BASE)
        sb.append("/get?artist_name=").append(urlEncode(artist))
        sb.append("&track_name=").append(urlEncode(track))
        if (!album.isNullOrBlank()) sb.append("&album_name=").append(urlEncode(album))
        if (durationSec != null && durationSec > 0) sb.append("&duration=").append(durationSec)
        return sb.toString()
    }

    fun searchUrl(query: String) = BASE + "/search?q=" + urlEncode(query)

    /** The single-result shape from `/get`. */
    fun parseOne(body: String): Lyrics? {
        val o = Json.parseOrNull(body)
        if (o is Json.Null) return null
        val lyrics = Lyrics(
            plain = o["plainLyrics"].string?.ifBlank { null },
            synced = o["syncedLyrics"].string?.ifBlank { null },
        )
        return lyrics.takeIf { !it.isEmpty }
    }

    /** The array shape from `/search`, best first. */
    fun parseSearch(body: String): List<Lyrics> =
        Json.parseOrNull(body).array.mapNotNull { o ->
            Lyrics(
                plain = o["plainLyrics"].string?.ifBlank { null },
                synced = o["syncedLyrics"].string?.ifBlank { null },
            ).takeIf { !it.isEmpty }
        }
}

// ------------------------------------------------------------------ Wikipedia

/**
 * Where the readable background comes from for artist and album pages.
 *
 * Two calls: a search to turn a name into a page title, then the REST summary
 * for the first paragraph and a thumbnail.
 */
object Wikipedia {

    fun searchUrl(term: String, lang: String = "en", limit: Int = 5) =
        "https://" + lang + ".wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
                urlEncode(term) + "&format=json&srlimit=" + limit + "&origin=*"

    fun parseSearchTitles(body: String): List<String> =
        Json.parseOrNull(body)["query"]["search"].array.mapNotNull { it["title"].string }

    fun summaryUrl(pageTitle: String, lang: String = "en") =
        "https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/" +
                urlEncode(pageTitle.replace(' ', '_'))

    data class Summary(
        val title: String,
        val extract: String?,
        val description: String?,
        val imageUrl: String?,
        val pageUrl: String?,
    )

    fun parseSummary(body: String): Summary? {
        val o = Json.parseOrNull(body)
        val title = o["title"].string ?: return null
        // Disambiguation pages are never the answer.
        if (o["type"].string == "disambiguation") return null
        return Summary(
            title = title,
            extract = o["extract"].string?.ifBlank { null },
            description = o["description"].string?.ifBlank { null },
            imageUrl = o["thumbnail"]["source"].string ?: o["originalimage"]["source"].string,
            pageUrl = o["content_urls"]["desktop"]["page"].string,
        )
    }

    /**
     * Search terms for a page, most specific first. The parenthesised hints are
     * how Wikipedia actually disambiguates these, so they find the right page
     * far more often than the bare name does.
     */
    fun artistTerms(name: String): List<String> =
        listOf(name + " (singer)", name + " (musician)", name)

    fun albumTerms(album: String, artist: String?, soundtrack: Boolean): List<String> = buildList {
        if (soundtrack) {
            add(album + " (film)")
            add(album + " (soundtrack)")
        }
        if (!artist.isNullOrBlank()) add(album + " " + artist + " album")
        add(album + " (album)")
        add(album)
    }
}

// ----------------------------------------------------------------------- TMDb

/**
 * Optional. The only source here that needs a key, and the only one that gives
 * a real film poster rather than a soundtrack cover -- which for a Hindi song
 * is usually the same picture, but not always. Skipped entirely when no key is
 * configured, so the app works fully without one.
 */
object Tmdb {

    fun searchMovieUrl(apiKey: String, title: String, year: String? = null, lang: String = "hi-IN"): String {
        val sb = StringBuilder("https://api.themoviedb.org/3/search/movie?api_key=")
        sb.append(urlEncode(apiKey)).append("&query=").append(urlEncode(title))
        sb.append("&language=").append(lang)
        if (!year.isNullOrBlank()) sb.append("&year=").append(year)
        return sb.toString()
    }

    data class Movie(
        val id: String,
        val title: String,
        val originalTitle: String?,
        val releaseDate: String?,
        val overview: String?,
        val posterPath: String?,
    ) {
        fun posterUrls(): List<String> = posterPath?.let {
            listOf("w780", "w500", "original").map { size ->
                "https://image.tmdb.org/t/p/" + size + it
            }
        } ?: emptyList()
    }

    fun parseMovies(body: String): List<Movie> =
        Json.parseOrNull(body)["results"].array.mapNotNull { m ->
            val title = m["title"].string ?: m["original_title"].string ?: return@mapNotNull null
            Movie(
                id = m["id"].string ?: title,
                title = title,
                originalTitle = m["original_title"].string,
                releaseDate = m["release_date"].string?.ifBlank { null },
                overview = m["overview"].string?.ifBlank { null },
                posterPath = m["poster_path"].string?.ifBlank { null },
            )
        }
}


// --------------------------------------------------------------------- TVmaze

/**
 * Television, and the reason series work without an API key at all: TVmaze is
 * open, needs no account, and answers episode-by-number directly, which is
 * exactly the question an `S01E02` filename asks.
 */
object TvMaze {

    private const val BASE = "https://api.tvmaze.com"

    fun searchShowsUrl(name: String) = BASE + "/search/shows?q=" + urlEncode(name)

    fun episodeUrl(showId: String, season: Int, episode: Int) =
        BASE + "/shows/" + showId + "/episodebynumber?season=" + season + "&number=" + episode

    data class Show(
        val id: String,
        val name: String,
        val premiered: String? = null,
        val network: String? = null,
        val genres: List<String> = emptyList(),
        val summary: String? = null,
        val imageUrl: String? = null,
    )

    fun parseShows(body: String): List<Show> =
        Json.parseOrNull(body).array.mapNotNull { entry ->
            val show = if (entry["show"] is Json.Obj) entry["show"] else entry
            val name = show["name"].string ?: return@mapNotNull null
            Show(
                id = show["id"].string ?: return@mapNotNull null,
                name = name,
                premiered = show["premiered"].string?.ifBlank { null },
                network = show["network"]["name"].string
                    ?: show["webChannel"]["name"].string,
                genres = show["genres"].array.mapNotNull { it.string },
                summary = stripHtml(show["summary"].string),
                imageUrl = show["image"]["original"].string ?: show["image"]["medium"].string,
            )
        }

    /** One episode, from the by-number endpoint. */
    fun parseEpisode(body: String, show: Show): Candidate? {
        val o = Json.parseOrNull(body)
        val title = o["name"].string ?: return null
        return Candidate(
            source = "TVmaze",
            id = o["id"].string ?: title,
            title = title,
            artist = show.name,
            album = show.name,
            date = o["airdate"].string?.ifBlank { null } ?: show.premiered,
            genre = show.genres.firstOrNull(),
            durationMs = o["runtime"].int?.let { it * 60_000 },
            description = stripHtml(o["summary"].string) ?: show.summary,
            showName = show.name,
            season = o["season"].int,
            episode = o["number"].int,
            network = show.network,
            mediaKind = MediaKind.TV_EPISODE,
            kind = "tvEpisode",
            artworkUrls = listOfNotNull(
                o["image"]["original"].string,
                o["image"]["medium"].string,
                show.imageUrl,
            ),
        )
    }

    /** TVmaze summaries are a paragraph of HTML; the app wants plain text. */
    fun stripHtml(html: String?): String? =
        html?.replace(Regex("<[^>]*>"), "")
            ?.replace("&amp;", "&")?.replace("&quot;", "\"")
            ?.replace("&#39;", "'")?.replace("&nbsp;", " ")
            ?.replace("&lt;", "<")?.replace("&gt;", ">")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.ifBlank { null }
}
