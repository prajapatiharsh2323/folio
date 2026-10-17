package dev.folio.mobile.core.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteCardContentTest {

    @Test
    fun `quote clamps at word boundary with ellipsis`() {
        val text = "a".repeat(50) + " " + "b".repeat(300)
        val out = QuoteCardContent.clampAndWrap(text, QuoteCardContent.MAX_QUOTE_CHARS, 38)
        val joined = out.joinToString(" ")
        assertTrue(joined.length <= QuoteCardContent.MAX_QUOTE_CHARS + 8)
        assertTrue(joined.endsWith("…"))
        out.forEach { assertTrue(it.length <= QuoteCardContent.QUOTE_LINE_CHARS) }
    }

    @Test
    fun `short quote stays whole`() {
        val out = QuoteCardContent.clampAndWrap("नमस्ते दुनिया", 280, 38)
        assertEquals(listOf("नमस्ते दुनिया"), out)
    }

    @Test
    fun `long unwrappable token hard-splits`() {
        val out = QuoteCardContent.clampAndWrap("x".repeat(90), 280, 38)
        assertTrue(out.size >= 2)
        out.forEach { assertTrue(it.length <= 38) }
    }

    @Test
    fun `blank note yields no note lines`() {
        val layout = QuoteCardContent.of("quote", "   ", "Book", "Author", 0xFFFFE58A)
        assertTrue(layout.noteLines.isEmpty())
        assertEquals("Book", layout.bookTitle)
        assertEquals("Author", layout.author)
    }

    @Test
    fun `layout keeps schema-1 inputs verbatim for title and author`() {
        val layout = QuoteCardContent.of("q", "n", " Pride  ", " Prejudice ", 0xFFB7E4B0)
        assertEquals("Pride", layout.bookTitle)
        assertEquals("Prejudice", layout.author)
        assertEquals(listOf("n"), layout.noteLines)
    }

    @Test
    fun `multiline input preserved across wraps`() {
        val out = QuoteCardContent.clampAndWrap("line one\nline two", 280, 38)
        assertEquals(listOf("line one", "line two"), out)
    }
}
