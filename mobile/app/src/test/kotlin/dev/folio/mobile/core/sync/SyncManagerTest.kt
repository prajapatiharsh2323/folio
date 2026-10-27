package dev.folio.mobile.core.sync

import dev.folio.mobile.core.importer.ImportOriginal
import dev.folio.mobile.core.importer.ImportReading
import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.importer.SearchChapterText
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.HighlightColor
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.util.sha256Hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * The Drive mirror contract, ported from packages/core/tests/drive-sync.test.ts.
 *
 * These are the scenarios the PWA guarantees; the mobile app has to keep the
 * same behaviour because both apps mirror into the same Drive folder.
 */
class SyncManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(label: String) =
        AppFileStore(tmp.newFolder("$label-${UUID.randomUUID()}"))

    private fun docMeta(
        id: String,
        title: String,
        hash: String,
        updatedAt: String = "2026-10-19T00:00:00Z",
    ) = DocMeta(
        id = id,
        slug = "slug-$id",
        title = title,
        author = "Author $id",
        kind = DocKind.Book,
        capturedAt = updatedAt,
        importedAt = updatedAt,
        contentHash = hash,
        sourceFormat = SourceFormat.Txt,
    )

    private fun importResult(meta: DocMeta, body: String) = ImportResult(
        meta = meta,
        original = ImportOriginal("book-${meta.id}.txt", ("original-$body").encodeToByteArray()),
        reading = ImportReading(
            "read.html",
            ("<!doctype html><html><body><h1>${meta.title}</h1><p>chapter $body</p></body></html>")
                .encodeToByteArray(),
        ),
        searchText = listOf(SearchChapterText("ch-1", meta.title, "x $body y")),
    )

    private fun manager(store: AppFileStore) = LibraryManager(store)

    private fun syncManager(
        store: AppFileStore,
        provider: SyncProvider,
    ) = SyncManager(store = store, library = LibraryManager(store), provider = provider)

    private fun addDoc(store: AppFileStore, meta: DocMeta, body: String) = runBlocking {
        manager(store).addDocument(importResult(meta, body))
    }

    private fun annotation(docId: String, id: String, updatedAt: String) = Annotation(
        id = id,
        docId = docId,
        quote = "quote $id",
        prefix = "",
        suffix = "",
        kind = AnnotationKind.Highlight,
        color = HighlightColor.Yellow,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    @Test
    fun `two devices converge on the same documents, notes and progress`() {
        val cloud = FakeSyncProvider()
        val deviceA = store("device-a")
        val deviceB = store("device-b")

        // Device A adds a book with a note and reading progress.
        val docA = addDoc(deviceA, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")
        runBlocking {
            manager(deviceA).saveAnnotation(annotation(docA.id, "annot-a1", "2026-10-12T10:00:00Z"))
            manager(deviceA).saveProgress(
                Progress(
                    docId = docA.id,
                    location = "loc-1",
                    percent = 0.25,
                    updatedAt = "2026-10-12T10:00:00Z",
                )
            )
        }

        val resultA = runBlocking { syncManager(deviceA, cloud).sync() }
        assertEquals(1, resultA.uploadedDocs)

        // Device B has its own book and syncs: it downloads A's book and
        // uploads its own.
        val docB = addDoc(deviceB, docMeta("d2", "Book Beta", "hash-beta"), "beta")
        runBlocking {
            manager(deviceB).saveAnnotation(annotation(docB.id, "annot-b1", "2026-10-12T11:00:00Z"))
        }

        val resultB = runBlocking { syncManager(deviceB, cloud).sync() }
        assertEquals(1, resultB.downloadedDocs)
        assertEquals(1, resultB.uploadedDocs)

        val manifestB = runBlocking { manager(deviceB).getManifest() }
        assertEquals(2, manifestB.docs.size)
        val annotationsB = runBlocking { manager(deviceB).getAnnotations() }
        assertEquals(2, annotationsB.size)

        // Device A syncs again and picks up B's book and note.
        val resultA2 = runBlocking { syncManager(deviceA, cloud).sync() }
        assertEquals(1, resultA2.downloadedDocs)

        val manifestA = runBlocking { manager(deviceA).getManifest() }
        assertEquals(2, manifestA.docs.size)
        val annotationsA = runBlocking { manager(deviceA).getAnnotations() }
        assertEquals(2, annotationsA.size)
    }

    @Test
    fun `newer metadata wins when both sides changed a title`() {
        val cloud = FakeSyncProvider()
        val deviceA = store("device-a")
        val deviceB = store("device-b")

        val docA = addDoc(deviceA, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")
        runBlocking { syncManager(deviceA, cloud).sync() }

        // Device B gets the book, then renames it (newer updatedAt).
        runBlocking { syncManager(deviceB, cloud).sync() }
        val docOnB = runBlocking { manager(deviceB).listDocuments().first { it.title == "Book Alpha" } }
        runBlocking {
            manager(deviceB).updateDocMeta(docOnB.id, title = "Renamed on B")
        }
        runBlocking { syncManager(deviceB, cloud).sync() }

        // Device A picks up the newer title.
        runBlocking { syncManager(deviceA, cloud).sync() }
        val manifestA = runBlocking { manager(deviceA).getManifest() }
        val updated = manifestA.docs.firstOrNull { it.id == docA.id }
        assertEquals("Renamed on B", updated?.title)
    }

    @Test
    fun `deletions never propagate to the mirror`() {
        val cloud = FakeSyncProvider()
        val deviceA = store("device-a")

        val docA = addDoc(deviceA, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")
        runBlocking { syncManager(deviceA, cloud).sync() }
        assertTrue(cloud.paths().any { it.contains("/original/") })

        runBlocking { manager(deviceA).deleteDocument(docA.id) }
        assertEquals(0, runBlocking { manager(deviceA).getManifest() }.docs.size)

        runBlocking { syncManager(deviceA, cloud).sync() }
        // Version-one rule: the mirror still holds the book.
        assertTrue(cloud.paths().any { it.contains("/original/") })
    }

    @Test
    fun `the same book imported on two devices is reconciled to one document`() {
        val cloud = FakeSyncProvider()
        val deviceA = store("device-a")
        val deviceB = store("device-b")

        val docA = addDoc(deviceA, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")
        runBlocking { syncManager(deviceA, cloud).sync() }

        // Device B imports the same book independently: different id, same
        // content hash.
        val docB = addDoc(deviceB, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")
        assertTrue(docA.id != docB.id)

        runBlocking {
            manager(deviceB).saveAnnotation(annotation(docB.id, "annot-b-1", "2026-10-19T09:00:00Z"))
            manager(deviceB).saveProgress(
                Progress(
                    docId = docB.id,
                    location = "epubcfi(/6/4)",
                    percent = 0.6,
                    updatedAt = "2026-10-19T09:00:00Z",
                )
            )
        }
        runBlocking { syncManager(deviceB, cloud).sync() }

        // Device A receives the note and progress, remapped to its own id.
        runBlocking { syncManager(deviceA, cloud).sync() }

        val manifestA = runBlocking { manager(deviceA).getManifest() }
        assertEquals(1, manifestA.docs.count { it.contentHash == "hash-alpha" })

        val annotationsA = runBlocking { manager(deviceA).getAnnotations(docA.id) }
        assertTrue(annotationsA.any { it.id == "annot-b-1" && it.docId == docA.id })

        val progressA = runBlocking { manager(deviceA).getProgress(docA.id) }
        assertNotNull(progressA)
        assertEquals(0.6, progressA!!.percent, 0.0001)
    }

    @Test
    fun `a downloaded book is searchable again`() {
        val cloud = FakeSyncProvider()
        val deviceA = store("device-a")
        val deviceB = store("device-b")

        val docA = addDoc(deviceA, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")
        runBlocking { syncManager(deviceA, cloud).sync() }
        runBlocking { syncManager(deviceB, cloud).sync() }

        // The search corpus is not mirrored (spec §8 excludes the index), so
        // the receiving device rebuilds it from the downloaded file.
        val searchText = runBlocking { manager(deviceB).getSearchText(docA.id) }
        assertTrue("search text was not rebuilt", searchText.isNotEmpty())
    }

    @Test
    fun `the manifest is uploaded last so a half-synced library is never advertised`() {
        val cloud = FakeSyncProvider()
        val deviceA = store("device-a")
        addDoc(deviceA, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")

        runBlocking { syncManager(deviceA, cloud).sync() }

        // The manifest is in the mirror and describes the book.
        assertTrue(cloud.contains(SyncManager.MANIFEST_PATH))
        val manifest = dev.folio.mobile.core.backup.parseManifest(
            cloud.bytesOf(SyncManager.MANIFEST_PATH)!!.decodeToString()
        )
        assertEquals(1, manifest.docs.size)
        assertEquals("Book Alpha", manifest.docs.first().title)
    }

    @Test
    fun `the sync state is kept locally and never uploaded`() {
        val cloud = FakeSyncProvider()
        val deviceA = store("device-a")
        addDoc(deviceA, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")

        runBlocking { syncManager(deviceA, cloud).sync() }

        val state = runBlocking { syncManager(deviceA, cloud).loadState() }
        assertEquals(1, state.version)
        assertTrue(state.files.keys.any { it.endsWith("meta.json") })
        assertTrue("sync state must stay local", !cloud.contains(SyncManager.SYNC_STATE_PATH))
    }

    // --- corruption safety -------------------------------------------------

    private fun seed(cloud: FakeSyncProvider, path: String, bytes: ByteArray) = runBlocking {
        cloud.upload(path, bytes, sha256Hex(bytes))
    }

    private fun expectAbort(block: suspend () -> Unit): IllegalStateException {
        try {
            runBlocking { block() }
        } catch (expected: IllegalStateException) {
            return expected
        }
        fail("sync must abort instead of overwriting unreadable data")
        error("unreachable")
    }

    @Test
    fun `an unreadable remote manifest stops sync before anything changes`() {
        val cloud = FakeSyncProvider()
        val device = store("device-a")
        addDoc(device, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")

        val corrupt = "{not a manifest".encodeToByteArray()
        seed(cloud, SyncManager.MANIFEST_PATH, corrupt)

        expectAbort { syncManager(device, cloud).sync() }

        assertArrayEquals(corrupt, cloud.bytesOf(SyncManager.MANIFEST_PATH))
        // The local manifest was not replaced either.
        assertEquals(1, runBlocking { manager(device).getManifest() }.docs.size)
    }

    @Test
    fun `an unreadable remote notes file stops sync and is left untouched`() {
        val cloud = FakeSyncProvider()
        val device = store("device-a")
        addDoc(device, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")
        runBlocking { syncManager(device, cloud).sync() }

        val corrupt = "[{oops".encodeToByteArray()
        seed(cloud, SyncManager.ANNOTATIONS_PATH, corrupt)

        val error = expectAbort { syncManager(device, cloud).sync() }
        assertTrue(error.message!!.contains("cloud storage"))
        assertArrayEquals(corrupt, cloud.bytesOf(SyncManager.ANNOTATIONS_PATH))
    }

    @Test
    fun `unreadable local notes stop sync without being overwritten`() {
        val cloud = FakeSyncProvider()
        val device = store("device-a")
        addDoc(device, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")
        val corrupt = "not json[".toByteArray()
        runBlocking { device.write(SyncManager.ANNOTATIONS_PATH, corrupt) }

        expectAbort { syncManager(device, cloud).sync() }

        assertArrayEquals(corrupt, runBlocking { device.read(SyncManager.ANNOTATIONS_PATH) })
        assertFalse(
            "aborted sync must not advertise a half-synced library",
            cloud.contains(SyncManager.MANIFEST_PATH)
        )
    }

    @Test
    fun `unreadable local progress stops sync without being overwritten`() {
        val cloud = FakeSyncProvider()
        val device = store("device-a")
        addDoc(device, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")
        val corrupt = "{bad".toByteArray()
        runBlocking { device.write(SyncManager.PROGRESS_PATH, corrupt) }

        expectAbort { syncManager(device, cloud).sync() }

        assertArrayEquals(corrupt, runBlocking { device.read(SyncManager.PROGRESS_PATH) })
        assertFalse(cloud.contains(SyncManager.MANIFEST_PATH))
    }

    @Test
    fun `unreadable local collections stop sync without being overwritten`() {
        val cloud = FakeSyncProvider()
        val device = store("device-a")
        addDoc(device, docMeta("d1", "Book Alpha", "hash-alpha"), "alpha")
        val corrupt = "[bad".toByteArray()
        runBlocking { device.write(SyncManager.COLLECTIONS_PATH, corrupt) }

        expectAbort { syncManager(device, cloud).sync() }

        assertArrayEquals(corrupt, runBlocking { device.read(SyncManager.COLLECTIONS_PATH) })
        assertFalse(cloud.contains(SyncManager.MANIFEST_PATH))
    }
}
