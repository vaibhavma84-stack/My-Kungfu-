package com.mykungfu.mvtagger.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision in this app that cannot be taken back.
 *
 * Everything else it does is a new file, a tag that can be rewritten, a name
 * that can be changed. A deleted video is gone, and if the copy that replaced
 * it was half-written then so is the recording.
 *
 * This lived inside the file-writing code, tangled up with opening documents
 * and querying providers, which is the shape of thing this sandbox cannot run
 * -- so nothing tested it. Every refusal below is a way the original used to be
 * deleted on nothing but an absence of bad news.
 */
class SafeToDeleteTest {

    private fun written(
        size: Long? = 1_000_000,
        originalSize: Long? = 1_000_000,
        embedded: Boolean = true,
        wantedTitle: String? = "Kesariya",
        titleReadBack: String? = "Kesariya",
        isDifferentFile: Boolean = true,
    ) = SafeToDelete.Written(
        size, originalSize, embedded, wantedTitle, titleReadBack, isDifferentFile,
    )

    private fun refused(w: SafeToDelete.Written): String {
        val verdict = SafeToDelete.check(w)
        assertFalse("it agreed to delete", verdict.ok)
        assertNotNull("refused without saying why", verdict.why)
        return verdict.why!!
    }

    @Test
    fun `a copy that is there, whole, and reads back is safe`() {
        assertTrue(SafeToDelete.check(written()).ok)
    }

    // --- not knowing is not consent -----------------------------------------

    @Test
    fun `a size nobody could read is a refusal`() {
        assertTrue(refused(written(size = null)).contains("could not be read"))
    }

    @Test
    fun `an empty copy is a refusal`() {
        assertTrue(refused(written(size = 0)).contains("empty"))
    }

    @Test
    fun `a title that could not be read back is a refusal`() {
        assertTrue(refused(written(titleReadBack = null)).contains("could not be read back"))
    }

    @Test
    fun `a title that came back as something else is a refusal`() {
        val why = refused(written(titleReadBack = "Something Else"))
        assertTrue(why, why.contains("Something Else"))
    }

    /**
     * The case that reads as a pass and is not one: nothing was asked for, so
     * there is nothing to compare -- but the file was still rewritten, and an
     * unopenable file is exactly what this check exists to catch.
     */
    @Test
    fun `no title asked for still needs the file to open`() {
        assertTrue(SafeToDelete.check(written(wantedTitle = null, titleReadBack = "")).ok)
        assertTrue(
            refused(written(wantedTitle = null, titleReadBack = null))
                .contains("could not be read back"),
        )
    }

    @Test
    fun `a blank title asked for is the same as none`() {
        assertTrue(SafeToDelete.check(written(wantedTitle = "   ", titleReadBack = "")).ok)
        assertFalse(SafeToDelete.check(written(wantedTitle = "  ", titleReadBack = null)).ok)
    }

    // --- half-written copies -------------------------------------------------

    @Test
    fun `a copy a fraction of the original's size is a refusal`() {
        val why = refused(written(size = 400_000, originalSize = 1_000_000))
        assertTrue(why, why.contains("too small"))
    }

    @Test
    fun `some shrinkage is expected and allowed`() {
        // Repackaging drops subtitle and attachment tracks, so a smaller file
        // is normal. Half is the line.
        assertTrue(SafeToDelete.check(written(size = 600_000, originalSize = 1_000_000)).ok)
        assertTrue(SafeToDelete.check(written(size = 500_000, originalSize = 1_000_000)).ok)
        assertFalse(SafeToDelete.check(written(size = 499_999, originalSize = 1_000_000)).ok)
    }

    @Test
    fun `a bigger copy is fine, because tags and a cover add bytes`() {
        assertTrue(SafeToDelete.check(written(size = 1_200_000, originalSize = 1_000_000)).ok)
    }

    @Test
    fun `an original whose size is unknown does not block a copy that is otherwise good`() {
        // Not knowing the original tells us nothing about the copy, and the
        // copy has already been checked on its own terms.
        assertTrue(SafeToDelete.check(written(originalSize = null)).ok)
        assertTrue(SafeToDelete.check(written(originalSize = 0)).ok)
    }

    // --- tags written beside the file ---------------------------------------

    @Test
    fun `a container that cannot hold tags is judged on its size alone`() {
        // Nothing was written into the video, so there is nothing to read back
        // and its absence proves nothing.
        assertTrue(SafeToDelete.check(written(embedded = false, titleReadBack = null)).ok)
    }

    @Test
    fun `but such a file still has to be a plausible size`() {
        assertFalse(
            SafeToDelete.check(
                written(embedded = false, titleReadBack = null, size = 10, originalSize = 1_000_000)
            ).ok,
        )
        assertFalse(SafeToDelete.check(written(embedded = false, size = null)).ok)
    }

    // --- the copy that is not a copy ----------------------------------------

    @Test
    fun `saving over the original is never a reason to delete it`() {
        val why = refused(written(isDifferentFile = false))
        assertTrue(why, why.contains("nothing to delete"))
    }

    @Test
    fun `and that is checked before anything else, so it cannot be argued round`() {
        assertFalse(
            SafeToDelete.check(
                written(isDifferentFile = false, size = 9_000_000, originalSize = 1)
            ).ok,
        )
    }

    // --- every refusal says why ---------------------------------------------

    @Test
    fun `no refusal is ever silent`() {
        val ways = listOf(
            written(size = null),
            written(size = 0),
            written(titleReadBack = null),
            written(titleReadBack = "Wrong"),
            written(size = 1, originalSize = 1_000_000),
            written(isDifferentFile = false),
            written(wantedTitle = null, titleReadBack = null),
        )
        for (way in ways) {
            val verdict = SafeToDelete.check(way)
            assertFalse(verdict.ok)
            assertTrue("a refusal with no reason", !verdict.why.isNullOrBlank())
        }
    }
}
