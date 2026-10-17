package dev.folio.mobile.core.library

import dev.folio.mobile.core.importer.ImportClean
import dev.folio.mobile.core.importer.ImportCover
import dev.folio.mobile.core.importer.ImportImage
import dev.folio.mobile.core.importer.ImportOriginal
import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.importer.ImportReading
import dev.folio.mobile.core.importer.SearchChapterText
import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.DocFiles
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.model.HighlightColor
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.store.AppFileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryManagerTest {

    private companion object {
        val jsonCodec = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: AppFileStore
    private lateinit var manager: LibraryManager

    @Before
    fun setUp() {
        store = AppFileStore(tmp.newFolder("root"))
        manager = LibraryManager(
            store,
            uuidProvider = { "test-uuid-${System.nanoTime()}" }
        )
    }

    private fun importResult(
        title: String = "Test Book",
        withClean: Boolean = true,
        withCover: Boolean = false
    ) = ImportResult(
        meta = DocMeta(
            id = "pending", // filled by LibraryManager at commit time
            slug = "pending",
            title = title,
            kind = DocKind.Book,
            sourceFormat = SourceFormat.Epub,
            capturedAt = "2026-10-17T00:00:00.000Z",
            importedAt = "pending",
            contentHash = sha256Of(title)
        ),
        original = ImportOriginal("book.epub", "ORIGINAL-EPUB-BYTES".toByteArray()),
        clean = if (withClean) ImportClean(
            indexHtml = "<html><body><p>Clean body</p></body></html>",
            images = listOf(ImportImage("img1.png", "PNG-BYTES".toByteArray()))
        ) else null,
        reading = ImportReading("book.epub", "READING-BYTES".toByteArray()),
        cover = if (withCover) ImportCover("cover.jpg", "JPEG-COVER-BYTES".toByteArray()) else null,
        searchText = listOf(SearchChapterText("ch1", "Chapter One", "Searchable text here"))
    )

    private fun sha256Of(s: String) = dev.folio.mobile.core.util.sha256Hex(s.toByteArray())

    @Test
    fun `getManifest initializes schema-1 manifest`() = runBlocking {
        val manifest = manager.getManifest()
        assertEquals(1, manifest.schema)
        assertEquals("folio", manifest.app)
        assertTrue(manifest.libraryId.isNotBlank())
        assertTrue(manifest.docs.isEmpty())
        assertTrue(store.exists(MANIFEST_PATH))
    }

    @Test
    fun `corrupted manifest reinitializes cleanly`() = runBlocking {
        manager.getManifest()
        store.write(MANIFEST_PATH, "{corrupted json!!")
        val manifest = manager.getManifest()
        assertEquals(1, manifest.schema)
        assertTrue(manifest.docs.isEmpty())
    }

    @Test
    fun `addDocument follows exact write order and commits manifest last`() = runBlocking {
        val meta = manager.addDocument(importResult())

        // meta.json filled in id/slug/importedAt/files
        assertTrue(meta.id.isNotBlank())
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}-test-book-[0-9a-f]{4}$").matches(meta.slug))
        assertEquals("original/book.epub", meta.files.original)
        assertEquals("clean/index.html", meta.files.clean)
        assertEquals("reading/book.epub", meta.files.reading)
        assertNull("no cover in this result", meta.files.cover)

        // All content files exist at their manifest paths
        assertTrue(store.exists("library/docs/${meta.slug}/original/book.epub"))
        assertTrue(store.exists("library/docs/${meta.slug}/clean/index.html"))
        assertTrue(store.exists("library/docs/${meta.slug}/clean/images/img1.png"))
        assertTrue(store.exists("library/docs/${meta.slug}/reading/book.epub"))
        assertTrue(store.exists("library/docs/${meta.slug}/meta.json"))
        assertTrue(store.exists("library/index/text/${meta.id}.json"))

        // Manifest lists every file with correct sha256
        val manifest = manager.getManifest()
        assertEquals(1, manifest.docs.size)
        val mDoc = manifest.docs[0]
        assertEquals(meta.id, mDoc.id)
        assertEquals(meta.contentHash, mDoc.contentHash)
        val recorded = mDoc.files.associate { it.path to it.sha256 }
        for (path in recorded.keys) {
            val actual = dev.folio.mobile.core.util.sha256Hex(store.read(path))
            assertEquals("sha256 mismatch for $path", recorded[path], actual)
        }
    }

    @Test
    fun `cover is written, hashed and recorded in files`() = runBlocking {
        val meta = manager.addDocument(importResult(withCover = true))

        assertEquals("cover.jpg", meta.files.cover)
        val coverPath = "library/docs/${meta.slug}/cover.jpg"
        assertTrue(store.exists(coverPath))
        assertEquals("JPEG-COVER-BYTES", store.read(coverPath).toString(Charsets.UTF_8))

        // manifest lists the cover with a correct sha256
        val mDoc = manager.getManifest().docs[0]
        val coverEntry = mDoc.files.firstOrNull { it.path == coverPath }
        assertTrue("cover missing from manifest files", coverEntry != null)
        assertEquals(sha256Of("JPEG-COVER-BYTES"), coverEntry!!.sha256)

        // meta.json on disk carries files.cover too (PWA-compatible contract)
        val metaJson = jsonCodec.decodeFromString<DocMeta>(
            store.read("library/docs/${meta.slug}/meta.json").toString(Charsets.UTF_8)
        )
        assertEquals("cover.jpg", metaJson.files.cover)
    }

    @Test
    fun `duplicate detection by contentHash`() = runBlocking {
        manager.addDocument(importResult(title = "Book One"))
        val dup = manager.findDuplicate(sha256Of("Book One"))
        assertTrue(dup != null)
        assertNull(manager.findDuplicate(sha256Of("Book Two")))
    }

    @Test
    fun `listDocuments returns committed docs`() = runBlocking {
        manager.addDocument(importResult(title = "First"))
        manager.addDocument(importResult(title = "Second"))
        val docs = manager.listDocuments()
        assertEquals(2, docs.size)
        assertEquals(setOf("First", "Second"), docs.map { it.title }.toSet())
    }

    @Test
    fun `updateDocMeta persists edits and updates manifest`() = runBlocking {
        val meta = manager.addDocument(importResult())
        val updated = manager.updateDocMeta(meta.id, title = "Renamed", tags = listOf("a", "b"))
        assertEquals("Renamed", updated.title)
        assertEquals(listOf("a", "b"), updated.tags)

        val listed = manager.listDocuments().first()
        assertEquals("Renamed", listed.title)
        val manifest = manager.getManifest()
        assertEquals("Renamed", manifest.docs[0].title)
    }

    @Test
    fun `deleteDocument removes every trace then rewrites manifest`() = runBlocking {
        val meta = manager.addDocument(importResult())
        manager.saveProgress(Progress(meta.id, "epubcfi(1)", 50.0, "2026-10-17T00:00:00.000Z"))
        manager.saveAnnotation(
            Annotation(
                id = "a1", docId = meta.id, quote = "q", prefix = "p", suffix = "s",
                kind = AnnotationKind.Highlight, color = HighlightColor.Pink,
                createdAt = "2026-10-17T00:00:00.000Z", updatedAt = "2026-10-17T00:00:00.000Z"
            )
        )
        manager.saveCollections(listOf(Collection("c1", "Col", listOf(meta.id), "2026-10-17T00:00:00.000Z")))

        manager.deleteDocument(meta.id)

        assertFalse(store.exists("library/docs/${meta.slug}"))
        assertFalse(store.exists("library/index/text/${meta.id}.json"))
        assertTrue(manager.getAllProgress().isEmpty())
        assertTrue(manager.getAnnotations().isEmpty())
        val coll = manager.getCollections().first()
        assertTrue(coll.docIds.isEmpty())
        assertTrue(manager.getManifest().docs.isEmpty())
        // second delete of same id is a no-op
        manager.deleteDocument(meta.id)
    }

    @Test
    fun `recoverInterruptedImports finds uncommitted doc folders`() = runBlocking {
        manager.addDocument(importResult(title = "Committed"))
        // Simulate an interrupted import: folder on disk, not in manifest
        store.write("library/docs/2026-10-17-orphan-ffff/meta.json", "{\"id\":\"orphan\"}")
        val orphans = manager.recoverInterruptedImports()
        assertEquals(listOf("2026-10-17-orphan-ffff"), orphans)
    }

    @Test
    fun `damagedDocuments surfaces entries with missing meta`() = runBlocking {
        val meta = manager.addDocument(importResult())
        store.remove("library/docs/${meta.slug}", recursive = true)
        val damaged = manager.damagedDocuments()
        assertEquals(listOf(meta.id), damaged.map { it.id })
        // not silently dropped: manifest still lists it
        assertEquals(1, manager.getManifest().docs.size)
    }

    @Test
    fun `removing a damaged doc clears manifest and recovery`() = runBlocking {
        // Banner flow: corrupt meta.json → entry is damaged → Remove → clean.
        val meta = manager.addDocument(importResult())
        store.write("library/docs/${meta.slug}/meta.json", "{corrupted json!!")
        assertEquals(listOf(meta.id), manager.damagedDocuments().map { it.id })

        manager.deleteDocument(meta.id)

        assertTrue(manager.damagedDocuments().isEmpty())
        assertTrue(manager.getManifest().docs.isEmpty())
        assertFalse(store.exists("library/docs/${meta.slug}"))
    }
}
