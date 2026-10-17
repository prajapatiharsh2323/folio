package dev.folio.mobile.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema-1 JSON byte-compatibility conformance tests.
 *
 * The JSON literals here mirror what the PWA writes (packages/core/src/types.ts).
 * If either side changes a field name, enum value, or optionality, these tests go
 * red. Update BOTH suites in the same commit (MOBILE-TRACKING policy).
 */
class TypesContractTest {

    private val json = Json {
        ignoreUnknownKeys = true // forward-compatible: tolerate PWA-added fields
        encodeDefaults = true
    }

    @Test
    fun `parses a PWA-shaped DocMeta json literal`() {
        val pwaJson = """
        {
          "schema": 1,
          "id": "0b8b8f6e-1111-4222-8333-444455556666",
          "slug": "2026-10-17-sample-article-ab12",
          "title": "Sample Article",
          "author": "A. Author",
          "kind": "article",
          "language": "en",
          "sourceFormat": "clip",
          "sourceUrl": "https://example.com/a",
          "sourceName": "Example",
          "capturedAt": "2026-10-17T00:00:00.000Z",
          "importedAt": "2026-10-17T00:00:00.000Z",
          "contentHash": "deadbeef",
          "wordCount": 500,
          "searchable": true,
          "tags": ["tech"],
          "collectionIds": ["c1"],
          "files": { "clean": "clean/index.html", "reading": "reading/book.epub" },
          "chapters": [ { "id": "ch1", "title": "Body", "order": 0, "href": "clean/index.html" } ]
        }
        """.trimIndent()

        val doc = json.decodeFromString<DocMeta>(pwaJson)
        assertEquals(DocKind.Article, doc.kind)
        assertEquals(SourceFormat.Clip, doc.sourceFormat)
        assertEquals("clean/index.html", doc.files.clean)
        assertEquals(1, doc.chapters.size)
        assertEquals("ch1", doc.chapters[0].id)
    }

    @Test
    fun `parses a PWA-shaped Manifest json literal`() {
        val pwaJson = """
        {
          "schema": 1,
          "app": "folio",
          "libraryId": "lib-1",
          "updatedAt": "2026-10-17T00:00:00.000Z",
          "docs": [
            {
              "id": "d1",
              "slug": "2026-10-17-book-cd34",
              "title": "Book",
              "contentHash": "cafe",
              "updatedAt": "2026-10-17T00:00:00.000Z",
              "files": [ { "path": "docs/2026-10-17-book-cd34/reading/book.epub", "size": 1024, "sha256": "ff00" } ]
            }
          ]
        }
        """.trimIndent()

        val manifest = json.decodeFromString<Manifest>(pwaJson)
        assertEquals("folio", manifest.app)
        assertEquals(1, manifest.docs.size)
        assertEquals("ff00", manifest.docs[0].files[0].sha256)
    }

    @Test
    fun `round-trips annotation with enum string values identical to PWA`() {
        val annotation = Annotation(
            id = "a1",
            docId = "d1",
            chapterId = "ch1",
            quote = "the quick brown fox",
            prefix = "Over ",
            suffix = " jumps",
            location = "epubcfi(/6/4!/4/2/1:0)",
            kind = AnnotationKind.Highlight,
            color = HighlightColor.Green,
            note = null,
            createdAt = "2026-10-17T00:00:00.000Z",
            updatedAt = "2026-10-17T00:00:00.000Z"
        )

        val encoded = json.encodeToString(annotation)
        assertTrue(encoded.contains("\"kind\":\"highlight\""))
        assertTrue(encoded.contains("\"color\":\"green\""))
        val decoded = json.decodeFromString<Annotation>(encoded)
        assertEquals(annotation, decoded)
    }

    @Test
    fun `tolerates missing optional fields (PWA writes compact json)`() {
        val minimal = """
        {
          "id": "d2",
          "slug": "2026-10-17-x-ef56",
          "title": "Minimal",
          "kind": "book",
          "sourceFormat": "epub",
          "capturedAt": "2026-10-17T00:00:00.000Z",
          "importedAt": "2026-10-17T00:00:00.000Z",
          "contentHash": "abcd"
        }
        """.trimIndent()

        val doc = json.decodeFromString<DocMeta>(minimal)
        assertEquals(DocKind.Book, doc.kind)
        assertEquals(null, doc.author)
        assertTrue(doc.tags.isEmpty())
        assertTrue(doc.chapters.isEmpty())
        assertEquals(1, doc.schema)
    }

    @Test
    fun `unknown enum value fails loudly, not silently`() {
        val badKind = """
        {
          "id": "d3",
          "slug": "2026-10-17-y-aa99",
          "title": "Bad",
          "kind": "manuscript",
          "sourceFormat": "epub",
          "capturedAt": "2026-10-17T00:00:00.000Z",
          "importedAt": "2026-10-17T00:00:00.000Z",
          "contentHash": "abcd"
        }
        """.trimIndent()

        val result = runCatching { json.decodeFromString<DocMeta>(badKind) }
        assertTrue("Unknown DocKind must throw (no silent corruption)", result.isFailure)
    }
}
