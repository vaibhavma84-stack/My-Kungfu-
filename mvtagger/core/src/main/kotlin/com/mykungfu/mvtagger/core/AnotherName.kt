package com.mykungfu.mvtagger.core

/**
 * Taking the credits from a record that is this recording under another name.
 *
 * A collection of downloaded video has a lot of material no shop sells: live
 * sets, festival performances, channel uploads. A report for
 *
 *     Megan_Thee_Stallion__Fantasy_Pool_Party_(1080p).mp4
 *
 * is the case this exists for. Nothing called "Fantasy Pool Party" is for sale
 * anywhere, but Apple has "Girls In The Hood & Savage Remix Performance" by
 * her, running four minutes twenty-nine, which is the file's length to the
 * second. It is almost certainly the same video under Apple's own name. The
 * scoring says so now, and says it carefully -- [Matching.Scored.anotherName]
 * -- because a running time is evidence and a name nobody shares is not.
 *
 * So the record is worth something and is not worth copying. What it can be
 * trusted for is who made it: the artist, the artwork, the genre. What it must
 * not be allowed to do is rename the file's own recording after a different
 * one, which is what applying it whole would do.
 *
 * Everything else is left alone deliberately. The album is a different
 * release; the date is that release's and not this performance's; the track
 * number belongs to a record this file is not on. A year that is probably
 * right is still a year nobody checked, and this app's promise is that a tag
 * it wrote can be explained.
 */
object AnotherName {

    /**
     * The title the file itself claims, which is the one to keep.
     *
     * Usually that is simply what the parser called the title. But this whole
     * case arises from a name the parser read the wrong way round -- "Megan
     * Thee Stallion | Fantasy Pool Party" has no marker word on either half,
     * so the artist ended up in the title -- and the candidate is what settles
     * it. A candidate whose *artist* is what the parser called the title is
     * proof of the inversion, and then the file's real title is the other half.
     */
    fun titleFromTheFile(parsed: ParsedName, candidate: Candidate): String? {
        val inverted = Matching.tokenOverlap(parsed.title, candidate.artist) >= 0.6
        if (inverted) {
            parsed.extras.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return parsed.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: parsed.extras.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Who made it, from the record; what it is called, from the file.
     *
     * [mediaKind] comes from the file's own reading rather than the
     * candidate's, because the candidate is a different release and its kind
     * describes that release.
     */
    fun tags(candidate: Candidate, parsed: ParsedName, mediaKind: MediaKind): VideoTags =
        VideoTags(
            mediaKind = mediaKind,
            title = titleFromTheFile(parsed, candidate),
            artist = candidate.artist,
            albumArtist = candidate.albumArtist ?: candidate.artist,
            genre = candidate.genre,
            language = candidate.language,
            source = candidate.source,
            sourceId = candidate.id,
        )

    /** What the button says, and what it promises. */
    const val LABEL = "Take the artist and artwork only"

    const val EXPLANATION =
        "Keeps the name this file already has, and takes only who made it " +
                "and the picture from that record."
}
