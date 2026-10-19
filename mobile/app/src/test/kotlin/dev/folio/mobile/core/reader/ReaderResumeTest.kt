package dev.folio.mobile.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the within-chapter resume math (audit EPUB-010 partial). */
class ReaderResumeTest {

    @Test
    fun `mid-chapter percent yields the fraction 0_5`() {
        assertEquals(
            0.5,
            ReaderResume.inChapterFraction(
                percentTotal = 0.5, startTotal = 0.4, nextStartTotal = 0.6
            )!!,
            1e-12
        )
    }

    @Test
    fun `chapter start percent clamps to 0`() {
        assertEquals(
            0.0,
            ReaderResume.inChapterFraction(
                percentTotal = 0.4, startTotal = 0.4, nextStartTotal = 0.6
            )!!,
            1e-12
        )
        // Percent drift BELOW the chapter start (stale progress) clamps too.
        assertEquals(
            0.0,
            ReaderResume.inChapterFraction(
                percentTotal = 0.3, startTotal = 0.4, nextStartTotal = 0.6
            )!!,
            1e-12
        )
    }

    @Test
    fun `percent at or beyond the next chapter clamps to 1`() {
        assertEquals(
            1.0,
            ReaderResume.inChapterFraction(
                percentTotal = 0.6, startTotal = 0.4, nextStartTotal = 0.6
            )!!,
            1e-12
        )
        assertEquals(
            1.0,
            ReaderResume.inChapterFraction(
                percentTotal = 0.9, startTotal = 0.4, nextStartTotal = 0.6
            )!!,
            1e-12
        )
    }

    @Test
    fun `degenerate spans degrade to chapter-start`() {
        // Zero width (single-position chapter spanning [0.5, 0.5)).
        assertNull(
            ReaderResume.inChapterFraction(0.5, startTotal = 0.5, nextStartTotal = 0.5)
        )
        // Inverted span.
        assertNull(
            ReaderResume.inChapterFraction(0.5, startTotal = 0.6, nextStartTotal = 0.4)
        )
        // Non-finite inputs.
        assertNull(
            ReaderResume.inChapterFraction(
                percentTotal = Double.NaN, startTotal = 0.4, nextStartTotal = 0.6
            )
        )
        assertNull(
            ReaderResume.inChapterFraction(
                percentTotal = 0.5,
                startTotal = Double.NEGATIVE_INFINITY,
                nextStartTotal = 0.6
            )
        )
    }

    @Test
    fun `a narrow but usable span still interpolates`() {
        assertEquals(
            0.5,
            ReaderResume.inChapterFraction(
                percentTotal = 0.5, startTotal = 0.49, nextStartTotal = 0.51
            )!!,
            1e-12
        )
    }
}
