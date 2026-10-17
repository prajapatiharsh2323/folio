package dev.folio.mobile.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverLoaderTest {

    @Test
    fun `sample size halves until both dims cover the request`() {
        // 4000x3000 → halves to 250x187 at 16 (next halving breaks the height)
        assertEquals(16, CoverLoader.sampleSize(4000, 3000, 132, 174))
        // 1000x1000 → 125x125 at 8 (next halving breaks both)
        assertEquals(8, CoverLoader.sampleSize(1000, 1000, 100, 100))
        // exact edge: 600x400 at 2 → 300x200, height still exactly enough
        assertEquals(2, CoverLoader.sampleSize(600, 400, 250, 200))
    }

    @Test
    fun `sample size never upscales`() {
        assertEquals(1, CoverLoader.sampleSize(100, 100, 132, 174))
        assertEquals(1, CoverLoader.sampleSize(512, 512, 500, 500))
        assertEquals(1, CoverLoader.sampleSize(132, 174, 132, 174))
    }

    @Test
    fun `non-positive inputs fall back to 1`() {
        assertEquals(1, CoverLoader.sampleSize(0, 100, 50, 50))
        assertEquals(1, CoverLoader.sampleSize(100, 0, 50, 50))
        assertEquals(1, CoverLoader.sampleSize(100, 100, 0, 50))
        assertEquals(1, CoverLoader.sampleSize(-10, 100, 50, 50))
    }
}
