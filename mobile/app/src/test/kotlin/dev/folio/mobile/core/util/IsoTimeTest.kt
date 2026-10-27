package dev.folio.mobile.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IsoTimeTest {

    @Test
    fun `format always emits millisecond precision and a single trailing Z`() {
        // Regression: the old take(24)+"Z" helper produced "...123ZZ" whenever
        // the clock reported millisecond precision (Instant.toString() is
        // already 24 chars ending in Z on those platforms).
        assertEquals("1970-01-01T00:00:00.000Z", IsoTime.format(Instant.EPOCH))
        assertEquals(
            "2026-10-26T18:25:33.947Z",
            IsoTime.format(Instant.ofEpochMilli(1_793_039_133_947L))
        )
        assertTrue(!IsoTime.format(Instant.EPOCH).endsWith("ZZ"))
    }

    @Test
    fun `formatted timestamps parse back to the same instant`() {
        val instant = Instant.ofEpochMilli(1_793_039_133_947L)
        assertEquals(instant, Instant.parse(IsoTime.format(instant)))
        assertEquals(1_793_039_133_947L, IsoTime.epochMs(IsoTime.format(instant)))
    }

    @Test
    fun `now is a valid schema timestamp`() {
        val now = IsoTime.now()
        assertTrue("not ISO: $now", Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""").matches(now))
        Instant.parse(now)
    }

    @Test
    fun `legacy double-Z timestamps still compare instead of collapsing to zero`() {
        assertEquals(1_793_039_133_947L, IsoTime.epochMs("2026-10-26T18:25:33.947ZZ"))
        assertEquals(
            Instant.parse("2026-10-26T18:25:33.947Z"),
            IsoTime.parseOrNull("2026-10-26T18:25:33.947ZZ")
        )
    }

    @Test
    fun `absent or malformed timestamps stay inert`() {
        assertEquals(0L, IsoTime.epochMs(null))
        assertEquals(0L, IsoTime.epochMs(""))
        assertEquals(0L, IsoTime.epochMs("not-a-date"))
        assertNull(IsoTime.parseOrNull("not-a-date"))
    }
}
