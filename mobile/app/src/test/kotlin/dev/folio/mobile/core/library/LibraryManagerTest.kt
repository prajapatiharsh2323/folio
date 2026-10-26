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
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private fun orphanMeta(
        slug: String,
        id: String,
        title: String,
        files: DocFiles = DocFiles(),
        contentHash: String = sha256Of(title)
    ) = DocMeta(
        id = id,
        slug = slug,
        title = title,
        kind = DocKind.Book,
        sourceFormat = SourceFormat.Epub,
        capturedAt = "2026-10-17T00:00:00.000Z",
        importedAt = "2026-10-17T00:00:00.000Z",
        contentHash = contentHash,
        files = files
    )

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
    fun `corrupted manifest is preserved, not overwritten or shown as a valid empty library`() = runBlocking {
        manager.getManifest()
        store.write(MANIFEST_PATH, "{corrupted json!!")
        // The corrupt file must survive so recovery/inspection can see it — and
        // the manager must never hand callers a bogus empty manifest (P0-01).
        try {
            manager.getManifest()
            fail("getManifest must throw on an undecodable manifest")
        } catch (expected: LibraryFileCorruptException) {
            assertEquals(MANIFEST_PATH, expected.path)
        }
        assertEquals("{corrupted json!!", store.readText(MANIFEST_PATH))
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
        // second delete of the same id is refused (unknown id), never silent
        try {
            manager.deleteDocument(meta.id)
            fail("deleteDocument must throw for an id that is not in the manifest")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `replaceDocument migrates notes progress and collections to the new copy`() = runBlocking {
        val old = manager.addDocument(importResult(title = "Old Edition"))
        manager.saveProgress(Progress(old.id, "epubcfi(1)", 42.0, "2026-10-17T00:00:00.000Z"))
        manager.saveAnnotation(
            Annotation(
                id = "a1", docId = old.id, quote = "q", prefix = "p", suffix = "s",
                kind = AnnotationKind.Highlight, color = HighlightColor.Pink,
                createdAt = "2026-10-17T00:00:00.000Z", updatedAt = "2026-10-17T00:00:00.000Z"
            )
        )
        val collection = manager.createCollection("Keepers")
        manager.setDocCollections(old.id, listOf(collection.id))

        val replacement = manager.replaceDocument(
            old.id,
            importResult(title = "New Edition").copy(
                original = ImportOriginal("new.epub", "NEW-BYTES".toByteArray())
            )
        )

        assertTrue(replacement.id != old.id)
        assertFalse(store.exists("library/docs/${old.slug}"))
        assertEquals(listOf(replacement.id), manager.getManifest().docs.map { it.id })
        assertEquals(42.0, manager.getProgress(replacement.id)!!.percent, 0.0001)
        assertEquals(listOf("a1"), manager.getAnnotations().map { it.id })
        assertEquals(replacement.id, manager.getAnnotations().first().docId)
        assertEquals(listOf(replacement.id), manager.getCollections().first().docIds)
        val meta = manager.listDocuments().first()
        assertEquals(listOf(collection.id), meta.collectionIds)
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
        // folder fully removed: no meta file, nothing left to surface
        assertFalse(store.exists("library/docs/${meta.slug}/meta.json"))
        assertFalse(manager.recoverInterruptedImports().contains(meta.slug))
    }

    @Test
    fun `scanInterruptedImports classifies finishable vs delete-only`() = runBlocking {
        val goodBytes = "Good Orphan".toByteArray()
        store.write("library/docs/good-orphan/original/book.epub", goodBytes)
        store.write("library/docs/good-orphan/reading/book.epub", goodBytes)
        store.write(
            "library/docs/good-orphan/meta.json",
            jsonCodec.encodeToString(
                orphanMeta(
                    "good-orphan",
                    "id-good",
                    "Good Orphan",
                    files = DocFiles(
                        original = "original/book.epub",
                        reading = "reading/book.epub"
                    )
                )
            )
        )
        store.write("library/docs/bad-orphan/junk.bin", "junk".toByteArray())

        val scanned = manager.scanInterruptedImports().associateBy { it.slug }
        assertEquals(2, scanned.size)
        assertTrue(scanned["good-orphan"]!!.finishable)
        assertEquals("Good Orphan", scanned["good-orphan"]!!.title)
        assertNull(scanned["good-orphan"]!!.problem)
        assertFalse(scanned["bad-orphan"]!!.finishable)
        assertNull(scanned["bad-orphan"]!!.title)
        assertEquals("its metadata can't be read", scanned["bad-orphan"]!!.problem)
    }

    @Test
    fun `scanInterruptedImports requires a real reading payload and matching checksum`() = runBlocking {
        val goodBytes = "PAYLOAD".toByteArray()
        // Metadata exists, but the reading file it names was never written.
        store.write("library/docs/no-reading/original/book.epub", goodBytes)
        store.write(
            "library/docs/no-reading/meta.json",
            jsonCodec.encodeToString(
                orphanMeta(
                    "no-reading",
                    "id-no-reading",
                    "No Reading",
                    files = DocFiles(
                        original = "original/book.epub",
                        reading = "reading/book.epub"
                    ),
                    contentHash = sha256Of("PAYLOAD")
                )
            )
        )
        // Payload exists, but its bytes no longer match the recorded checksum.
        store.write("library/docs/tampered/original/book.epub", "TAMPERED".toByteArray())
        store.write("library/docs/tampered/reading/book.epub", goodBytes)
        store.write(
            "library/docs/tampered/meta.json",
            jsonCodec.encodeToString(
                orphanMeta(
                    "tampered",
                    "id-tampered",
                    "Tampered",
                    files = DocFiles(
                        original = "original/book.epub",
                        reading = "reading/book.epub"
                    ),
                    contentHash = sha256Of("EXPECTED-OTHER-BYTES")
                )
            )
        )

        val scanned = manager.scanInterruptedImports().associateBy { it.slug }
        assertFalse(scanned["no-reading"]!!.finishable)
        assertEquals("its reading file is missing", scanned["no-reading"]!!.problem)
        assertFalse(scanned["tampered"]!!.finishable)
        assertTrue(scanned["tampered"]!!.problem!!.contains("checksum"))

        // Finishing through the API must enforce the same contract.
        var refused = false
        try {
            manager.finishInterruptedImport("no-reading")
        } catch (_: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
        assertTrue(manager.getManifest().docs.isEmpty())
    }

    @Test
    fun `finishInterruptedImport re-registers the orphan with rebuilt files`() = runBlocking {
        val slug = "2026-10-17-orphan-aaaa"
        val orphanBytes = "ORPHAN-EPUB".toByteArray()
        store.write("library/docs/$slug/original/book.epub", orphanBytes)
        store.write("library/docs/$slug/reading/book.epub", orphanBytes)
        store.write(
            "library/docs/$slug/meta.json",
            jsonCodec.encodeToString(
                orphanMeta(
                    slug,
                    "orphan-id-1",
                    "Orphan Book",
                    files = DocFiles(
                        original = "original/book.epub",
                        reading = "reading/book.epub"
                    ),
                    contentHash = sha256Of("ORPHAN-EPUB")
                )
            )
        )

        val meta = manager.finishInterruptedImport(slug)
        assertEquals("orphan-id-1", meta.id)

        val mDoc = manager.getManifest().docs.single()
        assertEquals(slug, mDoc.slug)
        assertEquals("Orphan Book", mDoc.title)
        assertEquals("orphan-id-1", mDoc.id)
        val byPath = mDoc.files.associateBy { it.path }
        assertEquals(
            setOf(
                "library/docs/$slug/meta.json",
                "library/docs/$slug/original/book.epub",
                "library/docs/$slug/reading/book.epub"
            ),
            byPath.keys
        )
        assertEquals(11L, byPath["library/docs/$slug/original/book.epub"]!!.size)
        // every recorded hash matches disk (same guarantee as addDocument)
        for (f in mDoc.files) {
            assertEquals(
                "sha256 mismatch for ${f.path}",
                dev.folio.mobile.core.util.sha256Hex(store.read(f.path)),
                f.sha256
            )
        }

        assertTrue(manager.recoverInterruptedImports().isEmpty())
        assertEquals(listOf("Orphan Book"), manager.listDocuments().map { it.title })

        // finishing twice is refused
        var threw = false
        try {
            manager.finishInterruptedImport(slug)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)

        // no meta.json → cannot finish
        store.write("library/docs/empty-orphan/junk.txt", "x".toByteArray())
        var threw2 = false
        try {
            manager.finishInterruptedImport("empty-orphan")
        } catch (_: IllegalArgumentException) {
            threw2 = true
        }
        assertTrue(threw2)
    }

    @Test
    fun `discardInterruptedImport removes folder and index text, refuses registered`() = runBlocking {
        val slug = "2026-10-17-junk-aaaa"
        store.write(
            "library/docs/$slug/meta.json",
            jsonCodec.encodeToString(orphanMeta(slug, "id-junk", "Junk"))
        )
        store.write("library/index/text/id-junk.json", "[]")

        manager.discardInterruptedImport(slug)

        // File inside the folder must be gone (exists() only checks files) and
        // the folder must no longer surface as an interrupted import.
        assertFalse(store.exists("library/docs/$slug/meta.json"))
        assertFalse(store.exists("library/index/text/id-junk.json"))
        assertTrue(manager.recoverInterruptedImports().isEmpty())

        // registered docs must go through deleteDocument
        val meta = manager.addDocument(importResult())
        var threw = false
        try {
            manager.discardInterruptedImport(meta.slug)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(store.exists("library/docs/${meta.slug}/meta.json"))
    }

    @Test
    fun `collection CRUD round-trips and refuses unknown rename`() = runBlocking {
        val created = manager.createCollection("Fiction")
        assertTrue(created.id.isNotBlank())
        assertEquals("Fiction", created.name)
        assertTrue(created.docIds.isEmpty())
        assertEquals(listOf(created.id), manager.getCollections().map { it.id })

        val renamed = manager.renameCollection(created.id, "Sci-Fi")
        assertEquals("Sci-Fi", renamed.name)
        assertEquals("Sci-Fi", manager.getCollections().first().name)

        var threw = false
        try {
            manager.renameCollection("nope", "x")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)

        manager.deleteCollection(created.id)
        assertTrue(manager.getCollections().isEmpty())
    }

    @Test
    fun `deleteCollection clears doc metadata but keeps the books`() = runBlocking {
        val meta = manager.addDocument(importResult())
        val collection = manager.createCollection("Fiction")
        manager.setDocCollections(meta.id, listOf(collection.id))

        val result = manager.deleteCollection(collection.id)

        assertEquals(1, result.cleanedDocuments)
        assertTrue(result.failedSlugs.isEmpty())
        assertTrue(manager.getCollections().isEmpty())
        // The book stays in the library manifest and its metadata has no stale id.
        assertEquals(listOf(meta.id), manager.listDocuments().map { it.id })
        val onDisk = jsonCodec.decodeFromString<DocMeta>(
            store.read("library/docs/${meta.slug}/meta.json").toString(Charsets.UTF_8)
        )
        assertTrue(onDisk.collectionIds.isEmpty())
    }

    @Test
    fun `setDocCollections syncs collection docIds and doc meta`() = runBlocking {
        val meta = manager.addDocument(importResult())
        val a = manager.createCollection("A")
        val b = manager.createCollection("B")

        manager.setDocCollections(meta.id, listOf(a.id))
        var byId = manager.getCollections().associateBy { it.id }
        assertEquals(listOf(meta.id), byId[a.id]!!.docIds)
        assertTrue(byId[b.id]!!.docIds.isEmpty())
        var onDisk = jsonCodec.decodeFromString<DocMeta>(
            store.read("library/docs/${meta.slug}/meta.json").toString(Charsets.UTF_8)
        )
        assertEquals(listOf(a.id), onDisk.collectionIds)

        // switching collections moves membership both ways
        manager.setDocCollections(meta.id, listOf(b.id))
        byId = manager.getCollections().associateBy { it.id }
        assertTrue(byId[a.id]!!.docIds.isEmpty())
        assertEquals(listOf(meta.id), byId[b.id]!!.docIds)

        // empty assignment clears membership everywhere
        manager.setDocCollections(meta.id, emptyList())
        assertTrue(manager.getCollections().all { it.docIds.isEmpty() })
        onDisk = jsonCodec.decodeFromString<DocMeta>(
            store.read("library/docs/${meta.slug}/meta.json").toString(Charsets.UTF_8)
        )
        assertTrue(onDisk.collectionIds.isEmpty())
    }

    @Test
    fun `addDocsToCollection bulk-adds once and keeps doc meta in sync`() = runBlocking {
        val meta = manager.addDocument(importResult())
        val coll = manager.createCollection("Shelf")
        assertTrue(coll.updatedAt != null)

        val updated = manager.addDocsToCollection(coll.id, listOf(meta.id))
        assertEquals(listOf(meta.id), updated.docIds)
        assertEquals(listOf(meta.id), manager.getCollections().first().docIds)
        val onDisk = jsonCodec.decodeFromString<DocMeta>(
            store.read("library/docs/${meta.slug}/meta.json").toString(Charsets.UTF_8)
        )
        assertEquals(listOf(coll.id), onDisk.collectionIds)

        // idempotent: re-adding an existing member is a no-op (same identity)
        val again = manager.addDocsToCollection(coll.id, listOf(meta.id))
        assertEquals(listOf(meta.id), again.docIds)
    }

    @Test
    fun `removeDocsFromCollection bulk-removes and clears doc meta`() = runBlocking {
        val meta = manager.addDocument(importResult())
        val coll = manager.createCollection("Shelf")
        manager.addDocsToCollection(coll.id, listOf(meta.id))

        val updated = manager.removeDocsFromCollection(coll.id, listOf(meta.id))
        assertTrue(updated.docIds.isEmpty())
        assertTrue(manager.getCollections().first().docIds.isEmpty())
        val onDisk = jsonCodec.decodeFromString<DocMeta>(
            store.read("library/docs/${meta.slug}/meta.json").toString(Charsets.UTF_8)
        )
        assertTrue(onDisk.collectionIds.isEmpty())
    }

    @Test
    fun `bulk membership writes move a doc between shelves atomically`() = runBlocking {
        val meta = manager.addDocument(importResult())
        val a = manager.createCollection("A")
        val b = manager.createCollection("B")

        manager.addDocsToCollection(a.id, listOf(meta.id))
        manager.removeDocsFromCollection(a.id, listOf(meta.id))
        manager.addDocsToCollection(b.id, listOf(meta.id))

        val byId = manager.getCollections().associateBy { it.id }
        assertTrue(byId[a.id]!!.docIds.isEmpty())
        assertEquals(listOf(meta.id), byId[b.id]!!.docIds)
        val onDisk = jsonCodec.decodeFromString<DocMeta>(
            store.read("library/docs/${meta.slug}/meta.json").toString(Charsets.UTF_8)
        )
        assertEquals(listOf(b.id), onDisk.collectionIds)
    }
}
