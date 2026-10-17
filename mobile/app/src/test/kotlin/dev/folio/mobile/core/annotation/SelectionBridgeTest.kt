package dev.folio.mobile.core.annotation

import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.model.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionBridgeTest {

    @Test
    fun `prefix and suffix are exactly 32 chars of surrounding context`() {
        val before = "a".repeat(50)
        val after = "b".repeat(50)
        val a = SelectionBridge.annotationFrom(
            docId = "doc-1",
            quote = "the quote",
            before = before,
            after = after,
            location = "epubcfi(/6/4!/4/2,/1:2)",
            id = "ann-1",
            updatedAt = "2026-10-17T00:00:00Z"
        )
        assertEquals("the quote", a.quote)
        assertEquals(32, a.prefix.length)
        assertEquals(before.takeLast(32), a.prefix)
        assertEquals(32, a.suffix.length)
        assertEquals(after.take(32), a.suffix)
    }

    @Test
    fun `short context passes through untouched`() {
        val a = SelectionBridge.annotationFrom(
            docId = "doc-1",
            quote = "q",
            before = "abc",
            after = "de",
            location = null,
            id = "ann-2",
            updatedAt = "2026-10-17T00:00:00Z"
        )
        assertEquals("abc", a.prefix)
        assertEquals("de", a.suffix)
        assertEquals(null, a.location)
    }

    @Test
    fun `maps onto schema 1 highlight fields`() {
        val a = SelectionBridge.annotationFrom(
            docId = "doc-9",
            quote = "hello",
            before = "",
            after = "",
            location = "epubcfi(/6/2)",
            id = "ann-9",
            updatedAt = "2026-10-17T12:00:00Z",
            color = HighlightColor.Green
        )
        assertEquals("doc-9", a.docId)
        assertEquals("ann-9", a.id)
        assertEquals(AnnotationKind.Highlight, a.kind)
        assertEquals(HighlightColor.Green, a.color)
        assertEquals("epubcfi(/6/2)", a.location)
        assertEquals("2026-10-17T12:00:00Z", a.createdAt)
        assertEquals("2026-10-17T12:00:00Z", a.updatedAt)
        assertEquals(null, a.chapterId)
        assertEquals(null, a.note)
    }
}
