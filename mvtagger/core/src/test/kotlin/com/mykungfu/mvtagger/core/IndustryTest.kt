package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndustryTest {

    @Test
    fun `the two big shelves get the names people use`() {
        assertEquals("Hollywood", Industry.label("en"))
        assertEquals("Bollywood", Industry.label("hi"))
    }

    @Test
    fun `the same answer whichever way the language was written`() {
        assertEquals("Bollywood", Industry.label("hin"))
        assertEquals("Bollywood", Industry.label("Hindi"))
        assertEquals("Hollywood", Industry.label("en-US"))
    }

    @Test
    fun `everything else is named by its language rather than a nickname`() {
        assertEquals("Tamil", Industry.label("ta"))
        assertEquals("Korean", Industry.label("ko"))
    }

    @Test
    fun `a file that does not say is not guessed at`() {
        assertEquals(Industry.UNKNOWN, Industry.label(null))
        assertEquals(Industry.UNKNOWN, Industry.label("  "))
        assertEquals(Industry.UNKNOWN, Industry.label("zzz"))
    }

    @Test
    fun `the shelves are ordered by how much of a library is on them`() {
        val shelves = listOf("Tamil", Industry.UNKNOWN, "Bollywood", "Hollywood")
            .sortedWith(compareBy({ Industry.order(it) }, { it }))
        assertEquals(listOf("Hollywood", "Bollywood", "Tamil", Industry.UNKNOWN), shelves)
    }

    @Test
    fun `an unplaceable file sorts last rather than first`() {
        assertTrue(Industry.order(Industry.UNKNOWN) > Industry.order("Tamil"))
    }
}
