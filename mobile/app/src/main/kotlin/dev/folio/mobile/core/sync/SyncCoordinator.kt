package dev.folio.mobile.core.sync

import dev.folio.mobile.core.backup.BackupRestore
import dev.folio.mobile.core.backup.RestoreMode
import dev.folio.mobile.core.backup.exportBackupZip
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.store.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Drives a cloud sync against a [CloudSyncStore].
 *
 * The coordinator owns the *local* half of the contract and knows nothing
 * about the transport:
 *  - upload: the library is streamed into a temp ZIP (one file at a time, the
 *    library never sits in RAM) and then handed to the store;
 *  - download: the store's stream is fed straight into [BackupRestore],
 *    which stages, verifies and merges/replaces under the library's file
 *    locks — the same path a local ZIP restore takes.
 *
 * Because both halves are the existing backup contract, a snapshot taken on
 * one device restores byte-identically on another and stays compatible with
 * the web app's backup format.
 */
class SyncCoordinator(
    private val store: CloudSyncStore,
    private val library: FileStore,
    /** Scratch directory for the temp ZIP and for the restore staging file. */
    private val stagingDir: File,
) {

    /**
     * Snapshots the local library and pushes it to the cloud.
     *
     * [onStage] reports ZIP packaging progress (current file, total files,
     * filename); [onProgress] reports upload progress in bytes. Both are
     * invoked from a background thread.
     */
    suspend fun upload(
        onStage: ((current: Int, total: Int, filename: String) -> Unit)? = null,
        onProgress: ((uploaded: Long, total: Long) -> Unit)? = null,
    ): CloudSyncOutcome = withContext(Dispatchers.IO) {
        val archive = try {
            File.createTempFile("folio-sync-", ".zip", stagingDir.apply { mkdirs() })
        } catch (t: Throwable) {
            return@withContext CloudSyncOutcome.Failed(t)
        }
        try {
            FileOutputStream(archive).use { out ->
                exportBackupZip(library, out, onStage)
                out.flush()
            }
            val info = store.upload(archive, onProgress)
            CloudSyncOutcome.Uploaded(info)
        } catch (t: Throwable) {
            CloudSyncOutcome.Failed(t)
        } finally {
            archive.delete()
        }
    }

    /**
     * Pulls the cloud snapshot down and applies it to the local library with
     * [mode] (merge keeps local data and lets newer remote state win;
     * replace wipes the library first).
     */
    suspend fun downloadAndRestore(mode: RestoreMode): CloudSyncOutcome =
        withContext(Dispatchers.IO) {
            try {
                val input = store.openDownload()
                    ?: return@withContext CloudSyncOutcome.NoRemoteBackup()
                input.use { stream ->
                    val (summary, verification) = BackupRestore.restore(
                        store = library,
                        manager = LibraryManager(library),
                        input = stream,
                        stagingDir = stagingDir,
                        mode = mode,
                    )
                    CloudSyncOutcome.Restored(summary, verification)
                }
            } catch (t: Throwable) {
                CloudSyncOutcome.Failed(t)
            }
        }

    /** Metadata about the cloud snapshot, or null when there is none. */
    suspend fun remoteInfo(): RemoteBackupInfo? = withContext(Dispatchers.IO) {
        runCatching { store.remoteInfo() }.getOrNull()
    }
}
