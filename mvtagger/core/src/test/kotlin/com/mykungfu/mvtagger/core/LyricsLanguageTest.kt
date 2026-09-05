package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsLanguageTest {

    /** Romanised Hindi, which is how lyrics sites usually carry these. */
    private val romanisedHindi = """
        Tum hi ho, ab tum hi ho
        Zindagi ab tum hi ho
        Chain bhi mera dard bhi
        Meri aashiqui ab tum hi ho
        Kyun khoya khoya chand ki raat mein
        Tere bina main kuch bhi nahi
        Mera dil mera jaan sab kuch tu hi hai
        Har din har raat bas teri hi baat
    """.trimIndent()

    private val english = """
        When I count the cars that pass me by
        And I know that you were the only one
        There is nothing that I would not give
        But the time we had is gone away
        And you never really knew what I meant
        So take my hand and come back here again
        All the things I could not say before
    """.trimIndent()

    @Test
    fun `romanised Hindi is recognised without any Devanagari`() {
        assertEquals("hi", LyricsLanguage.detect(romanisedHindi))
    }

    @Test
    fun `English lyrics are recognised`() {
        assertEquals("en", LyricsLanguage.detect(english))
    }

    @Test
    fun `Devanagari is settled by script alone`() {
        val text = "तुम ही हो अब तुम ही हो ज़िंदगी अब तुम ही हो चैन भी मेरा दर्द भी"
        assertEquals("hi", LyricsLanguage.detect(text))
    }

    @Test
    fun `too little text is not guessed at`() {
        assertNull(LyricsLanguage.detect("Tum hi ho"))
        assertNull(LyricsLanguage.detect(""))
        assertNull(LyricsLanguage.detect(null))
    }

    /**
     * The case the margin exists for. Hindi songs borrow English words freely,
     * so an English word here and there must not flip the answer.
     */
    @Test
    fun `English words inside a Hindi song do not flip it`() {
        val mixed = romanisedHindi + "\nBaby tonight, love you baby, come on now"
        assertEquals("hi", LyricsLanguage.detect(mixed))
    }

    @Test
    fun `lrc timing stamps and headers are ignored`() {
        val lrc = romanisedHindi.lines()
            .mapIndexed { i, line -> "[00:" + (10 + i) + ".00]" + line }
            .joinToString("\n", prefix = "[ar: Arijit Singh]\n[ti: Tum Hi Ho]\n")
        assertEquals("hi", LyricsLanguage.detect(lrc))
    }

    /** Neither side clearly ahead is a "do not know", not a coin toss. */
    @Test
    fun `an even mix is left unknown`() {
        // Five markers a side. Note the trailing space: without it the repeats
        // run together and invent a word that is neither.
        val even = "hai tum tera dil mera the and you that with ".repeat(5)
        assertNull(LyricsLanguage.detect(even))
    }

    @Test
    fun `instrumental or nonsense text says nothing`() {
        assertNull(LyricsLanguage.detect("la la la ".repeat(40)))
    }
}
