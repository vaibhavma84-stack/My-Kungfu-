package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cover the reason Hindi tracks were missed while English ones matched
 * first time. Before this, every case in [titles_that_are_the_same_song] scored
 * zero -- the right answer ranked level with an unrelated song.
 */
class TransliterateTest {

    @Test
    fun `devanagari is written out the way the catalogue spells it`() {
        assertEquals("kesariya", Transliterate.devanagari("केसरिया"))
        assertEquals("tum hi ho", Transliterate.devanagari("तुम ही हो"))
        assertEquals("zindagi", Transliterate.devanagari("ज़िंदगी"))
        assertEquals("ashiki", Transliterate.devanagari("आशिकी"))
    }

    @Test
    fun `the unpronounced final vowel is dropped`() {
        // तुम is "tum", not "tuma" -- and "tuma" is too short for the fuzzy
        // comparison to rescue, so it would simply never match.
        assertEquals("tum", Transliterate.devanagari("तुम"))
        assertEquals("dilabar", Transliterate.devanagari("दिलबर"))
    }

    @Test
    fun `a lone syllable keeps its vowel rather than becoming unpronounceable`() {
        assertEquals("ja", Transliterate.devanagari("ज"))
    }

    @Test
    fun `latin passes through untouched`() {
        assertEquals("Hello Adele", Transliterate.devanagari("Hello Adele"))
    }

    @Test
    fun titles_that_are_the_same_song() {
        val same = listOf(
            "केसरिया" to "Kesariya",
            "केसरिया" to "Kesariya (From \"Brahmastra\")",
            "Naatu Naatu" to "Natu Natu",
            "Tum Hii Ho" to "Tum Hi Ho",
            "तुम ही हो" to "Tum Hi Ho",
            "Zindagi Na Milegi" to "Jindagi Na Milegi",
            "Kesaria" to "Kesariya",
            "ब्रह्मास्त्र" to "Brahmastra",
            "Phir Le Aaya Dil" to "Fir Le Aya Dil",
        )
        for ((a, b) in same) {
            val score = Matching.tokenOverlap(a, b)
            assertEquals("'$a' and '$b' are the same song", 1.0, score, 0.001)
        }
    }

    @Test
    fun `different songs stay different`() {
        val different = listOf(
            "Kesariya" to "Malhari",
            "Tum Hi Ho" to "Channa Mereya",
            "Hello" to "Goodbye",
        )
        for ((a, b) in different) {
            assertEquals(
                "'$a' and '$b' are not the same song",
                0.0, Matching.tokenOverlap(a, b), 0.001,
            )
        }
    }

    @Test
    fun `short words are not merged by the fuzzy comparison`() {
        // One edit apart and genuinely different words. Slack is only given to
        // words long enough that a single difference is unlikely to be meaning.
        assertTrue(!Transliterate.sameWord("tera", "mera"))
        assertTrue(!Transliterate.sameWord("din", "din".replace('d', 'b')))
        assertTrue(Transliterate.sameWord("kesariya", "kesariy"))
    }

    @Test
    fun `a devanagari filename is searched for in latin`() {
        val p = FilenameParser.parse("अरिजीत सिंह - केसरिया.mp4")
        assertTrue(
            "expected a Latin query, got " + p.queries,
            p.queries.any { it.contains("kesariya") },
        )
    }
}
