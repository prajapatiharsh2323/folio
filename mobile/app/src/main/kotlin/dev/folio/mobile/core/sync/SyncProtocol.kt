package dev.folio.mobile.core.sync

import kotlinx.serialization.Serializable
import java.io.File

/**
 * The Drive mirror protocol, ported from packages/core/src/sync (PWA).
 *
 * The contract (spec §8, docs/decisions.md "Phase 6") is:
 *  - a Drive folder named "Folio Library" holds the library, file by file;
 *  - each mirrored file records its library-relative path and SHA-256 in the
 *    Drive `appProperties` (relPath / sha256), because Drive addresses files
 *    by opaque id, not by path;
 *  - the device keeps its own `sync/state.json` mapping path -> {driveId,
 *    sha256, remoteModifiedAt};
 *  - the manifest is uploaded LAST, so a partially synced library is never
 *    advertised as complete;
 *  - deletions never propagate (version-one rule).
 *
 * This is a byte-compatibility contract with the PWA: both apps mirror into
 * the same folder layout and understand each other's files.
 */

/** One file in the remote mirror. */
data class SyncFileEntry(
    /** Library-relative path, e.g. "library/manifest.json". */
    val path: String,
    val size: Long,
    val sha256: String,
    /** ISO-8601 modification time reported by the remote. */
    val modifiedAt: String,
    /** Remote file id (Drive id). */
    val id: String? = null,
)

/**
 * A remote store that can list, download and upload mirrored files.
 *
 * Implemented by [DriveSyncProvider] in production and by an in-memory fake in
 * the tests, so the sync algorithm can be exercised without a network.
 */
interface SyncProvider {

    /** Every file currently in the mirror, keyed by library-relative path. */
    suspend fun list(): List<SyncFileEntry>

    /** Contents of one mirrored file. */
    suspend fun download(path: String): ByteArray

    /** Uploads [data] as [path]; returns the remote file id. */
    suspend fun upload(path: String, data: ByteArray, sha256: String): String

    /**
     * Uploads a local file as [path] without loading it into memory. The
     * default implementation falls back to [upload]; stores that can hand out
     * a real file (the app-private filesystem) override it.
     */
    suspend fun uploadFile(path: String, file: File, sha256: String): String =
        upload(path, file.readBytes(), sha256)
}

/** One entry of the local sync state: what we know about a mirrored file. */
@Serializable
data class SyncStateItem(
    val driveId: String,
    val sha256: String,
    val remoteModifiedAt: String,
)

/**
 * The local sync state file (`sync/state.json`). Version 1 shape is fixed by
 * the PWA contract.
 */
@Serializable
data class SyncState(
    val version: Int = 1,
    val files: Map<String, SyncStateItem> = emptyMap(),
)

/** Progress of a running sync. */
data class SyncProgress(
    /** connecting | listing | downloading | uploading | merging | complete | error */
    val phase: String,
    val current: Int,
    val total: Int,
    val currentItem: String? = null,
    val message: String = "",
)

/** What a completed sync changed. */
data class SyncResult(
    var uploadedDocs: Int = 0,
    var downloadedDocs: Int = 0,
    var uploadedFiles: Int = 0,
    var downloadedFiles: Int = 0,
    var mergedUserFiles: Int = 0,
)
