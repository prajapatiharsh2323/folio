package dev.folio.mobile.core.auditscratch

// ============================================================================
// AUDIT-ONLY SCRATCH TESTS — AuditScratchValidationTest.kt
// Temporary validation artifacts for FOLIO_EPUB_READER_BUG_AUDIT.md.
// NOT part of the application test suite; deleted before the audit deliverable.
// They do not modify any production file; they only exercise production APIs.
// ============================================================================

import dev.folio.mobile.core.backup.BackupRestore
import dev.folio.mobile.core.backup.RestoreMode
import dev.folio.mobile.core.backup.exportBackupZip
import dev.folio.mobile.core.backup.unzipAndVerifyBackup
import dev.folio.mobile.core.backup.StagedArchive
import dev.folio.mobile.core.importer.DetectedFormat
import dev.folio.mobile.core.importer.FileDetector
import dev.folio.mobile.core.importer.ImportOriginal
import dev.folio.mobile.core.importer.ImportReading
import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.importer.SearchChapterText
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.search.SearchService
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.store.DocPrefsStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AuditScratchValidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AppFileStore(tmp.newFolder("lib-${UUID.randomUUID()}"))

    private fun metaDoc(id: String, title: String) = DocMeta(
        id = id,
        slug = "$id-slug",
        title = title,
        author = "A-$id",
        kind = DocKind.Book,
        capturedAt = "2026-10-19T00:00:00Z",
        importedAt = "2026-10-19T00:00:00Z",
        contentHash = "hash-$id",
        sourceFormat = SourceFormat.Txt
    )

    private fun importResult(id: String, title: String) = ImportResult(
        meta = metaDoc(id, title),
        original = ImportOriginal("book.txt", "original".encodeToByteArray()),
        reading = ImportReading(
            "book.epub",
            "<!doctype html><html><body><p>stardust corpus text $id</p></body></html>"
                .encodeToByteArray()
        ),
        searchText = listOf(
            SearchChapterText("ch-1", title, "stardust corpus text for $id")
        )
    )

    private fun zipOf(store: AppFileStore): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { exportBackupZip(store, out) }
        return out.toByteArray()
    }

    /**
     * SCRATCH-A: SearchService index after a Replace-restore.
     */
    @Test
    fun scratch_restoreThenSearchCorpus() = runBlocking {
        val backupStore = store()
        val backupManager = LibraryManager(backupStore)
        backupManager.addDocument(importResult("d1", "Atlas of stardust"))

        val zip = zipOf(backupStore)

        val targetStore = store()
        val target = LibraryManager(targetStore)
        val (summary, verification) = BackupRestore.restore(
            targetStore, target, zip.inputStream(),
            tmp.newFolder("stage-${UUID.randomUUID()}"), RestoreMode.Replace
        )
        assertTrue(verification.issues.joinToString { it.message }, verification.valid)
        assertEquals(1, summary.newDocsAdded)
        assertEquals(1, target.listDocuments().size)

        val session = SearchService(target).build()
        val hits = session.search("stardust")
        assertTrue("Restored doc must still be searchable (hits=$hits)", hits.isNotEmpty())
    }

    /**
     * SCRATCH-B: restore tolerance for a manifest-listed reading file missing
     * from the archive ("missing_in_archive" is a non-fatal issue).
     */
    @Test
    fun scratch_restoreMissingDocFileIsRegisteredThenUnopenable() = runBlocking {
        val backupStore = store()
        val backupManager = LibraryManager(backupStore)
        val meta = backupManager.addDocument(importResult("d1", "Atlas"))

        val readingPath = "library/docs/${meta.slug}/reading/book.epub"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            StagedArchive.stage(
                ByteArrayInputStream(zipOf(backupStore)),
                tmp.newFolder("tmp-stage-${UUID.randomUUID()}")
            ).use { staged ->
                for (name in staged.entryNames) {
                    if (name == readingPath) continue
                    val bytes = staged.readEntry(name) ?: continue
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        val pruned = bos.toByteArray()

        val verification = StagedArchive.stage(
            ByteArrayInputStream(pruned), tmp.newFolder("stage-verify-${UUID.randomUUID()}")
        ).use { unzipAndVerifyBackup(it) }
        assertTrue(
            "expected a missing_in_archive issue (${verification.issues})",
            verification.issues.any { it.reason == "missing_in_archive" && it.path == readingPath }
        )
        assertFalse(verification.valid)

        val targetStore = store()
        val target = LibraryManager(targetStore)
        val (summary, _) = BackupRestore.restore(
            targetStore, target, ByteArrayInputStream(pruned),
            tmp.newFolder("stage-rst-${UUID.randomUUID()}"), RestoreMode.Replace
        )
        assertEquals(1, summary.newDocsAdded)
        assertEquals(1, target.listDocuments().size)
        val damaged = target.damagedDocuments()
        assertEquals("damaged scan must stay empty for readable meta", 0, damaged.size)
    }

    /**
     * SCRATCH-C: unbounded decompression in EpubImporter.unzip()'s read path
     * (zip.readBytes() per entry, no cap) — a tiny archive expands past 300 MB;
     * inflation is aborted there to keep the JVM below OOM.
     */
    @Test
    fun scratch_epubUnzipUnbounded() {
        val zeros = ByteArray(1 shl 20)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            val e = ZipEntry("OEBPS/ch1.xhtml")
            e.method = ZipEntry.DEFLATED
            zos.putNextEntry(e)
            repeat(700) { zos.write(zeros) }
            zos.closeEntry()
        }
        val bomb = out.toByteArray()
        assertTrue("bomb should stay tiny, got ${bomb.size}", bomb.size < (1 shl 21))

        val zis = java.util.zip.ZipInputStream(ByteArrayInputStream(bomb))
        val next = zis.nextEntry
        assertTrue(next != null)
        var expanded = 0L
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = zis.read(buf)
            if (n < 0) break
            expanded += n
            if (expanded >= 300L * 1024 * 1024) break
        }
        zis.close()
        assertTrue(
            "expandable past the 300 MB cap from a ${bomb.size} byte archive",
            expanded >= 300L * 1024 * 1024
        )
    }

    /**
     * SCRATCH-D: FileDetector content sniffing — TXT mentioning "<html"
     * classified as HTML.
     */
    @Test
    fun scratch_txtMentioningHtmlBecomesHtml() {
        val txt = """
            Chapter 12: On Snippet Formatting

            Note for translators: keep the literal sample intact,
            e.g. print("<html>") as text, not as a page.
        """.trimIndent()
        assertEquals(DetectedFormat.Html, FileDetector.detectFileFormat(txt.encodeToByteArray()))
    }

    /**
     * SCRATCH-E: doc-settings.json read-modify-write race across two
     * DocPrefsStore instances of the same root (no mutex on set()).
     * On a Windows host concurrent writers can also surface as a hard
     * AccessDeniedException from the unguarded rename; on-device (POSIX
     * rename) they degrade to silent lost updates. Either mode is a defect;
     * the assertion demands "all writes succeed AND all keys survive".
     */
    @Test
    fun scratch_docPrefsStoreConcurrentSetLosesUpdates() = runBlocking {
        val store = store()
        val perInstance = 60
        val keys: List<String> = (0 until perInstance).flatMap { i ->
            listOf("doc-a-$i", "doc-b-$i")
        }
        var failures = 0
        coroutineScope {
            val outcomes = keys.map { key ->
                async(Dispatchers.IO) {
                    val ok = runCatching {
                        DocPrefsStore(store).set(key, ReadingPrefs(fontSize = 20.0))
                    }.isSuccess
                    if (!ok) failures += 1
                }
            }
            outcomes.awaitAll()
        }

        val mapSer = MapSerializer(String.serializer(), ReadingPrefs.serializer())
        val text = store.readText(DocPrefsStore.DOC_SETTINGS_PATH)
        val map = Json { ignoreUnknownKeys = true }.decodeFromString(mapSer, text)
        val missing = keys.filter { it !in map }
        assertTrue(
            "all writes must succeed and every key must survive " +
                "(failures=$failures, missing keys=${missing.size}: ${missing.take(5)})",
            failures == 0 && missing.isEmpty()
        )
    }

    /**
     * SCRATCH-F: Replace-restore cannot wipe library/docs, library/user or
     * library/index — FileStore.exists() is isFile-based and returns false for
     * DIRECTORY paths, so BackupRestore's `if (store.exists(X)) remove(X)`
     * checks skip every wipe.
     * Consequences demonstrated below: the previous library's doc folders and
     * per-book doc-settings survive a "wipe first" restore, and the stale doc
     * folders then resurface as "interrupted imports" orphans in the UI.
     */
    @Test
    fun scratch_replaceRestoreWipeIsANoOp() = runBlocking {
        val backupStore = store()
        LibraryManager(backupStore).addDocument(importResult("d1", "Atlas"))
        val zip = zipOf(backupStore)

        // Local library: one doc + a doc-settings override.
        val targetStore = store()
        val targetManager = LibraryManager(targetStore)
        val localDoc = targetManager.addDocument(importResult("d2", "Local secret book"))
        val targetPrefs = DocPrefsStore(targetStore)
        targetPrefs.set("doc-a", ReadingPrefs(fontSize = 30.0))

        BackupRestore.restore(
            targetStore, targetManager, zip.inputStream(),
            tmp.newFolder("stage-${UUID.randomUUID()}"), RestoreMode.Replace
        )

        // 1. The wiped-then-restored library should contain exactly the backup:
        //    manifest only has d1 (listDocuments is manifest-driven)...
        val docs = targetManager.listDocuments()
        assertEquals("manifest says only the backup doc remains", 1, docs.size)

        // 2. ...but the previous doc's FILES are still on disk (wipe no-op).
        val stalePath = "library/docs/${localDoc.slug}/meta.json"
        assertTrue(
            "Replace-restore must erase the old doc folder; $stalePath still exists",
            !targetStore.exists(stalePath)
        )

        // 3. Per-book doc-settings survive the wipe too.
        assertEquals(
            "Replace must drop doc-settings.json per its wipe of library/user",
            null, targetPrefs.get("doc-a")
        )

        // 4. Stale doc folders resurface as interrupted-import orphans in the UI.
        val orphans = targetManager.scanInterruptedImports()
        assertTrue(
            "stale folder should not resurface as an orphan after Replace (got $orphans)",
            orphans.none { it.slug == localDoc.slug }
        )
    }

    /** Unused in assertions; silence Kotlin warnings about the Progress import. */
    @Suppress("unused")
    private fun unused(progress: Progress) {}
}
