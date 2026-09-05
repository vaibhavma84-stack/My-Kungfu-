package com.mykungfu.mvtagger.core

/**
 * OpenSubtitles, the only workable source for subtitles a file does not already
 * have.
 *
 * The odd one out among the providers here: every other one is anonymous, and
 * this needs an account. Searching takes an API key, and downloading takes a
 * login on top of it, because downloads are rationed per account. That is their
 * design, not a choice made here, and it is why subtitle fetching is off unless
 * the details are filled in.
 *
 * As with the rest of this file, nothing here makes a request. It builds URLs
 * and bodies and reads responses, so the whole path can be tested against saved
 * JSON without an account or a network.
 */
object OpenSubtitles {

    private const val BASE = "https://api.opensubtitles.com/api/v1"

    fun loginUrl() = "$BASE/login"
    fun downloadUrl() = "$BASE/download"

    /**
     * Headers every call needs. [token] is only present after logging in, and
     * only downloading requires it.
     */
    fun headers(apiKey: String, token: String? = null): Map<String, String> = buildMap {
        put("Api-Key", apiKey)
        if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
    }

    fun loginBody(username: String, password: String): String =
        "{" + quote("username") + ":" + quote(username) + "," +
            quote("password") + ":" + quote(password) + "}"

    fun parseLoginToken(body: String): String? =
        Json.parseOrNull(body)["token"].string?.takeIf { it.isNotBlank() }

    fun downloadBody(fileId: String): String =
        "{" + quote("file_id") + ":" + fileId + "}"

    /** The response to a download request is a link, not the file itself. */
    fun parseDownloadLink(body: String): String? =
        Json.parseOrNull(body)["link"].string?.takeIf { it.isNotBlank() }

    /**
     * A subtitle search.
     *
     * [languages] is a comma-separated list of two-letter codes. For an episode
     * the season and number narrow it to the right one; without them a search
     * for a series name returns every episode ever made.
     */
    fun searchUrl(
        query: String,
        languages: String = "en",
        kind: MediaKind = MediaKind.MOVIE,
        season: Int? = null,
        episode: Int? = null,
        year: String? = null,
    ): String {
        val sb = StringBuilder(BASE).append("/subtitles?query=").append(urlEncode(query))
        sb.append("&languages=").append(urlEncode(languages))
        when (kind) {
            MediaKind.TV_EPISODE -> {
                sb.append("&type=episode")
                season?.let { sb.append("&season_number=").append(it) }
                episode?.let { sb.append("&episode_number=").append(it) }
            }
            MediaKind.MOVIE -> sb.append("&type=movie")
            // Nobody subtitles a music video, a workout or a lecture, and
            // asking anyway would spend one of a rationed number of requests
            // on a search that cannot succeed.
            MediaKind.MUSIC_VIDEO, MediaKind.PODCAST,
            MediaKind.FITNESS, MediaKind.LEARNING -> Unit
        }
        if (!year.isNullOrBlank()) sb.append("&year=").append(urlEncode(year))
        return sb.toString()
    }

    /** One subtitle file on offer. */
    data class Match(
        val fileId: String,
        val language: String?,
        val fileName: String? = null,
        val downloads: Int = 0,
        val fromTrusted: Boolean = false,
        val hearingImpaired: Boolean = false,
        val machineTranslated: Boolean = false,
    )

    fun parseSearch(body: String): List<Match> =
        Json.parseOrNull(body)["data"].array.mapNotNull { entry ->
            val a = entry["attributes"]
            val file = a["files"].array.firstOrNull() ?: return@mapNotNull null
            val fileId = file["file_id"].string ?: return@mapNotNull null
            Match(
                fileId = fileId,
                language = Languages.normalise(a["language"].string) ?: a["language"].string,
                fileName = file["file_name"].string,
                downloads = a["download_count"].int ?: 0,
                fromTrusted = a["from_trusted"].string == "true",
                hearingImpaired = a["hearing_impaired"].string == "true",
                machineTranslated = a["machine_translated"].string == "true",
            )
        }

    /**
     * The one to take, best first.
     *
     * Ordered by the language the user asked for, then by whether a person made
     * it -- a machine translation is worse than no subtitle, because it looks
     * right until you read it -- then by how many people have used it, which is
     * the only quality signal available.
     */
    fun rank(matches: List<Match>, preferred: List<String>): List<Match> =
        matches.sortedWith(
            compareBy(
                { match ->
                    val at = preferred.indexOfFirst { it.equals(match.language, true) }
                    if (at < 0) preferred.size else at
                },
                { if (it.machineTranslated) 1 else 0 },
                { if (it.hearingImpaired) 1 else 0 },
                { if (it.fromTrusted) 0 else 1 },
                { -it.downloads },
            )
        )

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
}
