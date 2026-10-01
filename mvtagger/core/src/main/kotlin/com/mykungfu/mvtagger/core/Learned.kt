package com.mykungfu.mvtagger.core

/**
 * What the app remembers from a correction, so the same fix is not needed twice.
 *
 * Until now nothing was learned from being put right. Five reports in one
 * afternoon were five separate failures on five files, and the sixth file from
 * the same channel, named by the same habit, would have failed in exactly the
 * same way. A collection is not a random sample: it is a few dozen channels
 * uploading under a few conventions, over and over.
 *
 * Two things are worth keeping, and they are the two a person actually
 * supplies by hand.
 *
 * **Who this uploader is.** The first field of a downloaded name is very often
 * the channel -- "Guru Randhawa | Nachle Na Video | ...", "Megan Thee Stallion
 * | Fantasy Pool Party" -- and a channel does not change who it is between
 * uploads. Once a match has been accepted for one of its files, the artist is
 * known for the rest.
 *
 * **What this is not.** A wrong answer that scores well scores well every
 * time. "Butter (Megan Thee Stallion Remix)" came top for a Megan Thee
 * Stallion live set, and no amount of ranking will teach the app that on its
 * own -- but a person can say so once.
 *
 * Both are keyed on the filename rather than on the file, so they carry across
 * to the next download and survive a rename. Nothing here is a guess: every
 * entry is something somebody chose.
 */
object Learned {

    /**
     * The handle for "files named like this one", or null when there is none.
     *
     * The leading field of the name. For the pipe and dash conventions that is
     * the channel or the artist, which is exactly the thing that repeats; for a
     * name with no fields at all there is nothing here worth remembering,
     * because a whole title identifies one recording rather than a source of
     * them.
     */
    fun uploaderKey(parsed: ParsedName, fileName: String): String? {
        val leading = leadingField(fileName) ?: return null
        // A field that is only the song is no use as a handle for a source of
        // songs, and the parser has already worked out which field that is.
        if (parsed.artist.isNullOrBlank() && parsed.extras.isEmpty()) return null
        return fold(leading)
    }

    /**
     * The handle for one particular recording, used for what it is *not*.
     *
     * The first field after the song is part of it, and a test is what
     * insisted: for a markerless name like "Megan Thee Stallion | Fantasy Pool
     * Party" the parser calls the channel the title, so artist and title alone
     * gave every file from that channel the same handle -- and rejecting one
     * answer would have rejected it for all of them. The field that tells the
     * uploads apart has to be in here.
     */
    fun recordingKey(parsed: ParsedName, fileName: String): String {
        val words = (listOfNotNull(parsed.artist, parsed.title) + parsed.extras.take(1))
            .joinToString(" ")
            .ifBlank { FilenameParser.stripExtension(fileName) }
        return fold(words)
    }

    /**
     * The first field of a name, before any separator this app recognises.
     *
     * Read from the raw name rather than from the parsed fields, because the
     * parser may have swapped them -- that is the whole reason the "Fantasy
     * Pool Party" file needed three rounds -- and the *first* field is what the
     * uploader wrote first, whichever half the parser decided it was.
     */
    private fun leadingField(fileName: String): String? {
        var text = FilenameParser.stripExtension(fileName)
        // The same flattening the parser does, so a name that arrived with its
        // separators mangled keys the same as one that did not.
        text = text.replace(Regex("_{2,}"), " | ")
        if (!text.contains(' ') && text.contains('_')) text = text.replace('_', ' ')

        val cut = listOf("|", " - ", " – ", " — ")
            .mapNotNull { sep -> text.indexOf(sep).takeIf { it > 0 } }
            .minOrNull() ?: return null
        return text.substring(0, cut).trim().ifBlank { null }
    }

    /** Comparable form: the same folding the matching uses, so spelling varies freely. */
    private fun fold(text: String): String =
        Matching.normalise(text).split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { Transliterate.latinFold(it) }

    /**
     * What is remembered about the file being looked at.
     *
     * Empty by default, so every caller that does not know anything behaves
     * exactly as it did before.
     */
    class Known(
        /** The artist accepted for this uploader before, if one was. */
        val artist: String? = null,
        /** `source:id` of candidates a person has said are not this recording. */
        val rejected: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean get() = artist.isNullOrBlank() && rejected.isEmpty()
    }

    /** How a candidate is named in [Known.rejected]. */
    fun idOf(candidate: Candidate): String = candidate.source + ":" + candidate.id
}
