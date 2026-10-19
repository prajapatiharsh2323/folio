package dev.folio.mobile.core.sync

import dev.folio.mobile.core.backup.RestoreSummary
import dev.folio.mobile.core.backup.VerificationResult
import java.io.File
import java.io.InputStream

/**
 * Cloud sync contracts.
 *
 * Folio is offline-first: the device library is the source of truth and the
 * cloud is a *mirror* — one verified backup snapshot that can be pulled down
 * on another device. Because the mirror is exactly the schema-1 backup ZIP
 * the app already produces, syncing reuses the whole BackupZip/BackupRestore
 * pipeline instead of inventing a second storage format.
 *
 * The transport is abstracted behind [CloudSyncStore] so the sync logic can be
 * unit-tested without a network or a Google account (see FakeCloudSyncStore in
 * the test source set).
 */

/** Metadata about the snapshot currently held in the cloud. */
data class RemoteBackupInfo(
    /** Opaque transport id of the remote file (Drive file id for the Drive store). */
    val fileId: String,
    /** ISO-8601 modification timestamp of the remote snapshot, when the transport reports one. */
    val modifiedTime: String?,
    /** Size of the remote snapshot in bytes, when known. */
    val sizeBytes: Long,
)

/** Outcome of a sync operation. */
sealed interface CloudSyncOutcome {
    /** The local library was snapshotted and pushed to the cloud. */
    data class Uploaded(val info: RemoteBackupInfo) : CloudSyncOutcome

    /** The cloud snapshot was pulled down and applied to the local library. */
    data class Restored(
        val summary: RestoreSummary,
        val verification: VerificationResult,
    ) : CloudSyncOutcome

    /** There is no snapshot in the cloud yet (fresh account / first run). */
    data class NoRemoteBackup(val message: String = "No cloud backup yet") : CloudSyncOutcome

    /** The operation failed; the local library was left untouched. */
    data class Failed(val error: Throwable) : CloudSyncOutcome
}

/**
 * A remote store holding a single backup snapshot.
 *
 * Implementations must be safe to call from a background dispatcher and must
 * never leave the local library half-modified: the remote side is written
 * first, and the local side is only touched by the restore pipeline, which
 * verifies the archive before applying it.
 */
interface CloudSyncStore {

    /**
     * Uploads [archive] as the new cloud snapshot, replacing any previous one.
     *
     * [onProgress] receives (uploadedBytes, totalBytes) as the transfer
     * advances; it may be called from a background thread.
     */
    suspend fun upload(
        archive: File,
        onProgress: ((uploaded: Long, total: Long) -> Unit)? = null,
    ): RemoteBackupInfo

    /**
     * Opens a stream over the cloud snapshot, or returns null when no snapshot
     * exists. The caller owns the stream and must close it.
     */
    suspend fun openDownload(): InputStream?

    /** Metadata about the cloud snapshot, or null when no snapshot exists. */
    suspend fun remoteInfo(): RemoteBackupInfo?
}
