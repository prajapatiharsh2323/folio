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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Size-limit hardening tests (S1 audit §6.1): every over-limit abort happens
 * on REAL decompressed bytes while they stream — never after the entry has
 * already been buffered — and a failed Always-Replace keeps the local library
 * byte-for-byte unchanged.
 *
 * Production caps (512 MB entry / 1 GB total) are too large for fast specs,
 * so most asserts run with [BackupLimitsSpec] injected at byte scale. One
 * test exercises the REAL 512 MB entry cap with a deflated zero-bomb, which
 * streams through 64 KiB chunks and never reaches that size on the heap.
 */
class BackupZipLimitsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AppFileStore(tmp.newFolder("root-${UUID.randomUUID()}"))

    private fun importResult(id: Int): ImportResult = ImportResult(
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
        original = ImportOriginal("book.txt", ByteArray(48 * 1024)),
        reading = ImportReading(
            "read.html",
            ("<!doctype html><html><body><h1>Doc $id</h1><p>chapter body</p></body></html>")
                .encodeToByteArray()
        )
    )

    /** A real Folio backup ZIP (1 doc), exported through the streaming export. */
    private fun realBackupBytes(): ByteArray {
        val backupStore = store()
        val manager = LibraryManager(backupStore)
        runBlocking { manager.addDocument(importResult(1)) }
        val out = ByteArrayOutputStream()
        runBlocking { exportBackupZip(backupStore, out) }
        return out.toByteArray()
    }

    /** Copies [source] and appends [extra] entries (used for hostile junk). */
    private fun withExtraEntries(source: ByteArray, extra: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(source.inputStream()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    zos.putNextEntry(ZipEntry(entry.name))
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        if (n > 0) zos.write(buf, 0, n)
                    }
                    zos.closeEntry()
                }
            }
            for ((name, bytes) in extra) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // StagedArchive streaming caps (count-on-arrival)
    // ------------------------------------------------------------------

    @Test
    fun `readEntryStreamed aborts mid stream when the cap is under the real entry`() = runBlocking {
        val staged = StagedArchive.stage(realBackupBytes().inputStream(), tmp.newFolder("s1"))
        // Resolve the original book's real archive path from the manifest.
        val bookPath = parseManifest(staged.readTextEntry("library/manifest.json")!!)
            .docs.first().files.first { it.path.contains("/original/") }.path
        val realSize = staged.readEntry(bookPath)!!.size
        assertTrue("test needs a multi-chunk entry", realSize > 2)
        // quality: count what arrives, not what the header claims — a cap one
        // byte under the truth must abort during decomposition, mid-stream.
        try {
            staged.readEntryStreamed(bookPath, realSize - 1L) { _, _ -> }
            fail("reading past the limit must throw EntryOversizeException")
        } catch (expected: EntryOversizeException) {
            assertEquals(bookPath, expected.entryName)
        }
        // At the exact limit the full bytes stream through and count right.
        var seen = 0
        val counted = staged.readEntryStreamed(bookPath, realSize.toLong()) { _, len -> seen += len }
        assertEquals(realSize.toLong(), counted)
        assertEquals(realSize.toLong(), seen.toLong())
        staged.close()
    }

    @Test
    fun `readEntryLimited equals readEntry for entries under the cap`() = runBlocking {
        val staged = StagedArchive.stage(realBackupBytes().inputStream(), tmp.newFolder("s2"))
        val direct = staged.readEntry("library/manifest.json")!!
        val limited = staged.readEntryLimited("library/manifest.json", BackupLimits.MAX_METADATA_UNCOMPRESSED)
        assertNotNull(limited)
        assertTrue(direct.contentEquals(limited))
        staged.close()
    }

    // ------------------------------------------------------------------
    // Verification aborts (injected byte-scale limits)
    // ------------------------------------------------------------------

    @Test
    fun `verification refuses an archive with an oversized single entry`() {
        val big = ByteArray(4 * 1024) // junk expands "past" the injected cap
        val archive = withExtraEntries(realBackupBytes(), listOf("hostile/big-blob.bin" to big))
        val result = unzipAndVerifyBackup(reStage(archive), BackupLimitsSpec(
            maxEntries = BackupLimits.MAX_ENTRIES,
            maxTotalUncompressed = BackupLimits.MAX_TOTAL_UNCOMPRESSED,
            maxEntryUncompressed = 1024L,
            maxMetadataUncompressed = BackupLimits.MAX_METADATA_UNCOMPRESSED,
            maxUserStateUncompressed = BackupLimits.MAX_USER_STATE_UNCOMPRESSED,
        ))
        assertTrue("oversized entry must invalidate the archive", !result.valid)
        assertTrue(
            "issue must report the oversized entry, got: ${result.issues.map { it.message }}",
            result.issues.any { it.reason == "corrupted" && it.message.contains("expands past") }
        )
    }

    @Test
    fun `verification refuses an archive whose total exceeds the total cap`() {
        val archive = withExtraEntries(realBackupBytes(), listOf(
            "hostile/one.bin" to ByteArray(2048),
            "hostile/two.bin" to ByteArray(2048),
            "hostile/three.bin" to ByteArray(2048),
        ))
        val result = unzipAndVerifyBackup(reStage(archive), BackupLimitsSpec(
            maxEntries = BackupLimits.MAX_ENTRIES,
            maxTotalUncompressed = 4096L, // 8 KB of junk vs a 4 KB total cap
            maxEntryUncompressed = BackupLimits.MAX_ENTRY_UNCOMPRESSED,
            maxMetadataUncompressed = BackupLimits.MAX_METADATA_UNCOMPRESSED,
            maxUserStateUncompressed = BackupLimits.MAX_USER_STATE_UNCOMPRESSED,
        ))
        assertTrue(!result.valid)
        assertTrue(
            "issue must report the TOTAL cap, got: ${result.issues.map { it.message }}",
            result.issues.any { it.reason == "corrupted" && it.message.contains("maximum uncompressed size") }
        )
    }

    @Test
    fun `verification refuses metadata entries over their own cap`() {
        val result = unzipAndVerifyBackup(reStage(realBackupBytes()), BackupLimitsSpec(
            maxEntries = BackupLimits.MAX_ENTRIES,
            maxTotalUncompressed = BackupLimits.MAX_TOTAL_UNCOMPRESSED,
            maxEntryUncompressed = BackupLimits.MAX_ENTRY_UNCOMPRESSED,
            maxMetadataUncompressed = 256L, // real manifest is well over 256 bytes
            maxUserStateUncompressed = BackupLimits.MAX_USER_STATE_UNCOMPRESSED,
        ))
        assertTrue(!result.valid)
        assertTrue(
            "issue must report the oversized metadata entry, got: ${result.issues.map { it.message }}",
            result.issues.any { it.reason == "corrupted" && it.message.contains("expands past") }
        )
    }

    @Test
    fun `restore refuses an over limit backup and keeps the local library untouched`() = runBlocking {
        val localStore = store()
        val local = LibraryManager(localStore)
        local.addDocument(importResult(9))
        val manifestBefore = localStore.readText(dev.folio.mobile.core.library.MANIFEST_PATH)
        val originalPath = dev.folio.mobile.core.backup.parseManifest(manifestBefore)
            .docs.first().files.first { it.path.contains("/original/") }.path
        val originalBefore = localStore.read(originalPath)
        val docsBefore = local.listDocuments().size

        // Hostile backup: same real archive plus 4 KB of junk with an injected
        // 1 KB per-entry cap — verification must fail BEFORE anything local moves.
        val hostile = withExtraEntries(realBackupBytes(), listOf("hostile/big.bin" to ByteArray(4 * 1024)))
        val stagingDir = tmp.newFolder("staging-${UUID.randomUUID()}")
        try {
            BackupRestore.restore(
                localStore, local, hostile.inputStream(), stagingDir,
                RestoreMode.Replace,
                BackupLimitsSpec(
                    maxEntries = BackupLimits.MAX_ENTRIES,
                    maxTotalUncompressed = BackupLimits.MAX_TOTAL_UNCOMPRESSED,
                    maxEntryUncompressed = 1024L,
                    maxMetadataUncompressed = BackupLimits.MAX_METADATA_UNCOMPRESSED,
                    maxUserStateUncompressed = BackupLimits.MAX_USER_STATE_UNCOMPRESSED,
                )
            )
            fail("an over-limit backup must be refused, not restored")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("failed verification"))
        }

        // The local library is byte-for-byte untouched.
        assertEquals(docsBefore, local.listDocuments().size)
        assertEquals(manifestBefore, localStore.readText(dev.folio.mobile.core.library.MANIFEST_PATH))
        assertTrue(
            "the original book file must be byte-identical after the refused restore",
            originalBefore.contentEquals(localStore.read(originalPath))
        )
    }

    @Test
    fun `a real half gigabyte zero bomb aborts at production caps without heap growth`() {
        // 640 MB of zeros deflates to ~700 KB on disk but 640 MB uncompressed —
        // over the 512 MB per-entry production cap. Verification counts what
        // arrives in 64 KiB chunks and refuses before the entry could exist as
        // one ByteArray. The zeros are written chunk-wise so even the TEST
        // never materializes them.
        val bomb = withStreamedZeroEntry(realBackupBytes(), "hostile/zero-bomb.bin", 640L * 1024 * 1024)
        val result = unzipAndVerifyBackup(reStage(bomb), BackupLimitsSpec.DEFAULT)
        assertTrue("zero bomb must invalidate the archive", !result.valid)
        assertTrue(
            "issue must report the oversized entry, got: ${result.issues.map { it.message }}",
            result.issues.any { it.reason == "corrupted" && it.message.contains("expands past") }
        )
    }

    /** Copies [source] and appends one entry of [totalSize] zero bytes, written
     *  chunk-wise (deflated to a few KB on disk, never one big heap array). */
    private fun withStreamedZeroEntry(source: ByteArray, name: String, totalSize: Long): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(source.inputStream()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    zos.putNextEntry(ZipEntry(entry.name))
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        if (n > 0) zos.write(buf, 0, n)
                    }
                    zos.closeEntry()
                }
            }
            zos.putNextEntry(ZipEntry(name))
            val zeros = ByteArray(64 * 1024)
            var written = 0L
            while (written < totalSize) {
                zos.write(zeros)
                written += zeros.size
            }
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    /** Stages a fresh [StagedArchive] for the archive bytes (own temp file). */
    private fun reStage(bytes: ByteArray): StagedArchive =
        StagedArchive.stage(bytes.inputStream(), tmp.newFolder("stage-${UUID.randomUUID()}"))
}
