package com.mykungfu.mvtagger.core

/**
 * Getting Indian song titles into a form two spellings of the same name agree on.
 *
 * This is the reason Hindi tracks were hard to match while English ones were
 * easy. An English title is spelled one way: "Hello" is "Hello" in the filename
 * and "Hello" in the catalogue, and comparing words works. A Hindi title has no
 * single spelling, because it is not really a Latin word at all -- it is a
 * transliteration, and everyone transliterates differently:
 *
 * ```
 * केसरिया   Kesariya   Kesaria    Kesarya
 * नाटु नाटु  Naatu      Natu       Nattu
 * ज़िंदगी    Zindagi    Jindagi
 * तुम ही हो  Tum Hi Ho  Tum Hii Ho
 * ```
 *
 * Comparing those as plain words gives zero overlap, so the right answer scored
 * the same as an unrelated song -- which is exactly why famous tracks were
 * missed. Two steps fix it: write Devanagari out in Latin letters, then fold
 * away the spelling choices that carry no meaning.
 *
 * The folding is deliberately one-way and lossy. It is only ever used to decide
 * whether two titles are the same; nothing folded is shown to anyone or written
 * to a file.
 */
object Transliterate {

    // --- Devanagari ---------------------------------------------------------

    /**
     * Consonants, without the inherent vowel.
     *
     * Short vowels throughout rather than `aa`/`ii`, because the output is meant
     * to read like the spelling people actually use: ब्रह्मास्त्र comes out
     * "brahmastra", which is what the catalogue calls it, not "brahmaastra".
     */
    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h", 'ळ' to "l",
        // Precomposed nukta forms, common in Urdu-influenced Hindi. Written as
        // code points because these also exist as a base letter plus a separate
        // nukta mark, and the two look identical in a source file while only one
        // of them is a single character.
        '\u0958' to "q",   // क़
        '\u0959' to "kh",  // ख़
        '\u095A' to "g",   // ग़
        '\u095B' to "z",   // ज़
        '\u095C' to "r",   // ड़
        '\u095D' to "rh",  // ढ़
        '\u095E' to "f",   // फ़
        '\u095F' to "y",   // य़
    )

    /** What a bare nukta does to the consonant before it. */
    private val NUKTA = mapOf(
        '\u0915' to "q",   // क
        '\u0916' to "kh",  // ख
        '\u0917' to "g",   // ग
        '\u091C' to "z",   // ज
        '\u0921' to "r",   // ड
        '\u0922' to "rh",  // ढ
        '\u092B' to "f",   // फ
    )

    private val INDEPENDENT_VOWELS = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u",
        'ऋ' to "ri", 'ॠ' to "ri", 'ऎ' to "e", 'ए' to "e", 'ऐ' to "ai",
        'ऒ' to "o", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )

    /** Vowel signs, which replace a consonant's inherent vowel. */
    private val MATRAS = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u",
        'ृ' to "ri", 'ॄ' to "ri", 'ॅ' to "e", 'ॆ' to "e", 'े' to "e",
        'ै' to "ai", 'ॉ' to "o", 'ॊ' to "o", 'ो' to "o", 'ौ' to "au",
    )

    private const val VIRAMA = '्'
    private const val NUKTA_SIGN = '़'
    private const val ANUSVARA = 'ं'
    private const val CHANDRABINDU = 'ँ'
    private const val VISARGA = 'ः'

    /** True if there is any Devanagari here worth converting. */
    fun hasDevanagari(text: String): Boolean =
        text.any { TextScript.of(it) == Script.DEVANAGARI }

    /**
     * Devanagari written out in Latin letters.
     *
     * Anything that is not Devanagari passes through untouched, so a title with
     * an English word in it survives intact.
     */
    fun devanagari(text: String): String {
        val out = StringBuilder(text.length * 2)
        // Where an inherent vowel was supplied rather than written. Only those
        // are candidates for the schwa deletion below; a vowel the writer
        // actually put there is never dropped.
        val inherent = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]

            val consonant = CONSONANTS[ch]
            if (consonant != null) {
                var base = consonant
                i++
                // A nukta written separately rather than precomposed.
                if (i < text.length && text[i] == NUKTA_SIGN) {
                    NUKTA[ch]?.let { base = it }
                    i++
                }
                out.append(base)
                when {
                    // A virama silences the inherent vowel: it is what joins
                    // consonants into a cluster, as in "brahmastra".
                    i < text.length && text[i] == VIRAMA -> i++
                    i < text.length && MATRAS.containsKey(text[i]) -> {
                        out.append(MATRAS[text[i]])
                        i++
                    }
                    else -> {
                        inherent += out.length
                        out.append('a')
                    }
                }
                continue
            }

            val vowel = INDEPENDENT_VOWELS[ch]
            if (vowel != null) {
                out.append(vowel)
                i++
                continue
            }

            when {
                ch == ANUSVARA || ch == CHANDRABINDU -> out.append('n')
                ch == VISARGA -> out.append('h')
                ch in '०'..'९' -> out.append(('0' + (ch - '०')))
                // Standalone matras, viramas and the avagraha carry nothing on
                // their own; dropping them is better than emitting noise.
                MATRAS.containsKey(ch) || ch == VIRAMA || ch == NUKTA_SIGN || ch == 'ऽ' -> Unit
                else -> out.append(ch)
            }
            i++
        }
        return deleteFinalSchwa(out.toString(), inherent)
    }

    /**
     * Drops the inherent vowel at the end of a word, which Hindi does not
     * pronounce.
     *
     * तुम is "tum", not "tuma"; दिलबर is "dilbar". Writing the vowel out leaves
     * a word that matches nothing, and short words like "tum" are too short for
     * the fuzzy comparison to rescue.
     *
     * Only the word-final schwa is dropped. The medial one -- the "a" that also
     * disappears from the middle of दिलबर -- follows a rule with enough
     * exceptions that applying it naively turns केसरिया into "kesriya", so it is
     * left in and [sameWord] absorbs the difference instead.
     */
    private fun deleteFinalSchwa(text: String, inherent: List<Int>): String {
        if (inherent.isEmpty()) return text
        val chars = text.toCharArray()
        val drop = BooleanArray(chars.size)

        for (at in inherent) {
            val wordFinal = at == chars.lastIndex || !chars[at + 1].isLetter()
            if (!wordFinal) continue

            // Keep it when the word has no other vowel, so a bare consonant
            // does not become unpronounceable: ज stays "ja", not "j".
            var i = at - 1
            var letters = 0
            var hasVowel = false
            while (i >= 0 && chars[i].isLetter()) {
                letters++
                if (chars[i] in "aeiou") hasVowel = true
                i--
            }
            if (hasVowel && letters >= 2) drop[at] = true
        }

        val out = StringBuilder(chars.size)
        for ((index, ch) in chars.withIndex()) if (!drop[index]) out.append(ch)
        return out.toString()
    }

    // --- spelling folding ---------------------------------------------------

    /**
     * Collapses the spelling choices that carry no meaning.
     *
     * Every rule here is a pair people genuinely use for the same sound:
     * doubling a vowel for length (`Naatu`/`Natu`), `z` where Devanagari has a
     * nukta and others write `j` (`Zindagi`/`Jindagi`), `w` for `v`
     * (`Wo`/`Vo`), `ph` for `f` (`Phir`/`Fir`).
     *
     * Deliberately conservative. Folding `kh` to `k` would make "Khan" and
     * "Kaan" the same word, which is a different name -- so aspirated
     * consonants are left alone even though they are also spelled variously.
     */
    fun latinFold(text: String): String {
        var s = text.lowercase()
        // Long vowels first, before doubles collapse, so "ee" becomes "i"
        // rather than "e".
        s = s.replace("aa", "a").replace("ee", "i").replace("ii", "i")
            .replace("oo", "u").replace("uu", "u")
        s = s.replace("ph", "f").replace("z", "j").replace("w", "v")
            .replace("q", "k").replace("x", "ks")
        // Any remaining doubled letter: "Nattu" and "Natu" are one word.
        val out = StringBuilder(s.length)
        for (ch in s) if (out.isEmpty() || out.last() != ch) out.append(ch)
        return out.toString()
    }

    /** Devanagari written out, then folded. The form used for comparing. */
    fun fold(text: String): String =
        latinFold(if (hasDevanagari(text)) devanagari(text) else text)

    // --- fuzzy comparison ---------------------------------------------------

    /**
     * Levenshtein distance, giving up once it passes [limit].
     *
     * Folding cannot catch everything: "Kesaria" and "Kesariya" differ by an
     * inserted letter and no rule will ever unify them. One edit of slack does,
     * and the length floors below keep it from making short words equal.
     */
    fun editDistance(a: String, b: String, limit: Int): Int {
        if (a == b) return 0
        if (Math.abs(a.length - b.length) > limit) return limit + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var best = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost,
                )
                best = minOf(best, current[j])
            }
            if (best > limit) return limit + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * Whether two already-folded words are the same word.
     *
     * The length floors matter: "tera" and "mera" are one edit apart and are
     * different words, so slack is only given to words long enough that a
     * single difference is far more likely to be a spelling choice than a
     * different word.
     */
    fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true

        // A trailing "a" is optional: Hindi drops it in speech and
        // transliterations disagree about whether to write it. "brahmastr" and
        // "brahmastra" are one word, and so are "tum" and "tuma". Stripping it
        // from both sides keeps this symmetric.
        val ta = a.trimEnd('a')
        val tb = b.trimEnd('a')
        if (ta == tb && ta.length >= 2) return true

        val shortest = minOf(a.length, b.length)
        return when {
            shortest >= 9 -> editDistance(a, b, 2) <= 2
            shortest >= 6 -> editDistance(a, b, 1) <= 1
            else -> false
        }
    }
}
