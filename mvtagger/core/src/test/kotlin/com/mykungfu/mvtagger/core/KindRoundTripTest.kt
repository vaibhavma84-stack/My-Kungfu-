package com.mykungfu.mvtagger.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three kinds with no catalogue behind them have to survive being written
 * and read again, which is not a given: Apple's `stik` has no value for a
 * workout, so a fitness video carries a film's number and would come back as a
 * film if that number were the only thing consulted.
 */
class KindRoundTripTest {

    @Test
    fun `every kind comes back as itself`() {
        val plain = TestMp4.build().bytes
        for (kind in MediaKind.values()) {
            val tagged = TestMp4.writeToBytes(
                plain,
                VideoTags(mediaKind = kind, title = "Something", showName = "A course"),
            )
            assertEquals(kind.name, kind, TestMp4.readTags(tagged).mediaKind)
        }
    }
}
