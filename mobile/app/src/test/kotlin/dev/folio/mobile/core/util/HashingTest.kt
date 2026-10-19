package dev.folio.mobile.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HashingTest {

    @Test
    fun `sha256 known vector`() {
        // echo -n "abc" | sha256sum
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun `slug format matches PWA rules`() {
        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        calendar.clear()
        calendar.set(2026, 9, 17) // Oct 17 2026
        val slug = createDocSlug("Hello, World! — A Great Title", calendar.time)
        assertTrue(
            "slug must match YYYY-MM-DD-title-4hex: $slug",
            Regex("^2026-10-17-hello-world-a-great-title-[0-9a-f]{4}$").matches(slug)
        )
        assertTrue(slug.length <= 60)
    }

    @Test
    fun `slug caps title and strips trailing dash`() {
        val longTitle = "word ".repeat(30) // 150 chars
        val slug = createDocSlug(longTitle, Date())
        assertTrue(slug.length <= 60)
        assertTrue(
            "no trailing dash before hex suffix",
            Regex("-[0-9a-f]{4}$").containsMatchIn(slug) && !slug.contains("--")
        )
    }

    @Test
    fun `slug falls back to doc for hindi titles`() {
        val slug = createDocSlug("विद्यार्थी", Date())
        assertTrue(
            "hindi-only title must fall back to 'doc': $slug",
            Regex("^\\d{4}-\\d{2}-\\d{2}-doc-[0-9a-f]{4}$").matches(slug)
        )
    }

    @Test
    fun `slug strips latin diacritics`() {
        val slug = createDocSlug("Ángel García", Date())
        assertTrue(slug.contains("angel-garcia"))
        assertTrue(!slug.contains("á"))
    }

    /**
     * Audit EPUB-014: createDocSlug migrated away from the deprecated
     * java.util.Date getters (year/month/date) to ZonedDateTime. The getters
     * wrap Calendar.getInstance over the system-default zone, which is exactly
     * what atZone(ZoneId.systemDefault()) yields — so the output must be
     * byte-stable. Pinned here over a corpus of edge instants (epoch, leap
     * day, old/new-year rollovers) so any future migration of the slug date
     * semantics is a conscious cross-repo decision, not an accident.
     */
    @Test
    fun `zoned fields equal deprecated date getters for every instant`() {
        val millis = listOf(
            0L,
            1L,
            1709164800000L, // 2024-02-29T00:00Z
            1709251199999L, // 2024-02-29T23:59:59.999Z
            1767225599999L, // 2025-12-31T23:59:59.999Z
            1767225600000L, // 2026-01-01T00:00:00Z
            1735689600000L, // 2025-01-01T00:00:00Z
            2000000000000L,
            System.currentTimeMillis()
        )
        for (ms in millis) {
            val date = Date(ms)
            val zoned = date.toInstant().atZone(java.time.ZoneId.systemDefault())
            assertEquals("year@$ms", date.year + 1900, zoned.year)
            assertEquals("month@$ms", date.month + 1, zoned.monthValue)
            assertEquals("day@$ms", date.date, zoned.dayOfMonth)
        }
    }

    @Test
    fun `slug date prefix equals deprecated-getter semantics`() {
        for (ms in listOf(0L, 1709164800000L, 1767225600000L)) {
            val date = Date(ms)
            val zoned = date.toInstant().atZone(java.time.ZoneId.systemDefault())
            val expectedPrefix = String.format(
                Locale.ROOT, "%04d-%02d-%02d",
                zoned.year, zoned.monthValue, zoned.dayOfMonth
            )
            val slug = createDocSlug("Stability Check", date)
            assertTrue(
                "slug date prefix must stay stable: $slug",
                slug.startsWith("$expectedPrefix-stability-check-")
            )
        }
    }
}
