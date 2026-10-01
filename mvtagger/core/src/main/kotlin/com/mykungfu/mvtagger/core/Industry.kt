package com.mykungfu.mvtagger.core

/**
 * The shelf a film or a series goes on, in the words people actually use for
 * it.
 *
 * A library of a few hundred films splits along one line before any other:
 * Hindi or English. Everyone with such a library says "Bollywood" and
 * "Hollywood" rather than "hi" and "en", so those are the headings, and
 * everything else is named by its language -- Tamil, Telugu, Korean -- because
 * inventing a nickname for each industry would be guessing at what someone
 * else calls their own cinema.
 *
 * The imprecision is deliberate and worth stating: a British film is not
 * Hollywood and a Pakistani drama is not Bollywood. What the app knows is the
 * language of the file, which is the closest honest thing to hand, and it is
 * the right split in practice for the library this is sorting.
 */
object Industry {

    const val UNKNOWN = "Language not known"

    fun label(language: String?): String {
        val code = Languages.normalise(language) ?: return UNKNOWN
        return when (code) {
            "en" -> "Hollywood"
            "hi" -> "Bollywood"
            else -> Languages.displayName(code)
        }
    }

    /**
     * The order shelves appear in: the two big ones first, then the rest by
     * name, and the unplaceable last.
     *
     * Alphabetical throughout would put "Bollywood" above "Hollywood" and both
     * below "Assamese", which is tidy and useless. A shelf order should follow
     * how much of the library is on each shelf, and for this library that is
     * known in advance.
     */
    fun order(label: String): Int = when (label) {
        "Hollywood" -> 0
        "Bollywood" -> 1
        UNKNOWN -> 3
        else -> 2
    }
}
