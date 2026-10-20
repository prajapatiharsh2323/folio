package dev.folio.mobile.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFallbackTest {

    @Test
    fun `same seed always picks the same slot`() {
        for (seed in listOf(0, 1, 42, -7, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(CoverFallback.indexFor(seed, 5), CoverFallback.indexFor(seed, 5))
        }
    }

    @Test
    fun `slot is always within range including negative seeds`() {
        val seeds = listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -1, 0, 1, Int.MAX_VALUE)
        for (count in 1..7) {
            for (seed in seeds) {
                val index = CoverFallback.indexFor(seed, count)
                assertTrue("index $index for seed $seed / count $count", index in 0 until count)
            }
        }
    }

    @Test
    fun `all bundled slots are reachable by title seeds`() {
        // Any 5 consecutive seeds map to 5 distinct slots (masked seeds are
        // sequential), so a small shelf cannot get stuck on one illustration.
        val slots = (0 until 5).map { CoverFallback.indexFor(it, 5) }.toSet()
        assertEquals(setOf(0, 1, 2, 3, 4), slots)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero count is rejected`() {
        CoverFallback.indexFor(1, 0)
    }
}
