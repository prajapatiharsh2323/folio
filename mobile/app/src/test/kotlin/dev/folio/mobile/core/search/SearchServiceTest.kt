package dev.folio.mobile.core.search

import dev.folio.mobile.core.model.Chapter
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchServiceTest {

    private fun meta(chapters: List<String>): DocMeta = DocMeta(
        id = "d1",
        slug = "d1-slug",
        title = "Alpha Book",
        author = "A. Authoress",
        kind = DocKind.Book,
        language = "hi",
        sourceFormat = SourceFormat.Epub,
        capturedAt = "2026-10-17T00:00:00Z",
        importedAt = "2026-10-17T00:00:00Z",
        contentHash = "h",
        chapters = chapters.mapIndexed { i, t -> Chapter("ch-${i + 1}", t, order = i + 1, href = "x/$i.html") }
    )

    @Test
    fun `entriesFor indexes each chapter with doc level metadata`() {
        val entries = SearchEntries.entriesFor(
            meta(listOf("Pehla Adhyay", "Doosra Adhyay")),
            listOf(
                dev.folio.mobile.core.importer.SearchChapterText("ch-1", "Pehla Adhyay", "ganga kehte hain"),
                dev.folio.mobile.core.importer.SearchChapterText("ch-2", "Doosra Adhyay", "aur yah katha")
            )
        )
        assertEquals(2, entries.size)
        assertEquals("d1:ch-1", entries[0].id)
        assertEquals("Alpha Book", entries[0].title)
        assertEquals("A. Authoress", entries[0].author)
        assertEquals("Pehla Adhyay", entries[0].chapterTitle)
    }

    @Test
    fun `chapterOrders mirror 1-based spine order`() {
        val orders = SearchEntries.chapterOrders(meta(listOf("A", "B", "C")))
        assertEquals(1, orders["ch-1"])
        assertEquals(3, orders["ch-3"])
    }

    @Test
    fun `snippet parts decode entities and split mark segments`() {
        val parts = SearchUi.parts("a &amp;b<mark>&lt;hi&gt;</mark> tail")
        assertEquals("a &b", parts[0].text)
        assertTrue(!parts[0].match)
        assertEquals("<hi>", parts[1].text)
        assertTrue(parts[1].match)
        assertEquals(" tail", parts[2].text)
        assertTrue(!parts[2].match)
    }

    @Test
    fun `apostrophe and quote entities decode`() {
        // &#039; -> ' and &quot; -> " : both entities fully decode
        assertEquals("'x\"", SearchUi.decodeEntities("&#039;x&quot;"))
    }
}
