package dev.folio.mobile.core.reader

import dev.folio.mobile.core.model.ProgressPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the PWA's `packages/core/tests/reading-stats.test.ts` golden cases —
 * the same scenarios must pass in both suites (schema-1 pace contract).
 */
class ReadingPaceStatsTest {

    private val default = ProgressPace()

    @Test
    fun `returns a fresh pace when none exists yet`() {
        val pace = ReadingPaceStats.updatePace(null, 60_000, 1.2)
        assertEquals(60_000L, pace.sessionsMs)
        assertEquals(1.2, pace.percentRead, 0.00001)
    }

    @Test
    fun `accumulates session time and percent read across sessions`() {
        var pace: ProgressPace = ProgressPace(sessionsMs = 30_000, percentRead = 1.5)
        pace = ReadingPaceStats.updatePace(pace, 45_000, 0.9)
        assertEquals(75_000L, pace.sessionsMs)
        assertEquals(2.4, pace.percentRead, 0.00001)
    }

    @Test
    fun `never records negative session durations`() {
        val pace = ReadingPaceStats.updatePace(
            ProgressPace(sessionsMs = 10_000, percentRead = 5.0), -5_000, 1.0
        )
        assertEquals(10_000L, pace.sessionsMs)
    }

    @Test
    fun `credits nothing for navigation jumps (5 percent threshold)`() {
        val pace = ReadingPaceStats.updatePace(null, 120_000, 95.0)
        assertEquals(120_000L, pace.sessionsMs)
        assertEquals(0.0, pace.percentRead, 0.0)

        val jump = ReadingPaceStats.updatePace(
            ProgressPace(sessionsMs = 10_000, percentRead = 2.0), 10_000, 30.0
        )
        assertEquals(20_000L, jump.sessionsMs)
        assertEquals(2.0, jump.percentRead, 0.00001)
    }

    @Test
    fun `is immune to the scrub left-right inflation loop`() {
        var pace: ProgressPace = ProgressPace(sessionsMs = 10_000, percentRead = 1.0)
        // A user yo-yos the scrubber: forward 30% > threshold (no credit), lands at 1.5%
        repeat(20) {
            pace = ReadingPaceStats.updatePace(pace, 1_000, 30.0)
            pace = ReadingPaceStats.updatePace(pace, 1_000, -0.5) // back-scroll: no credit
        }
        assertEquals(1.0, pace.percentRead, 0.00001)
        assertEquals(50_000L, pace.sessionsMs)
    }

    @Test
    fun `estimate returns null when there is no usable pace data yet`() {
        assertNull(ReadingPaceStats.estimateTimeLeftSeconds(null, 40.0))
        assertNull(
            ReadingPaceStats.estimateTimeLeftSeconds(
                ProgressPace(sessionsMs = 50_000, percentRead = 0.0), 40.0
            )
        )
    }

    @Test
    fun `extrapolates from observed pace one min per percent`() {
        val pace = ProgressPace(sessionsMs = 120_000, percentRead = 2.0)
        assertEquals(40 * 60L, ReadingPaceStats.estimateTimeLeftSeconds(pace, 40.0))
    }

    @Test
    fun `returns zero seconds when the book is finished`() {
        val pace = ProgressPace(sessionsMs = 120_000, percentRead = 2.0)
        assertEquals(0L, ReadingPaceStats.estimateTimeLeftSeconds(pace, 0.0))
    }

    @Test
    fun `clamps absurd remaining percents above 100`() {
        val pace = ProgressPace(sessionsMs = 60_000, percentRead = 2.0)
        val at100 = ReadingPaceStats.estimateTimeLeftSeconds(pace, 100.0)
        assertEquals(at100, ReadingPaceStats.estimateTimeLeftSeconds(pace, 900.0))
        assertEquals(50 * 60L, ReadingPaceStats.estimateTimeLeftSeconds(pace, 900.0))
    }

    @Test
    fun `renders human strings done under a minute minutes hours`() {
        assertNull(ReadingPaceStats.formatTimeLeft(null))
        assertEquals("done", ReadingPaceStats.formatTimeLeft(0))
        assertEquals("under a minute", ReadingPaceStats.formatTimeLeft(45))
        assertEquals("2 min left", ReadingPaceStats.formatTimeLeft(2 * 60))
        assertEquals("1 hr left", ReadingPaceStats.formatTimeLeft(5400))
        assertEquals("3 hr left", ReadingPaceStats.formatTimeLeft((3.2 * 3600).toLong()))
        assertTrue(ReadingPaceStats.READING_CREDIT_JUMP_THRESHOLD_PERCENT == 5.0)
    }
}
