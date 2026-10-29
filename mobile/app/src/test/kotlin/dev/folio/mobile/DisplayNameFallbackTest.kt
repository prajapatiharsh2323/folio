package dev.folio.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S1 Phase 4: import file-name resolution order — OpenableColumns.DISPLAY_NAME
 * (query result) beats the URI's last path segment; blank starvation falls all
 * the way to the neutral "import".
 */
class DisplayNameFallbackTest {

    @Test
    fun `provider display name wins over path segment`() {
        assertEquals(
            "My Book.epub",
            displayNameFrom(queryResult = "My Book.epub", lastPathSegment = "primary:Download/xyz"),
        )
    }

    @Test
    fun `blank query result falls back to the last path segment`() {
        assertEquals(
            "book.epub",
            displayNameFrom(queryResult = "  ", lastPathSegment = "primary:Download/book.epub"),
        )
    }

    @Test
    fun `null query result falls back to the stripped path segment`() {
        assertEquals(
            "article.html",
            displayNameFrom(queryResult = null, lastPathSegment = "google%3A/article.html"),
        )
    }

    @Test
    fun `blank everything resolves to import`() {
        assertEquals("import", displayNameFrom(null, null))
        assertEquals("import", displayNameFrom("", " "))
        assertEquals("import", displayNameFrom("", "/"))
    }

    @Test
    fun `directory-only path segments collapse to import`() {
        assertEquals("import", displayNameFrom(null, "primary:Download/"))
        assertEquals("book.epub", displayNameFrom(null, "book.epub"))
    }
}
