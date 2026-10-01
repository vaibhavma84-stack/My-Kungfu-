package com.mykungfu.mvtagger.core

/**
 * Whether a file will play well on an iPad, and why not when it will not.
 *
 * This exists because "Infuse does not read 4K files like VLC does" is a real
 * complaint with a cause that has nothing to do with Infuse. Apple's players
 * lean on the hardware decoders in the chip; VLC and nPlayer fall back to
 * decoding in software, which is slower and hotter but works on anything. So
 * the same file plays in one and stutters in the other, and from the outside
 * that looks like one app being worse.
 *
 * What is actually true is narrower and more useful: an iPad has silicon for
 * H.264 and HEVC and nothing else. A file in VP9 or AV1, or in the ten-bit
 * flavour of H.264, has to be decoded in software whatever app opens it. At
 * 720p nobody notices. At 4K it stutters and empties the battery.
 *
 * Knowing which is which before copying forty gigabytes onto an iPad is the
 * point. This says so from the codecs the file actually contains, which the app
 * has already read.
 *
 * ## What is being claimed
 *
 * Only that Apple hardware-decodes H.264 up to High profile at eight bits, and
 * HEVC Main and Main 10 -- true of every iPad since 2016 -- and that AV1 needs
 * an A17 Pro or M3 and newer. Everything else is left to software. Nothing here
 * knows which iPad, so AV1 is reported as the risk it is on most of them.
 *
 * Audio is judged the same way: AAC, MP3, ALAC, AC-3 and E-AC-3 are all carried
 * natively. DTS and TrueHD are not, and a player that manages them is doing it
 * in software.
 */
object ApplePlayback {

    enum class Level {
        /** The chip decodes it. Nothing to think about. */
        HARDWARE,

        /** It will play, but the processor is doing the work. */
        SOFTWARE,

        /** Apple's own players will not open it at all. */
        NONE,
    }

    /**
     * [warning] is null when there is nothing worth saying -- which includes a
     * file that needs software decoding at a size where nobody would notice.
     */
    class Verdict(val level: Level, val reason: String, val warning: String?)

    private const val AVC = "video/avc"
    private const val HEVC = "video/hevc"
    private const val VP8 = "video/x-vnd.on2.vp8"
    private const val VP9 = "video/x-vnd.on2.vp9"
    private const val AV1 = "video/av01"

    /** Above this on the long edge, software decoding starts to hurt. */
    private const val BIG = 1900

    private val AUDIO_NATIVE = setOf(
        "audio/mp4a-latm", "audio/mpeg", "audio/alac", "audio/ac3", "audio/eac3",
        "audio/raw", "audio/flac", "audio/opus",
    )

    private val FRIENDLY = mapOf(
        AVC to "H.264",
        HEVC to "H.265",
        VP8 to "VP8",
        VP9 to "VP9",
        AV1 to "AV1",
        "video/mp4v-es" to "MPEG-4",
        "video/mpeg2" to "MPEG-2",
        "video/x-ms-wmv" to "WMV",
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD",
        "audio/true-hd" to "Dolby TrueHD",
        "audio/vorbis" to "Vorbis",
        "audio/opus" to "Opus",
        "audio/ac3" to "Dolby Digital",
        "audio/eac3" to "Dolby Digital Plus",
        "audio/mp4a-latm" to "AAC",
        "audio/mpeg" to "MP3",
        "audio/flac" to "FLAC",
    )

    fun friendly(mime: String?): String =
        FRIENDLY[mime] ?: mime?.substringAfter('/')?.uppercase() ?: "unknown"

    /**
     * [tenBit] means H.264 in its ten-bit profile, which no Apple device has
     * ever had a decoder for, and which is not otherwise visible from the MIME
     * type. Ten-bit HEVC is fine and is a different thing entirely.
     */
    fun check(
        video: String?,
        audio: String?,
        tenBit: Boolean = false,
        longEdge: Int? = null,
    ): Verdict {
        val big = (longEdge ?: 0) >= BIG
        val name = friendly(video)

        // Video decides it. Bad audio is an annoyance; bad video is the film.
        val videoVerdict = when {
            video == null -> Verdict(Level.SOFTWARE, "No video track was found.", null)

            video == AVC && tenBit -> soft(
                "This is ten-bit H.264, which no Apple device has a decoder for.",
                big,
                "10-bit H.264",
            )

            video == AVC || video == HEVC ->
                Verdict(Level.HARDWARE, name + " plays straight off the chip.", null)

            video == AV1 -> soft(
                "AV1 only decodes in hardware on an A17 Pro or M3 and newer.",
                big,
                "AV1",
            )

            video == VP9 || video == VP8 -> soft(
                name + " has no hardware decoder on any iPad.",
                big,
                name,
            )

            else -> soft(name + " has no hardware decoder on an iPad.", big, name)
        }

        // Audio only gets a word in when the video had nothing to say, since
        // two warnings on one row is one more than anybody reads.
        if (videoVerdict.warning != null || audio == null) return videoVerdict
        if (audio in AUDIO_NATIVE) return videoVerdict

        val audioName = friendly(audio)
        return Verdict(
            videoVerdict.level,
            videoVerdict.reason + " The audio is " + audioName +
                    ", which an iPad decodes in software.",
            audioName + " audio",
        )
    }

    /**
     * Software decoding, warned about only when the size makes it matter.
     *
     * A 720p file decoded in software is nothing; the same codec at 4K stutters
     * and drains the battery. Warning about both would make the badge noise,
     * and a badge that is always on is a badge nobody reads.
     */
    private fun soft(reason: String, big: Boolean, short: String): Verdict = Verdict(
        Level.SOFTWARE,
        reason + if (big) {
            " At this size it will stutter on an iPad and empty the battery."
        } else {
            " At this size that is no trouble."
        },
        if (big) short else null,
    )
}
