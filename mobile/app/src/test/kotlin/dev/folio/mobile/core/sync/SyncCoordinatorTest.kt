package dev.folio.mobile.core.sync

import dev.folio.mobile.core.backup.RestoreMode
import dev.folio.mobile.core.backup.StagedArchive
import dev.folio.mobile.core.backup.unzipAndVerifyBackup
import dev.folio.mobile.core.importer.ImportOriginal
import dev.folio.mobile.core.importer.ImportReading
import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.importer.SearchChapterText
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.store.AppFileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Cloud sync behaviour, exercised against an in-memory transport.
 *
 * These tests cover the part that actually matters for the user: what leaves
 * the device, what comes back, and what happens to the local library when the
 * network or the cloud misbehaves. The Drive transport itself is a thin
 * adapter over these same contracts.
 */
class SyncCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(label: String) =
        AppFileStore(tmp.newFolder("$label-${UUID.randomUUID()}"))

    private fun doc(
        id: String,
        slug: String,
        hash: String,
        title: String = "T-$id",
        updatedAt: String = "2026-10-19T00:00:00Z",
    ) = DocMeta(
        id = id,
        slug = slug,
        title = title,
        author = "Author $id",
        kind = DocKind.Book,
        capturedAt = "2026-10-19T00:00:00Z",
        importedAt = updatedAt,
        contentHash = hash,
        sourceFormat = SourceFormat.Txt,
    )

    private fun importResult(meta: DocMeta, body: String) = ImportResult(
        meta = meta,
        original = ImportOriginal("book.txt", ("original-$body").encodeToByteArray()),
        reading = ImportReading(
            "read.html",
            ("<!doctype html><html><body><h1>${meta.title}</h1><p>chapter $body</p></body></html>")
                .encodeToByteArray(),
        ),
        searchText = listOf(SearchChapterText("ch-1", meta.title, "x $body y")),
    )

    private fun seed(store: AppFileStore, meta: DocMeta, body: String) = runBlocking {
        LibraryManager(store).addDocument(importResult(meta, body))
    }

    private fun docs(store: AppFileStore) = runBlocking {
        LibraryManager(store).listDocuments()
    }

    private fun coordinator(store: AppFileStore, cloud: CloudSyncStore) = SyncCoordinator(
        store = cloud,
        library = store,
        stagingDir = tmp.newFolder("stage-${UUID.randomUUID()}"),
    )

    @Test
    fun `upload pushes a verifiable snapshot to the cloud`() {
        val device = store("device-a")
        seed(device, doc("d1", "first-book", "hash-1"), "one")
        seed(device, doc("d2", "second-book", "hash-2"), "two")

        val cloud = FakeCloudSyncStore()
        val outcome = runBlocking { coordinator(device, cloud).upload() }

        assertTrue("upload failed: $outcome", outcome is CloudSyncOutcome.Uploaded)
        assertEquals(1, cloud.uploadCount)

        val snapshot = cloud.snapshot()
        assertNotNull(snapshot)
        StagedArchive.stage(snapshot!!.inputStream(), tmp.newFolder("verify")).use { staged ->
            val verification = unzipAndVerifyBackup(staged)
            assertTrue(
                "snapshot failed verification: " +
                    verification.issues.joinToString { it.message },
                verification.valid,
            )
            assertEquals(2, verification.backupMeta?.docCount)
        }
    }

    @Test
    fun `upload reports progress and cleans up its temporary archive`() {
        val device = store("device-a")
        seed(device, doc("d1", "first-book", "hash-1"), "one")

        val cloud = FakeCloudSyncStore()
        val staging = tmp.newFolder("stage-progress")
        val coordinator = SyncCoordinator(
            store = cloud,
            library = device,
            stagingDir = staging,
        )
        val stages = mutableListOf<String>()
        val outcome = runBlocking {
            coordinator.upload(onStage = { _, _, filename -> stages.add(filename) })
        }

        assertTrue(outcome is CloudSyncOutcome.Uploaded)
        assertTrue("no packaging progress reported", stages.isNotEmpty())
        assertEquals(2, cloud.progressEvents.size)
        assertEquals(cloud.lastUploadedSize, cloud.progressEvents.last().second)

        // The temp archive is deleted again — the staging dir stays empty.
        assertEquals(0, staging.listFiles()?.size ?: 0)
    }

    @Test
    fun `restore pulls the snapshot onto a fresh device`() {
        val source = store("device-a")
        seed(source, doc("d1", "first-book", "hash-1"), "one")
        seed(source, doc("d2", "second-book", "hash-2"), "two")

        val cloud = FakeCloudSyncStore()
        runBlocking { coordinator(source, cloud).upload() }

        val target = store("device-b")
        val outcome = runBlocking { coordinator(target, cloud).downloadAndRestore(RestoreMode.Merge) }

        assertTrue("restore failed: $outcome", outcome is CloudSyncOutcome.Restored)
        val restored = outcome as CloudSyncOutcome.Restored
        assertTrue(restored.verification.valid)
        assertEquals(2, restored.summary.newDocsAdded)
        assertEquals(2, docs(target).size)
        assertEquals(
            setOf("T-d1", "T-d2"),
            docs(target).map { it.title }.toSet(),
        )
    }

    @Test
    fun `merge keeps local books and adds the ones missing locally`() {
        val source = store("device-a")
        seed(source, doc("d1", "first-book", "hash-1"), "one")

        val cloud = FakeCloudSyncStore()
        runBlocking { coordinator(source, cloud).upload() }

        val target = store("device-b")
        seed(target, doc("d9", "local-only", "hash-9"), "local")

        val outcome = runBlocking { coordinator(target, cloud).downloadAndRestore(RestoreMode.Merge) }

        assertTrue(outcome is CloudSyncOutcome.Restored)
        assertEquals(1, (outcome as CloudSyncOutcome.Restored).summary.newDocsAdded)
        assertEquals(2, docs(target).size)
    }

    @Test
    fun `replace restores exactly the cloud copy`() {
        val source = store("device-a")
        seed(source, doc("d1", "first-book", "hash-1"), "one")

        val cloud = FakeCloudSyncStore()
        runBlocking { coordinator(source, cloud).upload() }

        val target = store("device-b")
        seed(target, doc("d9", "local-only", "hash-9"), "local")

        val outcome = runBlocking { coordinator(target, cloud).downloadAndRestore(RestoreMode.Replace) }

        assertTrue(outcome is CloudSyncOutcome.Restored)
        assertEquals(listOf("T-d1"), docs(target).map { it.title })
    }

    @Test
    fun `restoring with no cloud copy reports that there is nothing to restore`() {
        val device = store("device-a")
        val cloud = FakeCloudSyncStore()
        val outcome = runBlocking {
            coordinator(device, cloud).downloadAndRestore(RestoreMode.Merge)
        }
        assertTrue(outcome is CloudSyncOutcome.NoRemoteBackup)
    }

    @Test
    fun `a transport failure leaves the local library untouched`() {
        val device = store("device-a")
        seed(device, doc("d1", "first-book", "hash-1"), "one")

        val cloud = FakeCloudSyncStore(failWith = IllegalStateException("network down"))
        val outcome = runBlocking { coordinator(device, cloud).upload() }

        assertTrue(outcome is CloudSyncOutcome.Failed)
        assertEquals("network down", (outcome as CloudSyncOutcome.Failed).error.message)
        assertEquals(1, docs(device).size)
    }

    @Test
    fun `remote info is null until a snapshot exists`() {
        val device = store("device-a")
        val cloud = FakeCloudSyncStore()
        assertEquals(null, runBlocking { coordinator(device, cloud).remoteInfo() })

        seed(device, doc("d1", "first-book", "hash-1"), "one")
        runBlocking { coordinator(device, cloud).upload() }

        val info = runBlocking { coordinator(device, cloud).remoteInfo() }
        assertNotNull(info)
        assertTrue(info!!.sizeBytes > 0)
    }
}
