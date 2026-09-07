package com.mykungfu.mvtagger.core

/**
 * Who made a film, in the one place players look for it.
 *
 * MP4 has atoms for a title, a year, a genre and a dozen other things, and
 * none at all for a director. Apple's answer, which iTunes wrote and Infuse,
 * Plex and everything else since have read, is a freeform atom called
 * `iTunMOVI` holding an XML plist: cast, directors, producers, screenwriters
 * and the studio, each a list of names.
 *
 * It looks like an odd thing to hand-build until you consider the
 * alternatives. There is no library for it that runs on Android without
 * dragging in a plist parser, the format is four tags deep and never changes,
 * and writing it by hand is thirty lines that can be tested. Reading it back
 * is the same in reverse.
 *
 * Names are held here as one comma-separated string per role, because that is
 * how every catalogue hands them over and how anybody types them.
 */
object MovieCredits {

    /** The freeform atom name Apple uses. Not ours to choose. */
    const val ATOM = "iTunMOVI"

    /** True when there is anything worth writing. */
    fun worthWriting(tags: VideoTags): Boolean =
        !tags.director.isNullOrBlank() || !tags.producers.isNullOrBlank() ||
                !tags.cast.isNullOrBlank() || !tags.studio.isNullOrBlank()

    /**
     * The plist, as iTunes writes it.
     *
     * Only the keys that have something in them. An empty `<array/>` under
     * `directors` says "this film had no director", which is a claim rather
     * than a silence.
     */
    fun plist(tags: VideoTags): String? {
        if (!worthWriting(tags)) return null
        val body = StringBuilder()
        section(body, "cast", tags.cast)
        section(body, "directors", tags.director)
        section(body, "producers", tags.producers)
        tags.studio?.trim()?.ifBlank { null }?.let {
            body.append("\t<key>studio</key>\n\t<string>").append(escape(it)).append("</string>\n")
        }
        if (body.isEmpty()) return null

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
                "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n<dict>\n" + body + "</dict>\n</plist>\n"
    }

    private fun section(out: StringBuilder, key: String, names: String?) {
        val people = split(names)
        if (people.isEmpty()) return
        out.append("\t<key>").append(key).append("</key>\n\t<array>\n")
        for (name in people) {
            out.append("\t\t<dict>\n\t\t\t<key>name</key>\n\t\t\t<string>")
                .append(escape(name)).append("</string>\n\t\t</dict>\n")
        }
        out.append("\t</array>\n")
    }

    /** What was in one, for a file somebody else tagged. */
    fun read(plist: String?): VideoTags? {
        if (plist.isNullOrBlank()) return null
        val cast = names(plist, "cast")
        val directors = names(plist, "directors")
        val producers = names(plist, "producers")
        val studio = value(plist, "studio")
        if (cast == null && directors == null && producers == null && studio == null) return null
        return VideoTags(
            cast = cast,
            director = directors,
            producers = producers,
            studio = studio,
        )
    }

    /**
     * The names under one key.
     *
     * Read with string searching rather than an XML parser, deliberately. The
     * shape is fixed and four tags deep, the file is a few hundred bytes, and
     * an XML parser on Android brings its own failure modes for something this
     * small. A plist that is not shaped as expected returns nothing rather
     * than half of something.
     */
    private fun names(plist: String, key: String): String? {
        val at = plist.indexOf("<key>" + key + "</key>")
        if (at < 0) return null
        val start = plist.indexOf("<array>", at)
        if (start < 0) return null
        val end = plist.indexOf("</array>", start)
        if (end < 0) return null

        val found = Regex("""<string>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(plist.substring(start, end))
            .map { unescape(it.groupValues[1]).trim() }
            .filter { it.isNotBlank() }
            .toList()
        return found.joinToString(", ").ifBlank { null }
    }

    private fun value(plist: String, key: String): String? {
        val at = plist.indexOf("<key>" + key + "</key>")
        if (at < 0) return null
        val start = plist.indexOf("<string>", at)
        if (start < 0) return null
        val end = plist.indexOf("</string>", start)
        if (end < 0) return null
        return unescape(plist.substring(start + 8, end)).trim().ifBlank { null }
    }

    /** Names with commas in them are rare and are not worth losing. */
    private fun split(names: String?): List<String> =
        names?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
