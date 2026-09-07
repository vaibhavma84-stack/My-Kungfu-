package com.mykungfu.mvtagger.core

/**
 * The language of a song, read from its lyrics.
 *
 * Every other signal available is thin. A title is three words and half of them
 * are a name; the iTunes storefront says where a song sells, not what it is
 * sung in; a folder name says how someone happened to file it. Lyrics are
 * hundreds of words of the actual language, which makes them the one honest
 * thing to read.
 *
 * Two ways of reading them, because Hindi lyrics arrive both ways:
 *
 * **Script**, when the words are written in their own alphabet. Devanagari is
 * Hindi, Tamil script is Tamil, and there is nothing to work out.
 *
 * **Marker words**, when they are not. Lyrics sites carry Hindi songs
 * romanised far more often than in Devanagari -- "Tum hi ho, ab tum hi ho" --
 * and to a script test that is indistinguishable from English. But romanised
 * Hindi is full of words English never uses, and English is full of words Hindi
 * never uses, so counting both and comparing settles it.
 *
 * The whole thing is built to return null rather than guess. A song filed under
 * the wrong language is worse than one filed under none: "not known" is a
 * useful thing for a list to say, and something to go and correct, whereas a
 * confident wrong answer is neither.
 */
object LyricsLanguage {

    /**
     * Words that appear constantly in Hindi and effectively never in an English
     * song. Deliberately common ones -- pronouns, verbs, particles -- rather
     * than poetic vocabulary, because these turn up in every song rather than
     * in some of them.
     */
    private val HINDI = setOf(
        "hai", "hain", "ho", "hoon", "hu", "hun", "tha", "thi", "the",
        "tum", "tu", "tere", "tera", "teri", "tujhe", "tujh", "tumhe", "tumhi",
        "mera", "meri", "mere", "mujhe", "mujh", "main", "mai", "hum", "hamein",
        "kya", "kyun", "kyu", "kaise", "kahan", "kab", "koi", "kuch", "kuchh",
        "nahi", "nahin", "na", "haan", "bhi", "hi", "toh", "phir", "abhi",
        "yeh", "ye", "woh", "wo", "jo", "ki", "ke", "ka", "se", "mein", "par",
        "dil", "pyar", "pyaar", "ishq", "mohabbat", "jaan", "jaana", "sanam",
        "yaar", "dost", "khuda", "rab", "duniya", "zindagi", "zindagani",
        "raat", "raatein", "din", "subah", "sham", "chand", "aasman", "zameen",
        "aankhein", "aankhen", "aankh", "chehra", "baat", "baatein", "saath",
        "sath", "bina", "door", "paas", "yaad", "yaadein", "khwab", "sapne",
        "dard", "aansu", "khushi", "gham", "intezaar", "bekhudi", "junoon",
        "chal", "chalo", "aaja", "jaa", "aao", "sun", "suno", "dekh", "dekho",
        "bol", "kaho", "kar", "karo", "karke", "hua", "hui", "gaya", "gayi",
        "raha", "rahi", "rahe", "jaye", "jaaye", "jaana", "hone", "hoga",
        "bas", "sirf", "itna", "kitna", "jitna", "aisa", "waisa", "sab",
        "har", "ek", "do", "teri", "meri", "apna", "apni", "khud", "aur",
    )

    /**
     * The English side of the comparison. Function words rather than nouns:
     * these are what English cannot write a sentence without, so a genuinely
     * English lyric is dense with them.
     */
    private val ENGLISH = setOf(
        "the", "and", "you", "your", "yours", "that", "this", "with", "have",
        "from", "they", "them", "their", "what", "when", "where", "will",
        "would", "could", "should", "there", "here", "been", "being", "was",
        "were", "are", "for", "not", "but", "all", "can", "just", "like",
        "know", "your", "don", "doesn", "isn", "won", "ain", "gonna", "wanna",
        "never", "always", "every", "into", "over", "about", "again", "still",
        "only", "even", "than", "then", "because", "before", "after", "away",
        "down", "back", "come", "make", "take", "give", "want", "need", "feel",
        "love", "heart", "night", "day", "time", "life", "eyes", "baby",
    )

    /**
     * Enough words to be worth counting.
     *
     * A chorus repeated four times is not evidence, and neither is a two-line
     * fragment. Below this the answer is "no idea", which is the right answer.
     */
    private const val MIN_WORDS = 25

    /**
     * How far ahead one language has to be.
     *
     * Hindi songs borrow English words constantly ("baby", "love", "tonight"),
     * and English songs occasionally borrow the other way, so a narrow lead
     * means nothing. Twice as many hits, and enough of them to not be noise.
     */
    private const val MARGIN = 2.0
    private const val MIN_RATE = 0.02

    /** The language of these lyrics, or null when they do not clearly say. */
    fun detect(lyrics: String?): String? {
        val text = lyrics?.trim().orEmpty()
        if (text.isBlank()) return null

        // Written in its own script, there is nothing to weigh up.
        if (TextScript.hasNonLatin(text)) {
            Languages.fromScript(TextScript.dominant(text))?.let { return it }
        }

        val words = words(text)
        if (words.size < MIN_WORDS) return null

        val hindi = words.count { it in HINDI }
        val english = words.count { it in ENGLISH }
        val hindiRate = hindi.toDouble() / words.size
        val englishRate = english.toDouble() / words.size

        return when {
            hindiRate >= MIN_RATE && hindi >= english * MARGIN -> "hi"
            englishRate >= MIN_RATE && english >= hindi * MARGIN -> "en"
            else -> null
        }
    }

    /**
     * The words, with the timing lines of an .lrc thrown away.
     *
     * Synced lyrics carry a `[00:14.20]` stamp on every line. Left in, those
     * digits would not change the counts, but the words in a metadata header --
     * `[ar: Arijit Singh]` -- would.
     */
    private fun words(text: String): List<String> {
        val cleaned = StringBuilder()
        var inBracket = false
        for (ch in text) {
            when {
                ch == '[' -> inBracket = true
                ch == ']' -> inBracket = false
                inBracket -> Unit
                else -> cleaned.append(ch)
            }
        }
        return cleaned.toString()
            .lowercase()
            .split(Regex("""[^\p{L}]+"""))
            .filter { it.length >= 2 }
    }
}
