package dev.folio.mobile.core.backup

import dev.folio.mobile.core.backup.exportBackupZip
import dev.folio.mobile.core.importer.ImportClean
import dev.folio.mobile.core.importer.ImportOriginal
import dev.folio.mobile.core.importer.ImportReading
import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.importer.SearchChapterText
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.HighlightColor
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.store.AppFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Round-trip tests for the schema-1 backup contract (PWA parity, port of the
 * packages/core backup tests' scenarios). The same ZIP must be byte-exchangeable
 * between apps/web and the native app.
 */
class BackupRestoreTest {


    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AppFileStore(tmp.newFolder("lib-${UUID.randomUUID()}"))

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
        searchText = listOf(
            SearchChapterText("ch-1", meta.title, "x $body y")
        )
    )

    private fun seedLibrary(store: AppFileStore, meta: DocMeta, body: String): DocMeta =
        kotlinx.coroutines.runBlocking {
            LibraryManager(store).addDocument(importResult(meta, body))
        }

    private fun exportBytes(store: AppFileStore): ByteArray {
        val out = ByteArrayOutputStream()
        kotlinx.coroutines.runBlocking {
            exportBackupZip(store, out)
        }
        return out.toByteArray()
    }

    /** Stages the in-memory zip through the production streaming path (EPUB-007). */
    private fun verificationOf(zip: ByteArray): VerificationResult {
        StagedArchive.stage(zip.inputStream(), tmp.newFolder("stage-verify-${UUID.randomUUID()}")).use {
            return unzipAndVerifyBackup(it)
        }
    }

    @Test
    fun `export then verify round trips a clean library`() {
        val s1 = store()
        seedLibrary(s1, doc("d1", "first-book", "hash-1"), body = "one")

        val zip = exportBytes(s1)
        val verification = verificationOf(zip)

        assertTrue(verification.issues.joinToString { it.message }, verification.valid)
        assertEquals(1, verification.backupMeta?.docCount)
        assertTrue(verification.totalFiles >= 3) // backup.json + manifest + doc files
        // Content lives on the staged archive (never in a big in-RAM map).
        StagedArchive.stage(zip.inputStream(), tmp.newFolder("stage-names-${UUID.randomUUID()}")).use { staged ->
            assertTrue(staged.hasEntry("backup.json"))
            assertTrue(staged.hasEntry("library/manifest.json"))
            assertTrue(staged.entryNames.any { it.startsWith("library/docs/") && it.endsWith("/meta.json") })
        }
    }

    @Test
    fun `tampered manifest checksum fails verification`() {
        // Crafted archive: manifest internal checksums don't match the payload.
        val payload = "reading bytes".encodeToByteArray()
        val wrongSha = dev.folio.mobile.core.util.sha256Hex("different".encodeToByteArray())
        val manifestJson = """
            {
              "schema": 1,
              "app": "folio",
              "libraryId": "lib-1",
              "updatedAt": "2026-10-17T00:00:00Z",
              "docs": [
                {
                  "id": "d1",
                  "slug": "first-book",
                  "title": "T",
                  "contentHash": "hash-1",
                  "updatedAt": "2026-10-17T00:00:00Z",
                  "files": [
                    { "path": "library/docs/first-book/reading/read.html", "size": ${payload.size}, "sha256": "$wrongSha" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val manifestSha = dev.folio.mobile.core.util.sha256Hex(manifestJson.encodeToByteArray())
        val backupMetaJson = """
            {
              "schema": 1,
              "createdAt": "2026-10-17T00:00:00Z",
              "appVersion": "test",
              "docCount": 1,
              "manifestSha256": "$manifestSha"
            }
        """.trimIndent()

        val bytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("backup.json"))
                zos.write(backupMetaJson.encodeToByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("library/manifest.json"))
                zos.write(manifestJson.encodeToByteArray())
                zos.closeEntry()
                // STORED so bytes match verbatim → deterministic sha mismatch.
                val e = ZipEntry("library/docs/first-book/reading/read.html")
                e.method = ZipEntry.STORED
                e.size = payload.size.toLong()
                e.crc = java.util.zip.CRC32().apply { update(payload) }.value
                zos.putNextEntry(e)
                zos.write(payload)
                zos.closeEntry()
            }
            bos.toByteArray()
        }

        val verification = verificationOf(bytes)
        assertFalse(verification.valid)
        assertTrue(verification.issues.any { it.reason == "checksum_mismatch" && it.path.endsWith("read.html") })
    }

    @Test
    fun `unsafe paths are flagged not executed`() {
        val bytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("../evil.txt"))
                zos.write("bad".encodeToByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("library/manifest.json"))
                zos.write("{}".encodeToByteArray())
                zos.closeEntry()
            }
            bos.toByteArray()
        }

        val verification = verificationOf(bytes)
        assertTrue(verification.issues.any { it.reason == "unsafe_path" && it.path == "../evil.txt" })
        // .. entry was skipped during verification; restore only writes
        // manifest-listed paths, so unsafe paths never extract.
        assertFalse(verification.issues.isEmpty())
    }

    @Test
    fun `merge adds new docs skips hashes and never deletes local`() = runBlocking2 {
        val sBackup = store()
        seedLibrary(sBackup, doc("d1", "2026-10-17-first-book", "hash-1"), body = "one")
        val zip = exportBytes(sBackup)

        // Local library: different doc + local-only annotation and progress.
        val sLocal = store()
        val local = LibraryManager(sLocal)
        seedLibrary(sLocal, doc("d2", "2026-10-17-second-book", "hash-2"), body = "two")
        local.saveAnnotation(
            annotation("a-local", "d2", updatedAt = "2026-10-18T00:00:00Z")
        )

        val (summary, verification) = restoreZip(sLocal, local, zip, RestoreMode.Merge)
        assertTrue(verification.valid)
        assertEquals(1, summary.totalDocsInBackup)
        assertEquals(1, summary.newDocsAdded)
        assertEquals(0, summary.duplicatesSkipped)

        val docsAfter = local.listDocuments()
        // Merge never deletes: both local d2 and restored d1 remain.
        assertEquals(2, docsAfter.size)
        assertTrue(docsAfter.any { it.contentHash == "hash-2" })
        assertTrue(docsAfter.any { it.contentHash == "hash-1" })
        // Local-only annotations survive.
        assertEquals(listOf("a-local"), local.getAnnotations().map { it.id })
    }

    @Test
    fun `merge skips same contentHash even under a different id`() = runBlocking2 {
        val sBackup = store()
        seedLibrary(sBackup, doc("d1", "2026-10-17-first-book", "hash-1"), body = "one")
        val zip = exportBytes(sBackup)

        val sLocal = store()
        val local = LibraryManager(sLocal)
        // Same content bytes → same hash (only its doc id differs).
        seedLibrary(sLocal, doc("dX", "2026-10-17-first-book", "hash-1", title = "Renamed"), body = "one")

        val (summary, _) = restoreZip(sLocal, local, zip, RestoreMode.Merge)
        assertEquals(1, summary.duplicatesSkipped)
        assertEquals(0, summary.newDocsAdded)
        assertEquals("Renamed", local.listDocuments().single().title)
    }

    @Test
    fun `annotations merge newer-wins by id and progress newer-wins per doc`() = runBlocking2 {
        val sBackup = store()
        val bManager = LibraryManager(sBackup)
        seedLibrary(sBackup, doc("d1", "2026-10-17-first-book", "hash-1"), body = "one")
        // Backup has a NEWER version of the same annotation id...
        bManager.saveAnnotation(
            annotation("shared", "d1", updatedAt = "2026-10-19T00:00:00Z", note = "backup wins")
        )
        // ...and a NEWER progress entry for the same document.
        bManager.saveProgress(
            Progress(
                "d1", "epubcfi(/6/2)", 42.0,
                updatedAt = "2026-10-20T00:00:00Z"
            )
        )
        val zip = exportBytes(sBackup)

        val sLocal = store()
        val local = LibraryManager(sLocal)
        seedLibrary(sLocal, doc("d1", "2026-10-17-first-book", "hash-1"), body = "one")
        local.saveAnnotation(
            annotation("shared", "d1", updatedAt = "2026-10-18T00:00:00Z", note = "older local")
        )
        local.saveProgress(Progress("d1", "epubcfi(/6/2)", 5.0, "2026-10-18T00:00:00Z"))

        val (summary, _) = restoreZip(sLocal, local, zip, RestoreMode.Merge)
        assertEquals(1, summary.annotationsMerged)
        assertEquals(1, summary.progressUpdated)
        val notes = local.getAnnotations().associateBy { it.id }
        assertEquals("backup wins", notes["shared"]!!.note)
        // Backup progress is newer → its percent replaces the local one.
        assertEquals(42.0, local.getAllProgress()["d1"]!!.percent, 0.0)
    }

    @Test
    fun `collections merge unions docIds and never duplicates`() = runBlocking2 {
        val sBackup = store()
        val backupMeta = seedLibrary(sBackup, doc("d1", "2026-10-17-first-book", "hash-1"), body = "one")
        // Same collection id on both sides (ids are regenerated UUIDs at
        // import time, so both libraries must reference a fixed id here).
        sBackup.write(
            "library/user/collections.json",
            """[{"id":"coll-1","name":"Reading","docIds":["${backupMeta.id}"],""" +
                "\"createdAt\":\"2026-10-17T00:00:00Z\"}]"
        )
        val zip = exportBytes(sBackup)

        val sLocal = store()
        val localMeta = seedLibrary(sLocal, doc("d2", "2026-10-17-second-book", "hash-2"), body = "two")
        sLocal.write(
            "library/user/collections.json",
            """[{"id":"coll-1","name":"Reading","docIds":["${localMeta.id}"],""" +
                "\"createdAt\":\"2026-10-16T00:00:00Z\"}]"
        )
        val local = LibraryManager(sLocal)

        val (summary, _) = restoreZip(sLocal, local, zip, RestoreMode.Merge)
        assertEquals(1, summary.collectionsMerged)
        val collections = local.getCollections()
        assertEquals(1, collections.size)
        // Union of backup + local docIds inside the same collection id.
        assertEquals(
            setOf(backupMeta.id, localMeta.id),
            collections.single().docIds.toSet()
        )
    }

    @Test
    fun `replace wipes local state and restores exactly the backup`() = runBlocking2 {
        val sBackup = store()
        val bManager = LibraryManager(sBackup)
        val meta = seedLibrary(sBackup, doc("d1", "2026-10-17-first-book", "hash-1"), body = "one")
        bManager.saveAnnotation(annotation("b1", "d1", updatedAt = "2026-10-18T00:00:00Z"))
        val zip = exportBytes(sBackup)

        val sLocal = store()
        val local = LibraryManager(sLocal)
        seedLibrary(sLocal, doc("d2", "2026-10-17-second-book", "hash-Z"), body = "two")
        local.saveAnnotation(annotation("local-only", "d2", updatedAt = "2026-10-18T00:00:00Z"))

        val (summary, _) = restoreZip(sLocal, local, zip, RestoreMode.Replace)
        val docs = local.listDocuments()
        assertEquals(1, docs.size)
        assertEquals("hash-1", docs.single().contentHash)
        assertEquals(listOf("b1"), local.getAnnotations().map { it.id })
    }

    /** Restores through the production streaming path: zip bytes → staged file (EPUB-007). */
    private suspend fun restoreZip(
        sLocal: AppFileStore,
        local: LibraryManager,
        zip: ByteArray,
        mode: RestoreMode
    ): Pair<RestoreSummary, VerificationResult> =
        BackupRestore.restore(sLocal, local, zip.inputStream(), tmp.newFolder("stage-${UUID.randomUUID()}"), mode)

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
        kind = dev.folio.mobile.core.model.AnnotationKind.Highlight,
        color = HighlightColor.Yellow,
        createdAt = "2026-10-17T00:00:00Z",
        updatedAt = updatedAt,
        note = note
    )

    /** Tiny helper so test bodies can use suspend LibraryManager APIs. */
    companion object {
        fun runBlocking2(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
            kotlinx.coroutines.runBlocking { block() }
        }
    }
}
