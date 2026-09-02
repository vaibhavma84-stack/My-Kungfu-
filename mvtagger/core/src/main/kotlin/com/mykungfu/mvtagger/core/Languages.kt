package com.mykungfu.mvtagger.core

/** Writing systems this app can tell apart from a title alone. */
enum class Script {
    LATIN, DEVANAGARI, BENGALI, GURMUKHI, GUJARATI, ORIYA, TAMIL, TELUGU,
    KANNADA, MALAYALAM, ARABIC, CYRILLIC, GREEK, HEBREW, THAI, HAN, KANA,
    HANGUL, OTHER,
}

object TextScript {

    /** The script of a single character, or null for digits, spaces and punctuation. */
    fun of(ch: Char): Script? = when (ch.code) {
        in 0x0900..0x097F -> Script.DEVANAGARI
        in 0x0980..0x09FF -> Script.BENGALI
        in 0x0A00..0x0A7F -> Script.GURMUKHI
        in 0x0A80..0x0AFF -> Script.GUJARATI
        in 0x0B00..0x0B7F -> Script.ORIYA
        in 0x0B80..0x0BFF -> Script.TAMIL
        in 0x0C00..0x0C7F -> Script.TELUGU
        in 0x0C80..0x0CFF -> Script.KANNADA
        in 0x0D00..0x0D7F -> Script.MALAYALAM
        in 0x0E00..0x0E7F -> Script.THAI
        in 0x0590..0x05FF -> Script.HEBREW
        in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF -> Script.ARABIC
        in 0x0370..0x03FF, in 0x1F00..0x1FFF -> Script.GREEK
        in 0x0400..0x04FF -> Script.CYRILLIC
        in 0x3040..0x30FF -> Script.KANA
        in 0xAC00..0xD7AF, in 0x1100..0x11FF -> Script.HANGUL
        in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> Script.HAN
        else -> if (ch.isLetter() && ch.code < 0x0250) Script.LATIN else null
    }

    /**
     * The script most of the letters are in.
     *
     * A Devanagari title with an English word in it is still Devanagari, so this
     * counts rather than looking at the first letter. Ties go to the non-Latin
     * one: "Kesariya (Official Video)" written in Devanagari would otherwise be
     * called Latin by the parenthesis alone.
     */
    fun dominant(text: String): Script {
        val counts = HashMap<Script, Int>()
        for (ch in text) of(ch)?.let { counts[it] = (counts[it] ?: 0) + 1 }
        if (counts.isEmpty()) return Script.OTHER
        val nonLatin = counts.filterKeys { it != Script.LATIN }
        val best = (nonLatin.ifEmpty { counts }).maxByOrNull { it.value }!!
        return best.key
    }

    /** True if any character is from a non-Latin script. */
    fun hasNonLatin(text: String): Boolean =
        text.any { of(it) != null && of(it) != Script.LATIN }
}

/**
 * The languages the app offers, and the mapping needed to make three different
 * spellings of the same language agree.
 *
 * MusicBrainz answers in ISO 639-3 (`hin`), iTunes says nothing at all, and a
 * person picking from a list wants to see "Hindi". [normalise] takes any of
 * those and returns the 639-1 code that gets written to the file.
 */
object Languages {

    data class Language(val code: String, val english: String, val native: String)

    /**
     * Indian film languages, then the rest. Songs in these come from a film, so
     * their cover art is a film poster -- see [ArtworkPlan].
     */
    val INDIAN_FILM = setOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "or", "as", "ur")

    val ALL: List<Language> = listOf(
        Language("hi", "Hindi", "हिन्दी"),
        Language("en", "English", "English"),
        Language("pa", "Punjabi", "ਪੰਜਾਬੀ"),
        Language("ta", "Tamil", "தமிழ்"),
        Language("te", "Telugu", "తెలుగు"),
        Language("ml", "Malayalam", "മലയാളം"),
        Language("kn", "Kannada", "ಕನ್ನಡ"),
        Language("bn", "Bengali", "বাংলা"),
        Language("mr", "Marathi", "मराठी"),
        Language("gu", "Gujarati", "ગુજરાતી"),
        Language("or", "Odia", "ଓଡ଼ିଆ"),
        Language("as", "Assamese", "অসমীয়া"),
        Language("ur", "Urdu", "اردو"),
        Language("ne", "Nepali", "नेपाली"),
        Language("sa", "Sanskrit", "संस्कृतम्"),
        Language("es", "Spanish", "Español"),
        Language("fr", "French", "Français"),
        Language("de", "German", "Deutsch"),
        Language("pt", "Portuguese", "Português"),
        Language("it", "Italian", "Italiano"),
        Language("nl", "Dutch", "Nederlands"),
        Language("sv", "Swedish", "Svenska"),
        Language("pl", "Polish", "Polski"),
        Language("ru", "Russian", "Русский"),
        Language("ar", "Arabic", "العربية"),
        Language("fa", "Persian", "فارسی"),
        Language("tr", "Turkish", "Türkçe"),
        Language("he", "Hebrew", "עברית"),
        Language("el", "Greek", "Ελληνικά"),
        Language("th", "Thai", "ไทย"),
        Language("id", "Indonesian", "Bahasa Indonesia"),
        Language("vi", "Vietnamese", "Tiếng Việt"),
        Language("ja", "Japanese", "日本語"),
        Language("ko", "Korean", "한국어"),
        Language("zh", "Chinese", "中文"),
    )

    private val byCode = ALL.associateBy { it.code }
    private val byEnglish = ALL.associateBy { it.english.lowercase() }

    /** ISO 639-3 (and the older bibliographic codes) to the 639-1 we store. */
    private val THREE_LETTER = mapOf(
        "hin" to "hi", "eng" to "en", "pan" to "pa", "tam" to "ta", "tel" to "te",
        "mal" to "ml", "kan" to "kn", "ben" to "bn", "mar" to "mr", "guj" to "gu",
        "ori" to "or", "ory" to "or", "asm" to "as", "urd" to "ur", "nep" to "ne",
        "san" to "sa", "spa" to "es", "fra" to "fr", "fre" to "fr", "deu" to "de",
        "ger" to "de", "por" to "pt", "ita" to "it", "nld" to "nl", "dut" to "nl",
        "swe" to "sv", "pol" to "pl", "rus" to "ru", "ara" to "ar", "fas" to "fa",
        "per" to "fa", "tur" to "tr", "heb" to "he", "ell" to "el", "gre" to "el",
        "tha" to "th", "ind" to "id", "vie" to "vi", "jpn" to "ja", "kor" to "ko",
        "zho" to "zh", "chi" to "zh",
    )

    fun byCode(code: String?): Language? = code?.let { byCode[it] }

    fun displayName(code: String?): String =
        byCode(code)?.english ?: code?.takeIf { it.isNotBlank() } ?: "Unknown"

    /** `hin`, `Hindi`, `HI`, `hi-IN` all become `hi`. Unknown input returns null. */
    fun normalise(value: String?): String? {
        val v = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val base = v.substringBefore('-').substringBefore('_')
        return when {
            byCode.containsKey(base) -> base
            THREE_LETTER.containsKey(base) -> THREE_LETTER[base]
            byEnglish.containsKey(v) -> byEnglish[v]!!.code
            else -> null
        }
    }

    /**
     * The likeliest language for a script.
     *
     * Devanagari is shared by Hindi, Marathi, Nepali and Sanskrit; Hindi is
     * returned because it is overwhelmingly the common case for film music, and
     * it stays editable in the app.
     */
    fun fromScript(script: Script): String? = when (script) {
        Script.DEVANAGARI -> "hi"
        Script.BENGALI -> "bn"
        Script.GURMUKHI -> "pa"
        Script.GUJARATI -> "gu"
        Script.ORIYA -> "or"
        Script.TAMIL -> "ta"
        Script.TELUGU -> "te"
        Script.KANNADA -> "kn"
        Script.MALAYALAM -> "ml"
        Script.ARABIC -> "ur"
        Script.CYRILLIC -> "ru"
        Script.GREEK -> "el"
        Script.HEBREW -> "he"
        Script.THAI -> "th"
        Script.KANA -> "ja"
        Script.HANGUL -> "ko"
        Script.HAN -> "zh"
        Script.LATIN, Script.OTHER -> null
    }

    /**
     * Best guess for a track, most trustworthy evidence first: what the source
     * said, then the script the title is written in, then the storefront that
     * matched it.
     *
     * A romanised Hindi title ("Kesariya", "Tum Hi Ho") is indistinguishable
     * from English by script alone, which is why the Indian storefront matching
     * is worth something: it is the only signal left in that very common case.
     */
    fun guess(
        declared: String? = null,
        title: String? = null,
        storefront: String? = null,
    ): String? {
        normalise(declared)?.let { return it }
        title?.let { t ->
            if (TextScript.hasNonLatin(t)) fromScript(TextScript.dominant(t))?.let { return it }
        }
        return when (storefront?.uppercase()) {
            "IN" -> "hi"
            "US", "GB", "AU", "CA" -> "en"
            else -> null
        }
    }
}
