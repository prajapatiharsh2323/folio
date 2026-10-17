package dev.folio.mobile.core.library

import dev.folio.mobile.core.model.DocFiles
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUiTest {

    private fun meta(
        id: String,
        title: String,
        author: String?,
        kind: DocKind = DocKind.Book
    ) = DocMeta(
        id = id,
        slug = "$id-slug",
        title = title,
        author = author,
        kind = kind,
        capturedAt = "2026-10-17T00:00:00Z",
        importedAt = "2026-10-17T00:00:00Z",
        contentHash = "hash-$id",
        sourceFormat = SourceFormat.Txt
    )

    private fun progress(percent: Double) = Progress("d1", "/c1", percent, "2026-10-17T00:00:00Z")

    @Test
    fun `item falls back to kind label when author missing`() {
        val item = LibraryUi.item(meta("d1", "The Book", null), null)
        assertEquals("book", item.subtitle)
        assertFalse(item.percentLabel.isNotBlank())
    }

    @Test
    fun `progress percent shapes the label`() {
        assertEquals("42%", LibraryUi.item(meta("d1", "B", "A"), progress(0.421)).percentLabel)
        assertEquals("0%", LibraryUi.item(meta("d1", "B", "A"), progress(0.0)).percentLabel)
    }

    @Test
    fun `out of range percent clamps`() {
        assertEquals("100%", LibraryUi.item(meta("d1", "B", "A"), progress(1.3)).percentLabel)
    }

    @Test
    fun `title seed is stable and differentiates titles`() {
        assertEquals(LibraryUi.titleSeed("Book"), LibraryUi.titleSeed("Book"))
        assertTrue(LibraryUi.titleSeed("Alpha") != LibraryUi.titleSeed("Beta"))
    }

    @Test
    fun `cover path resolves against the doc slug`() {
        val m = meta("d1", "B", "A").copy(files = DocFiles(cover = "cover.jpg"))
        assertEquals("library/docs/d1-slug/cover.jpg", LibraryUi.item(m, null).cover)
    }

    @Test
    fun `absent or blank cover yields null`() {
        assertNull(LibraryUi.item(meta("d1", "B", "A"), null).cover)
        val blank = meta("d1", "B", "A").copy(files = DocFiles(cover = "  "))
        assertNull(LibraryUi.item(blank, null).cover)
    }

    @Test
    fun `sort puts in-progress first, untouched, finished last`() {
        val unread = LibraryUi.item(meta("u", "B unread", null), null).copy(id = "u")
        val reading = LibraryUi.item(meta("r", "B reading", null), progress(0.5)).copy(id = "r")
        val fresh = LibraryUi.item(meta("f", "B fresh", null), progress(0.0)).copy(id = "f")
        val done = LibraryUi.item(meta("d", "B done", null), progress(1.0)).copy(id = "d")

        val sorted = LibraryUi.sort(listOf(done, unread, fresh, reading))
        assertEquals("f", sorted[0].id)
        assertEquals("r", sorted[1].id)
        assertEquals("u", sorted[2].id)
        assertEquals("d", sorted[3].id)
    }
}
