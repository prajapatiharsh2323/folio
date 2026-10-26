package dev.folio.mobile.core.library

import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.HighlightColor
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.SourceFormat
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

/**
 * Audit P0-01/P0-02/A5 regression suite: corrupt state files must never
 * masquerade as empty data, must never be silently overwritten by mutations,
 * and every recovery path must preserve the original bytes.
 */
class LibraryCorruptionTest {

    private companion object {
        val jsonCodec = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val CORRUPT = "{corrupted json!!"
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

    // --- fixtures (mirrors of LibraryManagerTest helpers) ---

    private fun sha256Of(s: String) = dev.folio.mobile.core.util.sha256Hex(s.toByteArray())

    private fun importResult(title: String) = ImportResult(
        meta = DocMeta(
            id = "pending",
            slug = "pending",
            title = title,
            kind = DocKind.Book,
            sourceFormat = SourceFormat.Epub,
            capturedAt = "2026-10-17T00:00:00.000Z",
            importedAt = "pending",
            contentHash = sha256Of(title)
        ),
        original = dev.folio.mobile.core.importer.ImportOriginal("book.epub", "ORIGINAL-EPUB-BYTES".toByteArray()),
        clean = null,
        reading = dev.folio.mobile.core.importer.ImportReading("book.epub", "READING-BYTES".toByteArray()),
        cover = null,
        searchText = emptyList()
    )

    private fun annotation(id: String, docId: String = "some-doc") = Annotation(
        id = id,
        docId = docId,
        quote = "a quote",
        prefix = "",
        suffix = "",
        kind = AnnotationKind.Highlight,
        color = HighlightColor.Yellow,
        createdAt = "2026-10-17T00:00:00.000Z",
        updatedAt = "2026-10-17T00:00:00.000Z"
    )

    private fun progress(docId: String = "some-doc") = Progress(
        docId = docId,
        location = "epubcfi(1)",
        percent = 25.0,
        updatedAt = "2026-10-17T00:00:00.000Z"
    )

    private fun collection() = Collection(
        id = "c1",
        name = "Favourites",
        docIds = emptyList(),
        createdAt = "2026-10-17T00:00:00.000Z",
        updatedAt = "2026-10-17T00:00:00.000Z"
    )

    /** Asserts [block] throws LibraryFileCorruptException naming [path]. */
    private inline fun expectCorrupt(
        path: String,
        block: () -> Unit
    ): LibraryFileCorruptException {
        try {
            block()
            fail("Expected LibraryFileCorruptException for $path")
        } catch (e: LibraryFileCorruptException) {
            assertEquals(path, e.path)
            return e
        }
        throw IllegalStateException("unreachable")
    }

    // --- P0-01: corrupt manifest must block mutations and stay intact ---

    @Test
    fun `corrupt manifest blocks addDocument and preserves the file`() = runBlocking {
        val seeded = manager.addDocument(importResult("Book A"))
        store.write(MANIFEST_PATH, CORRUPT)

        expectCorrupt(MANIFEST_PATH) { manager.addDocument(importResult("Book B")) }

        // Original bytes untouched; no second book folder was created.
        assertEquals(CORRUPT, store.readText(MANIFEST_PATH))
        assertEquals(listOf(seeded.slug), store.list("library/docs"))
    }

    @Test
    fun `corrupt manifest blocks deleteDocument instead of reporting success`() = runBlocking {
        val seeded = manager.addDocument(importResult("Book A"))
        store.write(MANIFEST_PATH, CORRUPT)

        expectCorrupt(MANIFEST_PATH) { manager.deleteDocument(seeded.id) }
        // The document entry must keep its normal UI route.
        assertTrue(store.exists("library/docs/${seeded.slug}/meta.json"))
        assertEquals(CORRUPT, store.readText(MANIFEST_PATH))
    }

    @Test
    fun `corrupt manifest blocks finishInterruptedImport and damagedDocument scans surface the state`() = runBlocking {
        store.write(
            "library/docs/orphan-x/meta.json",
            jsonCodec.encodeToString(
                dev.folio.mobile.core.model.DocMeta(
                    id = "orphan-id-x",
                    slug = "orphan-x",
                    title = "Orphan X",
                    kind = DocKind.Book,
                    sourceFormat = SourceFormat.Epub,
                    capturedAt = "2026-10-17T00:00:00.000Z",
                    importedAt = "2026-10-17T00:00:00.000Z",
                    contentHash = sha256Of("Orphan X")
                )
            )
        )
        store.write(MANIFEST_PATH, CORRUPT)

        expectCorrupt(MANIFEST_PATH) { manager.finishInterruptedImport("orphan-x") }
        expectCorrupt(MANIFEST_PATH) { manager.listDocuments() }
        expectCorrupt(MANIFEST_PATH) { manager.damagedDocuments() }
        expectCorrupt(MANIFEST_PATH) { manager.recoverInterruptedImports() }
        assertEquals(CORRUPT, store.readText(MANIFEST_PATH))
    }

    @Test
    fun `repairManifest rebuilds all doc folders and preserves the corrupt bytes`() = runBlocking {
        val a = manager.addDocument(importResult("Alpha"))
        val b = manager.addDocument(importResult("Beta"))
        store.write(MANIFEST_PATH, CORRUPT)

        val repaired = manager.repairManifest()

        assertEquals(2, repaired.registered)
        assertTrue(repaired.skipped.isEmpty())
        assertTrue(repaired.preservedBackupPath.startsWith(RECOVERY_DIR))
        // Original corrupt bytes preserved byte-for-byte in the recovery copy… 
        assertEquals(
            CORRUPT,
            store.read(repaired.preservedBackupPath).toString(Charsets.UTF_8)
        )
        // …the rebuilt index holds both books, and every recorded file hash
        // matches disk (same guarantee as addDocument).
        val manifest = manager.getManifest()
        assertEquals(setOf("Alpha", "Beta"), manifest.docs.map { it.title }.toSet())
        assertTrue(a.id in manifest.docs.map { it.id })
        assertTrue(b.id in manifest.docs.map { it.id })
        for (doc in manifest.docs) {
            for (f in doc.files) {
                assertEquals(
                    "sha256 mismatch for ${f.path}",
                    dev.folio.mobile.core.util.sha256Hex(store.read(f.path)),
                    f.sha256
                )
            }
        }
        assertEquals(2, manager.listDocuments().size)
    }

    @Test
    fun `repairManifest registers unusable folders as skipped and repair refuses healthy manifests`() = runBlocking {
        manager.addDocument(importResult("Good"))
        store.write("library/docs/broken-folder/junk.bin", "junk".toByteArray())
        store.write(MANIFEST_PATH, CORRUPT)

        val repaired = manager.repairManifest()
        assertEquals(1, repaired.registered)
        assertEquals(listOf("broken-folder"), repaired.skipped)

        // A healthy manifest must never pass through the repair path.
        try {
            manager.repairManifest()
            fail("repairManifest must refuse when the manifest is fine")
        } catch (expected: IllegalStateException) {
        }
    }

    // --- P0-02: corrupt user-state files are distinct from empty and never overwritten ---

    @Test
    fun `corrupt annotations block save and reads, bytes preserved`() = runBlocking {
        store.write(ANNOTATIONS_PATH, CORRUPT)

        expectCorrupt(ANNOTATIONS_PATH) { manager.saveAnnotation(annotation("a1")) }
        try {
            manager.removeAnnotation("a1")
            fail("removeAnnotation must not silently no-op against corrupt data")
        } catch (expected: LibraryFileCorruptException) {
        }
        assertEquals(CORRUPT, store.readText(ANNOTATIONS_PATH))

        when (val r = manager.readAnnotationsState()) {
            is ReadResult.Corrupt -> assertEquals(ANNOTATIONS_PATH, r.path)
            else -> fail("Expected Corrupt, got $r")
        }
        // Tolerant reads collapse to empty — callers needing an "is empty" UI
        // state must read the typed result instead (see NotesActivity wiring).
        assertTrue(manager.getAnnotations().isEmpty())
    }

    @Test
    fun `corrupt progress blocks save, typed read surfaces Corrupt, tolerant read logs empty`() = runBlocking {
        store.write(PROGRESS_PATH, CORRUPT)

        expectCorrupt(PROGRESS_PATH) { manager.saveProgress(progress()) }
        assertEquals(CORRUPT, store.readText(PROGRESS_PATH))

        when (val r = manager.readAllProgressState()) {
            is ReadResult.Corrupt -> assertEquals(PROGRESS_PATH, r.path)
            else -> fail("Expected Corrupt, got $r")
        }
        assertNull("tolerant read must collapse to null", manager.getProgress("some-doc"))
        assertTrue(manager.getAllProgress().isEmpty())
        assertEquals(CORRUPT, store.readText(PROGRESS_PATH))
    }

    @Test
    fun `corrupt collections block create and delete, bytes preserved`() = runBlocking {
        store.write(COLLECTIONS_PATH, CORRUPT)

        expectCorrupt(COLLECTIONS_PATH) { manager.createCollection("New") }
        expectCorrupt(COLLECTIONS_PATH) { manager.deleteCollection("c1") }
        when (val r = manager.readCollectionsState()) {
            is ReadResult.Corrupt -> assertEquals(COLLECTIONS_PATH, r.path)
            else -> fail("Expected Corrupt, got $r")
        }
        assertEquals(CORRUPT, store.readText(COLLECTIONS_PATH))
    }

    @Test
    fun `missing user files remain valid empty states`() = runBlocking {
        assertEquals(ReadResult.Missing, manager.readAnnotationsState())
        assertEquals(ReadResult.Missing, manager.readAllProgressState())
        assertEquals(ReadResult.Missing, manager.readCollectionsState())
        // Tolerant reads legitimately return empty data for absent files.
        assertTrue(manager.getAllProgress().isEmpty())
        assertTrue(manager.getAnnotations().isEmpty())
        assertTrue(manager.getCollections().isEmpty())
    }

    @Test
    fun `deleteDocument preserves corrupt user files and reports warnings`() = runBlocking {
        val meta = manager.addDocument(importResult("Book A"))
        store.write(PROGRESS_PATH, CORRUPT)
        store.write(ANNOTATIONS_PATH, CORRUPT)
        store.write(COLLECTIONS_PATH, CORRUPT)

        val result = manager.deleteDocument(meta.id)

        assertTrue("manifest commit should pass when only user files are unreadable", result.manifestRemoved)
        assertEquals(3, result.cleanupWarnings.size)
        assertTrue(manager.getManifest().docs.isEmpty())
        // the unreadable bytes survive for later recovery
        assertEquals(CORRUPT, store.readText(PROGRESS_PATH))
        assertEquals(CORRUPT, store.readText(ANNOTATIONS_PATH))
        assertEquals(CORRUPT, store.readText(COLLECTIONS_PATH))
        assertFalse(store.exists("library/docs/${meta.slug}/meta.json"))
    }

    // --- recovery action: discardCorruptUserState ---

    @Test
    fun `discardCorruptUserState preserves corrupt bytes, clears file, and unblocks saves`() = runBlocking {
        store.write(ANNOTATIONS_PATH, CORRUPT)

        val preserved = manager.discardCorruptUserState(ANNOTATIONS_PATH)

        assertFalse(store.exists(ANNOTATIONS_PATH))
        assertEquals(CORRUPT, store.read(preserved).toString(Charsets.UTF_8))

        // Saves work again, starting from a valid empty state.
        manager.saveAnnotation(annotation("a1"))
        assertEquals(1, manager.readAnnotationsState().orDefault(emptyList()).size)
    }

    @Test
    fun `discardCorruptUserState refuses readable and absent files`() = runBlocking {
        // readable progress.json (written through the strict save) must be refused
        manager.saveProgress(progress())
        try {
            manager.discardCorruptUserState(PROGRESS_PATH)
            fail("must refuse to discard a readable file")
        } catch (expected: IllegalStateException) {
        }
        // absent file must be refused
        try {
            manager.discardCorruptUserState(COLLECTIONS_PATH)
            fail("must refuse to discard an absent file")
        } catch (expected: IllegalStateException) {
        }
        // unknown paths are rejected loudly
        try {
            manager.discardCorruptUserState("library/user/settings.json")
            fail("must reject non-user-state paths")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
