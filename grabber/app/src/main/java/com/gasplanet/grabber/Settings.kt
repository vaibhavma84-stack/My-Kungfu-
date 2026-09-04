package com.gasplanet.grabber

import android.content.Context
import android.content.SharedPreferences

/**
 * Every preference the app keeps, in one place, backed by SharedPreferences.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("grabber", Context.MODE_PRIVATE)

    var defaultQuality: Quality
        get() = runCatching { Quality.valueOf(prefs.getString("quality", null) ?: "") }
            .getOrDefault(Quality.BEST)
        set(v) = prefs.edit().putString("quality", v.name).apply()

    /** Ask for the highest resolution regardless of the quality cap when the
     *  clip is VR: a 4K sphere is only about 1080p once it fills your view. */
    var vrAlwaysBest: Boolean
        get() = prefs.getBoolean("vrAlwaysBest", true)
        set(v) = prefs.edit().putBoolean("vrAlwaysBest", v).apply()

    /** Tag saved VR files with the layout suffix players read. */
    var vrNameHints: Boolean
        get() = prefs.getBoolean("vrNameHints", true)
        set(v) = prefs.edit().putBoolean("vrNameHints", v).apply()

    var embedMetadata: Boolean
        get() = prefs.getBoolean("embedMetadata", true)
        set(v) = prefs.edit().putBoolean("embedMetadata", v).apply()

    var embedThumbnail: Boolean
        get() = prefs.getBoolean("embedThumbnail", false)
        set(v) = prefs.edit().putBoolean("embedThumbnail", v).apply()

    var writeSubtitles: Boolean
        get() = prefs.getBoolean("writeSubtitles", false)
        set(v) = prefs.edit().putBoolean("writeSubtitles", v).apply()

    var subtitleLanguages: String
        get() = prefs.getString("subLangs", "en") ?: "en"
        set(v) = prefs.edit().putString("subLangs", v).apply()

    /** Prefer H.264 video and AAC audio: bigger files, but they play on
     *  anything, including older TVs and standalone VR headsets. */
    var preferH264: Boolean
        get() = prefs.getBoolean("preferH264", false)
        set(v) = prefs.edit().putBoolean("preferH264", v).apply()

    /** When a link carries a playlist, take the whole playlist. */
    var grabWholePlaylist: Boolean
        get() = prefs.getBoolean("wholePlaylist", false)
        set(v) = prefs.edit().putBoolean("wholePlaylist", v).apply()

    /** Extra yt-dlp arguments, for anything the interface does not cover. */
    var extraArgs: String
        get() = prefs.getString("extraArgs", "") ?: ""
        set(v) = prefs.edit().putString("extraArgs", v).apply()

    var lastEngineUpdateCheck: Long
        get() = prefs.getLong("engineChecked", 0L)
        set(v) = prefs.edit().putLong("engineChecked", v).apply()

    var sites: List<Site>
        get() {
            val raw = prefs.getString("sites", null) ?: return DEFAULT_SITES
            return Site.listFromJson(raw).ifEmpty { DEFAULT_SITES }
        }
        set(v) = prefs.edit().putString("sites", Site.listToJson(v)).apply()

    fun resetSites() = prefs.edit().remove("sites").apply()

    companion object {
        /**
         * The tiles the app ships with. These are starting points, not a limit:
         * the engine works on roughly 1,800 sites, and anything not here can be
         * added by hand or simply shared into the app from its own app.
         */
        val DEFAULT_SITES = listOf(
            Site("YouTube", "https://m.youtube.com/", "Video"),
            Site("Vimeo", "https://vimeo.com/", "Video"),
            Site("Dailymotion", "https://www.dailymotion.com/", "Video"),
            Site("TikTok", "https://www.tiktok.com/", "Video"),
            Site("Reddit", "https://www.reddit.com/", "Video"),
            Site("X", "https://x.com/", "Video"),
            Site("Facebook", "https://m.facebook.com/", "Video"),
            Site("Instagram", "https://www.instagram.com/", "Video"),
            Site("Twitch", "https://m.twitch.tv/", "Video"),
            Site("Rumble", "https://rumble.com/", "Video"),
            Site("Odysee", "https://odysee.com/", "Video"),

            Site("YouTube Music", "https://music.youtube.com/", "Music"),
            Site("SoundCloud", "https://soundcloud.com/", "Music"),
            Site("Bandcamp", "https://bandcamp.com/", "Music"),
            Site("Vevo", "https://www.vevo.com/", "Music"),
            Site("NPR Tiny Desk", "https://www.npr.org/series/tiny-desk-concerts/", "Music"),

            Site("Internet Archive films", "https://archive.org/details/feature_films", "Films"),
            Site("Archive: silent films", "https://archive.org/details/silent_films", "Films"),
            Site("Public Domain Movies", "https://archive.org/details/moviesandfilms", "Films"),

            Site("YouTube 360°", "https://m.youtube.com/results?search_query=360+video+8k", "VR & 360"),
            Site("YouTube VR180", "https://m.youtube.com/results?search_query=vr180", "VR & 360"),
            Site("Vimeo 360°", "https://vimeo.com/search?q=360%20video", "VR & 360"),
            Site("VeeR VR", "https://veer.tv/", "VR & 360"),
            Site("Insta360 films", "https://m.youtube.com/@Insta360", "VR & 360"),
        )
    }
}
