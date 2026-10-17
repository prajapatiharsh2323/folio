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
}
