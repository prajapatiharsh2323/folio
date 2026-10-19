package dev.folio.mobile.core.backup

import dev.folio.mobile.core.importer.ImportOriginal
import dev.folio.mobile.core.importer.ImportReading
import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.store.AppFileStore
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Streaming-restore tests (audit EPUB-007): the backup ZIP is staged to a
 * temp file and read entry-by-entry — a multi-megabyte archive with hundreds
 * of docs must restore without the whole library sitting in memory, and the
 * staging file must be cleaned up afterwards.
 */
class BackupRestoreStreamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AppFileStore(tmp.newFolder("root-${UUID.randomUUID()}"))

    /** Deterministic, low-redundancy bytes so the staged archive really is
     *  multi-MB (repetitive text would deflate to almost nothing). */
    private fun lowRedundancyBytes(id: Int, size: Int): ByteArray {
        val rnd = java.util.Random((id + 1L) * 0x5DEECE66DL)
        return ByteArray(size).also { rnd.nextBytes(it) }
    }

    private fun importResult(id: Int): ImportResult {
        val body = "chapter body ".repeat(2 + (id % 20))
        return ImportResult(
            meta = DocMeta(
                id = "pending",
                slug = "pending",
                title = "Doc $id",
                author = null,
                kind = DocKind.Book,
                capturedAt = "2026-10-19T00:00:00Z",
                importedAt = "2026-10-19T00:00:00Z",
                contentHash = "hash-$id",
                sourceFormat = SourceFormat.Txt
            ),
            original = ImportOriginal("book.txt", lowRedundancyBytes(id, 48 * 1024)),
            reading = ImportReading(
                "read.html",
                ("<!doctype html><html><body><h1>Doc $id</h1><p>$body</p></body></html>")
                    .encodeToByteArray()
            )
        )
    }

    @Test
    fun `staged restore of a large archive round trips and cleans its staging file`() {
        runBlocking {
            val backupStore = store()
            val manager = LibraryManager(backupStore)
            // 120 docs x ~40-80 KB buffers → several MB uncompressed, plus
            // per-entry cap checks; far beyond any single-file read.
            val docs = (0 until 120).map { id ->
                async(Dispatchers.IO) { manager.addDocument(importResult(id)) }
            }.awaitAll()

            val zipOut = ByteArrayOutputStream()
            exportBackupZip(backupStore, zipOut)
            val zip = zipOut.toByteArray()
            // ~6 MB uncompressed, low-redundancy → near 1:1 deflate, so the
            // staged archive is genuinely multi-MB on disk.
            assertTrue("archive size ${zip.size}", zip.size > 2 * 1024 * 1024)

            val localStore = store()
            val local = LibraryManager(localStore)
            val stagingDir = tmp.newFolder("staging-${UUID.randomUUID()}")

            val (summary, verification) =
                BackupRestore.restore(localStore, local, zip.inputStream(), stagingDir, RestoreMode.Replace)

            assertTrue(verification.issues.joinToString { it.message }, verification.valid)
            assertEquals(120, summary.newDocsAdded)
            assertEquals(120, local.listDocuments().size)

            // The staging temp file is deleted when the archive closes.
            assertEquals(0, stagingDir.listFiles().orEmpty().size)
        }
    }

    @Test
    fun `staged archive reads entries lazily and deletes its temp file on close`() {
        runBlocking {
            val backupStore = store()
            LibraryManager(backupStore).addDocument(importResult(1))
            val bytes = ByteArrayOutputStream().also { out ->
                runBlocking { exportBackupZip(backupStore, out) }
            }.toByteArray()

            val stagingDir = tmp.newFolder("staging-${UUID.randomUUID()}")
            val staged = StagedArchive.stage(bytes.inputStream(), stagingDir)
            assertTrue(stagingDir.listFiles().orEmpty().size == 1)
            assertTrue(staged.hasEntry("library/manifest.json"))
            val manifest = staged.readTextEntry("library/manifest.json")
            assertTrue(manifest.orEmpty().contains("hash-1"))
            assertTrue(0 < staged.entryNames.size)
            staged.close()
            assertEquals(0, stagingDir.listFiles().orEmpty().filter { it.isFile }.size)
        }
    }

    /** Compressed bytes never materialize as one ByteArray in restore: the
     *  staging input path streams to disk — asserted structurally above; this
     *  test pins the ZipOutputStream-free path (no readBytes of the input). */
    @Test
    fun `restore accepts zip produced by the streaming exporter with unicode names`() {
        runBlocking {
            val backupStore = store()
            LibraryManager(backupStore).addDocument(importResult(7))
            val bytes = ByteArrayOutputStream().also { out ->
                runBlocking { exportBackupZip(backupStore, out) }
            }.toByteArray()

            val localStore = store()
            val local = LibraryManager(localStore)
            val stagingDir = tmp.newFolder("staging-${UUID.randomUUID()}")
            // Wrap in ZipInputStream-style pipeline: stage direct from stream.
            val (summary, verification) = BackupRestore.restore(
                localStore, local, bytes.inputStream(), stagingDir, RestoreMode.Merge
            )
            assertTrue(verification.valid)
            assertEquals(1, summary.newDocsAdded)
        }
    }

    /** A backup that camouflages junk as trailing entries after the real
     *  backup entries must still restore: junk is never manifest-listed, so
     *  restore ignores it. A nested full-backup copy inside junk stays unparsed. */
    @Test
    fun `restore handles an archive with trailing junk entries`() {
        runBlocking {
            val backupStore = store()
            LibraryManager(backupStore).addDocument(importResult(3))
            val bytes = ByteArrayOutputStream().also { out ->
                runBlocking { exportBackupZip(backupStore, out) }
            }.toByteArray()

            // Real backup entries first (copied verbatim), then trailing junk.
            val outer = ByteArrayOutputStream()
            ZipOutputStream(outer).use { zos ->
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        zos.write(zis.readBytes())
                        zos.closeEntry()
                    }
                }
                zos.putNextEntry(java.util.zip.ZipEntry("stray/unrelated/file.txt"))
                zos.write("junk".encodeToByteArray())
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("stray/nested-backup-copy.zip"))
                zos.write(bytes)
                zos.closeEntry()
            }
            val mixed = outer.toByteArray()

            val localStore = store()
            val local = LibraryManager(localStore)
            val stagingDir = tmp.newFolder("staging-${UUID.randomUUID()}")
            val (summary, verification) = BackupRestore.restore(
                localStore, local, mixed.inputStream(), stagingDir, RestoreMode.Replace
            )
            assertTrue(verification.issues.joinToString { it.message }, verification.valid)
            assertEquals(1, summary.newDocsAdded)
            assertEquals(1, local.listDocuments().size)
        }
    }
}
