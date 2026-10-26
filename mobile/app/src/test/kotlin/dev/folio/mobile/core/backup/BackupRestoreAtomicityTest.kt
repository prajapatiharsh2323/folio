package dev.folio.mobile.core.backup

import dev.folio.mobile.core.importer.ImportOriginal
import dev.folio.mobile.core.importer.ImportReading
import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.importer.SearchChapterText
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.library.MANIFEST_PATH
import dev.folio.mobile.core.library.RestoreSwap
import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.HighlightColor
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.store.FileStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Audit P0-03/P0-04 regression suite: a failed or malformed Replace restore
 * must never lose or mutate the previous library, malformed user-state JSON in
 * a checksum-valid archive must be refused BEFORE any local change, and an
 * interrupted swap must self-heal.
 */
class BackupRestoreAtomicityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AppFileStore(tmp.newFolder("root-${UUID.randomUUID()}"))

    private fun doc(
        id: String,
        slug: String,
        hash: String,
        title: String = "T-$id",
        updatedAt: String = "2026-10-17T00:00:00Z"
    ) = DocMeta(
        id = id,
        slug = slug,
        title = title,
        author = "Author $id",
        kind = DocKind.Book,
        capturedAt = "2026-10-17T00:00:00Z",
        importedAt = updatedAt,
        contentHash = hash,
        sourceFormat = SourceFormat.Txt
    )

    private fun importResult(meta: DocMeta, body: String) = ImportResult(
        meta = meta,
        original = ImportOriginal("book.txt", ("original-$body").encodeToByteArray()),
        reading = ImportReading(
            "read.html",
            ("<!doctype html><html><body><h1>${meta.title}</h1><p>chapter text $body</p></body></html>")
                .encodeToByteArray()
        ),
        searchText = listOf(SearchChapterText("ch-1", meta.title, "x $body y"))
    )

    private fun annotation(
        id: String,
        docId: String,
        updatedAt: String,
        note: String? = null
    ) = Annotation(
        id = id,
        docId = docId,
        chapterId = "ch-1",
        quote = "quoted text $id",
        prefix = "",
        suffix = "",
        location = "epubcfi(/6/4!/4/2)",
        kind = AnnotationKind.Highlight,
        color = HighlightColor.Yellow,
        createdAt = "2026-10-17T00:00:00Z",
        updatedAt = updatedAt,
        note = note
    )

    private fun seedLibrary(store: AppFileStore, meta: DocMeta, body: String): DocMeta =
        runBlocking {
            LibraryManager(store).addDocument(importResult(meta, body))
        }

    private fun exportBytes(store: AppFileStore): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { exportBackupZip(store, out) }
        return out.toByteArray()
    }

    /** Replaces the bytes of one archive entry (checksums inside backup.json then mismatch). */
    private fun withEntryReplaced(zip: ByteArray, name: String, bytes: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(zip.inputStream()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    zos.putNextEntry(ZipEntry(e.name))
                    if (e.name == name) {
                        zos.write(bytes.encodeToByteArray())
                    } else {
                        zos.write(zis.readBytes())
                    }
                    zos.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private suspend fun assertNoWorkDirs(store: AppFileStore) {
        assertFalse(store.list("library").contains("restore-staging"))
        assertFalse(store.list("library").contains("restore-rollback"))
    }

    // --- tests ----------------------------------------------------------------

    @Test
    fun `corrupt backup user state refuses Replace before any local change`() {
        runBlocking {
            val backupStore = store()
            val bManager = LibraryManager(backupStore)
            seedLibrary(backupStore, doc("d1", "2026-10-17-first-book", "hash-1"), body = "one")
            bManager.saveAnnotation(annotation("b1", "d1", updatedAt = "2026-10-18T00:00:00Z", note = "backup note"))
            val zip = exportBytes(backupStore)
            val brokenZip = withEntryReplaced(zip, "library/user/annotations.json", "<not json at all!!")

            val localStore = store()
            val local = LibraryManager(localStore)
            seedLibrary(localStore, doc("d2", "2026-10-17-second-book", "hash-Z"), body = "two")
            local.saveAnnotation(annotation("local-only", "d2", updatedAt = "2026-10-18T00:00:00Z"))

            try {
                BackupRestore.restore(
                    localStore, local, brokenZip.inputStream(),
                    File(tmp.newFolder("staging-a1").absolutePath), RestoreMode.Replace
                )
                fail("malformed annotations.json in the archive must abort the restore")
            } catch (expected: IllegalArgumentException) {
                assertTrue("message must name the section: ${expected.message}", expected.message!!.contains("saved notes"))
            }

            // Nothing was touched: the previous library is fully intact.
            val docs = local.listDocuments()
            assertEquals(1, docs.size)
            assertEquals("hash-Z", docs.single().contentHash)
            assertEquals(listOf("local-only"), local.getAnnotations().map { it.id })
            assertFalse(localStore.list("library").contains("restore-staging"))
            assertFalse(localStore.list("library").contains("restore-rollback"))
        }
    }

    @Test
    fun `corrupt backup progress refuses Merge before any local change`() {
        runBlocking {
            val backupStore = store()
            val bManager = LibraryManager(backupStore)
            seedLibrary(backupStore, doc("d1", "2026-10-17-first-book", "hash-1"), body = "one")
            bManager.saveProgress(Progress("d1", "epubcfi(/6/2)", 42.0, "2026-10-20T00:00:00Z"))
            val zip = exportBytes(backupStore)
            val brokenZip = withEntryReplaced(zip, "library/user/progress.json", "{")

            val localStore = store()
            val local = LibraryManager(localStore)
            seedLibrary(localStore, doc("d2", "2026-10-17-second-book", "hash-Z"), body = "two")
            local.saveProgress(Progress("d2", "epubcfi(/6/2)", 7.0, "2026-10-20T00:00:00Z"))

            try {
                BackupRestore.restore(
                    localStore, local, brokenZip.inputStream(),
                    File(tmp.newFolder("staging-a2").absolutePath), RestoreMode.Merge
                )
                fail("malformed progress.json in the archive must abort the restore")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("reading progress"))
            }

            // Nothing was touched: local doc + progress intact.
            val docs = local.listDocuments()
            assertEquals(1, docs.size)
            assertEquals("hash-Z", docs.single().contentHash)
            assertEquals(1, local.getAllProgress().size)
        }
    }

    @Test
    fun `staging write failure leaves the previous library untouched`() {
        runBlocking {
            val backupStore = store()
            seedLibrary(backupStore, doc("d1", "2026-10-17-first-book", "hash-1"), body = "one")
            val zip = exportBytes(backupStore)

            val localStore = store()
            val local = LibraryManager(localStore)
            seedLibrary(localStore, doc("d2", "2026-10-17-second-book", "hash-Z"), body = "two")
            local.saveAnnotation(annotation("local-only", "d2", updatedAt = "2026-10-18T00:00:00Z"))
            local.saveProgress(Progress("d2", "epubcfi(/6/2)", 7.0, "2026-10-20T00:00:00Z"))

            val faulty = FaultyStore(localStore).also { it.failWriteOnStaging = true }

            try {
                BackupRestore.restore(
                    faulty, local, zip.inputStream(), tmp.newFolder("staging-a3"), RestoreMode.Replace
                )
                fail("injected write failure must abort the restore")
            } catch (expected: IOException) {
            }

            val docs = local.listDocuments()
            assertEquals(1, docs.size)
            assertEquals("hash-Z", docs.single().contentHash)
            assertEquals(listOf("local-only"), local.getAnnotations().map { it.id })
            assertEquals(1, local.getAllProgress().size)
            assertNoWorkDirs(localStore)
        }
    }

    @Test
    fun `rename failure mid-commit rolls fully back to the previous library`() {
        runBlocking {
            val backupStore = store()
            val bManager = LibraryManager(backupStore)
            seedLibrary(backupStore, doc("d1", "2026-10-17-first-book", "hash-1"), body = "one")
            bManager.saveAnnotation(annotation("b1", "d1", updatedAt = "2026-10-18T00:00:00Z", note = "backup note"))
            bManager.saveProgress(Progress("d1", "epubcfi(/6/2)", 42.0, "2026-10-20T00:00:00Z"))
            val zip = exportBytes(backupStore)

            val localStore = store()
            val local = LibraryManager(localStore)
            seedLibrary(localStore, doc("d2", "2026-10-17-second-book", "hash-Z"), body = "two")
            local.saveAnnotation(annotation("local-only", "d2", updatedAt = "2026-10-18T00:00:00Z"))
            local.saveProgress(Progress("d2", "epubcfi(/6/2)", 7.0, "2026-10-20T00:00:00Z"))

            val faulty = FaultyStore(localStore).also { it.failRenameOnDocsInstall = true }

            try {
                BackupRestore.restore(
                    faulty, local, zip.inputStream(), tmp.newFolder("staging-a4"), RestoreMode.Replace
                )
                fail("injected rename failure must abort the restore commit")
            } catch (expected: IOException) {
            }

            val docs = local.listDocuments()
            assertEquals(1, docs.size)
            assertEquals("hash-Z", docs.single().contentHash)
            assertEquals(listOf("local-only"), local.getAnnotations().map { it.id })
            assertEquals(1, local.getAllProgress().size)
            assertNoWorkDirs(localStore)
        }
    }

    @Test
    fun `interrupted swap self-heals via rollbackPending`() {
        runBlocking {
            val localStore = store()
            val local = LibraryManager(localStore)
            seedLibrary(localStore, doc("d2", "2026-10-17-second-book", "hash-Z"), body = "two")
            local.saveAnnotation(annotation("local-only", "d2", updatedAt = "2026-10-18T00:00:00Z"))
            local.saveProgress(Progress("d2", "epubcfi(/6/2)", 7.0, "2026-10-20T00:00:00Z"))

            // Simulate a process death mid-commit: live docs/user got swapped in
            // (originals live in the rollback snapshot), the manifest rename never
            // happened, and a "staged" docs tree shows what got installed.
            localStore.rename("library/docs", "${RestoreSwap.ROLLBACK_DIR}/docs")
            localStore.rename("library/user", "${RestoreSwap.ROLLBACK_DIR}/user")
            localStore.rename(MANIFEST_PATH, "${RestoreSwap.ROLLBACK_DIR}/manifest.json")
            localStore.write("library/docs/backup-slug/meta.json", "{}".toByteArray())

            val recovered = RestoreSwap.rollbackPending(localStore)
            assertTrue("an interrupted swap must be detected", recovered)

            val docs = local.listDocuments()
            assertEquals(1, docs.size)
            assertEquals("hash-Z", docs.single().contentHash)
            assertFalse(localStore.list("library").contains("backup-slug"))
            assertFalse(localStore.list("library").contains("restore-rollback"))
            assertFalse(localStore.list("library").contains("restore-staging"))
        }
    }

    // --- fault-injection seam -------------------------------------------------

    /**
     * Wraps a real [AppFileStore]; can fail selected writes/renames on purpose.
     * `failWriteOnStaging` fails the first write under the staging tree;
     * `failRenameOnDocsInstall` fails only install-phase renames (staging → live).
     */
    private class FaultyStore(
        private val delegate: AppFileStore,
        var failWriteOnStaging: Boolean = false,
        var failRenameOnDocsInstall: Boolean = false,
    ) : FileStore by delegate {
        override suspend fun write(path: String, data: ByteArray) {
            if (failWriteOnStaging && path.startsWith(RestoreSwap.STAGING_DIR)) {
                throw IOException("injected write failure: $path")
            }
            delegate.write(path, data)
        }

        override suspend fun write(path: String, data: String) {
            if (failWriteOnStaging && path.startsWith(RestoreSwap.STAGING_DIR)) {
                throw IOException("injected write failure: $path")
            }
            delegate.write(path, data)
        }

        override suspend fun rename(from: String, to: String) {
            if (
                failRenameOnDocsInstall &&
                from.startsWith(RestoreSwap.STAGING_DIR) &&
                (to == "library/docs" || to == "library/manifest.json")
            ) {
                throw IOException("injected rename failure: $from -> $to")
            }
            delegate.rename(from, to)
        }
    }
}
