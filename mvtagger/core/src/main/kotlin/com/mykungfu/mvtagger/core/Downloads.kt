package com.mykungfu.mvtagger.core

/**
 * Choosing which of a video's streams to actually fetch.
 *
 * A site like YouTube does not hold one file per video. It holds a dozen: a
 * few complete ones at modest sizes, and above those, separate video-only and
 * audio-only streams meant to be played together and joined by whoever is
 * downloading. Which to take is not obvious, and the obvious answer -- the
 * biggest number -- is usually the wrong one for this app.
 *
 * ## Why the biggest number is the wrong answer
 *
 * Above 1080p there is no H.264. The large sizes exist only as VP9 or AV1
 * video with Opus audio, and this app already knows what that means, because
 * it says so on the collection screen: those cannot be written into an MP4,
 * so the details and artwork end up in a file beside the video instead of
 * inside it, and an iPad decodes them in software, which stutters and empties
 * the battery. A 4K download would arrive as the exact file the app warns
 * about elsewhere.
 *
 * So this prefers the MP4 family throughout: H.264 video, AAC audio. Where
 * that means taking a video-only stream and an audio-only stream and joining
 * them, both are still MP4-family, so the join is a copy rather than a
 * re-encode and costs nothing in quality.
 *
 * A file that exists only as WebM is still offered, with a warning, because a
 * video nobody can watch is worth less than one that plays imperfectly.
 */
object Downloads {

    enum class Container { MP4, M4A, WEBM, OTHER }

    /** Above this, nothing is offered in a form this app can use. */
    const val PRACTICAL_HEIGHT = 1080

    /** One stream as the site offers it. */
    class Option(
        /** Whatever the fetcher needs to get at it, usually a URL. */
        val id: String,
        val label: String,
        /** 0 for an audio-only stream. */
        val height: Int,
        val container: Container,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        /** Audio bitrate where the site says, for picking between two of them. */
        val bitrate: Int = 0,
    ) {
        val isProgressive: Boolean get() = hasVideo && hasAudio
    }

    /**
     * What to fetch. [audio] is set only when [video] carries no sound of its
     * own and the two have to be joined afterwards.
     */
    class Choice(
        val video: Option?,
        val audio: Option?,
        val warning: String? = null,
        /**
         * The tallest size the site offered, when it is bigger than the one
         * chosen. Zero when what was chosen is the best there was.
         *
         * Worth reporting rather than hiding: someone who knows the video is
         * in 4K and sees 1080p on the button deserves to be told why, and
         * "the bigger sizes are in a format this app cannot tag" is a reason
         * rather than a limitation.
         */
        val cappedFrom: Int = 0,
    ) {
        val needsJoining: Boolean get() = video != null && audio != null
    }

    /**
     * The best video worth taking, up to [ceiling].
     *
     * The order of preference, and the reasoning for each:
     *
     *  1. A video-only MP4 joined to an M4A. Highest quality this app can
     *     still tag, and joining is free.
     *  2. A complete MP4. No joining, so nothing can go wrong halfway.
     *  3. Anything else, with a warning, because WebM plays and cannot be
     *     tagged, which is worse but not useless.
     */
    fun bestVideo(options: List<Option>, ceiling: Int = PRACTICAL_HEIGHT): Choice? {
        if (options.isEmpty()) return null

        val usable = options.filter { it.height in 1..ceiling }
        val audio = bestAudio(options)

        val mp4Only = usable
            .filter { it.container == Container.MP4 && it.hasVideo && !it.hasAudio }
            .maxByOrNull { it.height }
        val mp4Whole = usable
            .filter { it.container == Container.MP4 && it.isProgressive }
            .maxByOrNull { it.height }

        // Joining only earns its place when it actually gets a better picture.
        // The tallest anything on offer, ceiling included, so the choice can
        // say what it passed over.
        val tallest = options.filter { it.hasVideo }.maxOfOrNull { it.height } ?: 0
        fun cap(chosen: Option): Int = if (tallest > chosen.height) tallest else 0

        if (mp4Only != null && audio != null && audio.container == Container.M4A &&
            mp4Only.height > (mp4Whole?.height ?: 0)
        ) {
            return Choice(mp4Only, audio, cappedFrom = cap(mp4Only))
        }
        if (mp4Whole != null) return Choice(mp4Whole, null, cappedFrom = cap(mp4Whole))
        if (mp4Only != null && audio != null) {
            return Choice(mp4Only, audio, cappedFrom = cap(mp4Only))
        }

        val anything = usable.filter { it.hasVideo }.maxByOrNull { it.height }
            ?: options.filter { it.hasVideo }.minByOrNull { it.height }
            ?: return null

        return Choice(
            anything,
            if (anything.hasAudio) null else bestAudio(options),
            warning = "This one is only offered as " + name(anything.container) +
                    ", which cannot hold tags inside it and needs an iPad to " +
                    "decode it in software. The details will be written beside it.",
        )
    }

    /**
     * The best sound on its own, for a music download.
     *
     * M4A first whatever the bitrate: it is an MP4 underneath, so the app can
     * write the artist, title and artwork straight into it the way it does for
     * a video. A higher-bitrate Opus file that cannot carry any of that is the
     * worse outcome for a library.
     */
    fun bestAudio(options: List<Option>): Option? {
        val sound = options.filter { it.hasAudio && !it.hasVideo }
        return sound.filter { it.container == Container.M4A }.maxByOrNull { it.bitrate }
            ?: sound.maxByOrNull { it.bitrate }
    }

    /** What the downloaded file is called before the tagger renames it properly. */
    fun fileName(title: String?, container: Container): String {
        val base = RenameTemplate.sanitise(title.orEmpty()).ifBlank { "Download" }
        return base + "." + extension(container)
    }

    fun extension(container: Container): String = when (container) {
        Container.MP4 -> "mp4"
        Container.M4A -> "m4a"
        Container.WEBM -> "webm"
        Container.OTHER -> "bin"
    }

    private fun name(container: Container): String = when (container) {
        Container.MP4 -> "MP4"
        Container.M4A -> "M4A"
        Container.WEBM -> "WebM"
        Container.OTHER -> "an unusual format"
    }
}
