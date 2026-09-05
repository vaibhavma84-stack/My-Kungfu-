package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The case these rules exist for, taken from a real file.
 *
 * "House of the Dragon The House That Dragons Built - S03E08" matched the
 * aftershow of that name: right episode number, right show for the words in
 * the filename, twenty-two minutes against the file's seventy. It scored 95%,
 * which is confident enough to apply without asking, and it was wrong.
 */
class EpisodeRankingTest {

    private fun episode(show: String, minutes: Int?) = Candidate(
        source = "TVmaze",
        id = show + minutes,
        title = "The Treasons at Tumbleton",
        showName = show,
        season = 3,
        episode = 8,
        mediaKind = MediaKind.TV_EPISODE,
        durationMs = minutes?.let { it * 60_000 },
    )

    private val fileRuns = 70 * 60_000 + 6_000

    @Test
    fun `an episode a third of the length is not that episode`() {
        val scored = Matching.rankEpisodes(
            listOf(episode("House of the Dragon: The House That Dragons Built", 22)),
            fileRuns,
        ).single()
        assertTrue(
            "scored " + scored.score + ", which would still apply on its own",
            scored.score < 0.80,
        )
        assertTrue(scored.reasons.toString(), scored.reasons.any { it.contains("different series") })
    }

    @Test
    fun `the right length keeps its confidence`() {
        val scored = Matching.rankEpisodes(listOf(episode("House of the Dragon", 68)), fileRuns)
            .single()
        assertTrue("scored " + scored.score, scored.score >= 0.80)
        assertTrue(scored.reasons.toString(), scored.reasons.contains("length agrees"))
    }

    @Test
    fun `the real episode is offered ahead of the aftershow`() {
        val ranked = Matching.rankEpisodes(
            listOf(
                episode("House of the Dragon: The House That Dragons Built", 22),
                episode("House of the Dragon", 68),
            ),
            fileRuns,
        )
        assertEquals("House of the Dragon", ranked.first().candidate.showName)
    }

    /** An unknown length is not evidence against; the number still stands. */
    @Test
    fun `no length either side leaves the number to speak for itself`() {
        assertTrue(
            Matching.rankEpisodes(listOf(episode("Westworld", null)), fileRuns)
                .single().score >= 0.80,
        )
        assertTrue(
            Matching.rankEpisodes(listOf(episode("Westworld", 60)), null)
                .single().score >= 0.80,
        )
    }

    /** Close but not exact -- an ad break, a recap -- is still the same episode. */
    @Test
    fun `a few minutes out is still a match`() {
        val scored = Matching.rankEpisodes(listOf(episode("House of the Dragon", 63)), fileRuns)
            .single()
        assertTrue("scored " + scored.score, scored.score >= 0.80)
    }
}
