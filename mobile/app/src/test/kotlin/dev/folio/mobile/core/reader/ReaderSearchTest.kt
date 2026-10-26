package dev.folio.mobile.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the in-book search bridge (audit EPUB-015). */
class ReaderSearchTest {

    // ---- isRunnableQuery ----

    @Test
    fun `queries below two live characters are rejected - lib-search parity`() {
        assertFalse(ReaderSearch.isRunnableQuery(""))
        assertFalse(ReaderSearch.isRunnableQuery("  "))
        assertFalse(ReaderSearch.isRunnableQuery("a"))
        assertTrue(ReaderSearch.isRunnableQuery(" ab "))
        assertTrue(ReaderSearch.isRunnableQuery("needle"))
        // CJK/Devanagari words count characters, not bytes.
        assertTrue(ReaderSearch.isRunnableQuery("किताब"))
    }

    // ---- snippet ----

    @Test
    fun `empty context collapses to an empty snippet`() {
        assertEquals("", ReaderSearch.snippet(null, null, null))
        assertEquals("", ReaderSearch.snippet("  ", "", "\n\t"))
    }

    @Test
    fun `whitespace runs collapse and parts join with spaces`() {
        val snippet = ReaderSearch.snippet(
            before = "the\n  calm  ",
            highlight = "  river",
            after = "flowed\t in   autumn"
        )
        assertEquals("the calm river flowed in autumn", snippet)
    }

    @Test
    fun `long context is capped with an ellipsis`() {
        val long = "x".repeat(ReaderSearch.SNIPPET_MAX + 30)
        val snippet = ReaderSearch.snippet(null, long, null)
        assertEquals(ReaderSearch.SNIPPET_MAX + 1, snippet.length)
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `exactly max-length context stays uncapped`() {
        val exact = "y".repeat(ReaderSearch.SNIPPET_MAX)
        assertEquals(exact, ReaderSearch.snippet(null, exact, null))
        assertFalse(exact.endsWith("…"))
    }

    // ---- sectionTitle ----

    @Test
    fun `locator title wins when present`() {
        assertEquals(
            "Chapter One",
            ReaderSearch.sectionTitle(" Chapter One ", "OPS/chapter1.xhtml")
        )
    }

    @Test
    fun `missing title never falls back to a raw file name`() {
        assertNull(ReaderSearch.sectionTitle(null, "OPS/chapter2.xhtml"))
        assertNull(ReaderSearch.sectionTitle("", "OPS/chapter3.xhtml#note-1"))
    }

    @Test
    fun `blank title and blank href give null`() {
        assertNull(ReaderSearch.sectionTitle(null, ""))
        assertNull(ReaderSearch.sectionTitle(null, "  /  "))
    }

    // ---- buildSnippet (P1-13) ----

    @Test
    fun `match-aware snippet keeps the query even after long leading context`() {
        val before = "start ".repeat(40) // 240 chars > snippet budget
        val built = ReaderSearch.buildSnippet(before, "needle", "tail")
        assertTrue(built != null)
        val text = built!!.text
        assertEquals("needle", text.substring(built.matchStart, built.matchEnd))
        assertTrue(text.length <= ReaderSearch.SNIPPET_MAX + 1)
    }

    @Test
    fun `match-aware snippet reports the exact span`() {
        val built = ReaderSearch.buildSnippet("the calm ", "river", " flowed")
        assertEquals("the calm river flowed", built!!.text)
        assertEquals(9, built.matchStart)
        assertEquals(14, built.matchEnd)
    }

    @Test
    fun `match-aware snippet is null without a match`() {
        assertNull(ReaderSearch.buildSnippet("before", null, "after"))
        assertNull(ReaderSearch.buildSnippet("before", "  ", "after"))
    }
}
