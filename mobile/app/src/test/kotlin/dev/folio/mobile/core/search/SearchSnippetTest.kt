package dev.folio.mobile.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports the buildSnippet golden cases of packages/core/tests/search.test.ts. */
class SearchSnippetTest {

    @Test
    fun `html entities escaped - no injection`() {
        val malicious = "Hello <script>alert('xss')</script> and <img src=x onerror=alert(1)> in text."
        val snippet = SearchSnippet.build(malicious, listOf("script", "alert"))

        assertFalse(snippet.contains("<script>"))
        assertFalse(snippet.contains("<img src="))
        assertTrue(snippet.contains("&lt;<mark>script</mark>&gt;"))
        assertTrue(snippet.contains("&lt;img src=x onerror="))
        assertTrue(snippet.contains("<mark>script</mark>"))
        assertTrue(snippet.contains("<mark>alert</mark>"))
    }

    @Test
    fun `multi-term highlighting merges without garbling`() {
        val snippet1 = SearchSnippet.build("The market is a mark of trade.", listOf("market", "ar"))
        assertTrue(snippet1.contains("<mark>market</mark>"))
        assertTrue(snippet1.contains("m<mark>ar</mark>k"))
        assertFalse(snippet1.contains("<m<mark>"))

        val snippet2 = SearchSnippet.build("Fish & chips with an amp amplifier.", listOf("amp"))
        assertTrue(snippet2.contains("&amp;"))
        assertFalse(snippet2.contains("<mark>&amp;</mark>"))
        assertTrue(snippet2.contains("<mark>amp</mark>"))
    }

    @Test
    fun `no-hit case returns head-160 with ellipsis`() {
        val text = "a".repeat(200)
        val snippet = SearchSnippet.build(text, listOf("zzz"))
        assertTrue(snippet.endsWith("…"))
        assertFalse(snippet.contains("<mark>"))
        assertEquals(161, snippet.length)
    }

    @Test
    fun `head and tail ellipses bracket an interior hit`() {
        val text = "x".repeat(100) + " needle " + "y".repeat(100)
        val snippet = SearchSnippet.build(text, listOf("needle"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("<mark>needle</mark>"))
    }
}
