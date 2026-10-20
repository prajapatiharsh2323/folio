package dev.folio.mobile.core.annotation

import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.model.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationFiltersTest {

    private fun annotation(
        id: String,
        kind: AnnotationKind,
        note: String? = null,
    ) = Annotation(
        id = id,
        docId = "doc",
        quote = "quote",
        prefix = "",
        suffix = "",
        kind = kind,
        color = if (kind == AnnotationKind.Bookmark) null else HighlightColor.Yellow,
        note = note,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private val highlight = annotation("h", AnnotationKind.Highlight)
    private val highlightWithNote = annotation("hn", AnnotationKind.Highlight, note = "my thought")
    private val note = annotation("n", AnnotationKind.Note, note = "standalone note")
    private val bookmark = annotation("b", AnnotationKind.Bookmark)
    private val all = listOf(highlight, highlightWithNote, note, bookmark)

    @Test
    fun `all keeps every annotation and its order`() {
        assertEquals(all, all.filtered(AnnotationFilter.All))
    }

    @Test
    fun `highlights contains highlight records including ones with notes`() {
        assertEquals(
            listOf(highlight, highlightWithNote, note),
            all.filtered(AnnotationFilter.Highlights),
        )
    }

    @Test
    fun `notes contains any record with note text`() {
        assertEquals(
            listOf(highlightWithNote, note),
            all.filtered(AnnotationFilter.Notes),
        )
    }

    @Test
    fun `a highlight with a note intentionally appears in both filters`() {
        val highlights = all.filtered(AnnotationFilter.Highlights).map { it.id }
        val notes = all.filtered(AnnotationFilter.Notes).map { it.id }
        assertEquals(true, "hn" in highlights)
        assertEquals(true, "hn" in notes)
    }

    @Test
    fun `bookmarks filters only saved locations`() {
        assertEquals(listOf(bookmark), all.filtered(AnnotationFilter.Bookmarks))
    }

    @Test
    fun `blank note text is not a note`() {
        val blank = annotation("blank", AnnotationKind.Highlight, note = "   ")
        assertEquals(emptyList<Annotation>(), listOf(blank).filtered(AnnotationFilter.Notes))
    }
}
