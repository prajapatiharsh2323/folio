package dev.folio.mobile.core.sync

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * In-memory [CloudSyncStore] used by the sync tests: the "cloud" is a single
 * byte array on the test JVM, so upload/download/restore can be exercised
 * end-to-end without a network, a Google account or a Drive client.
 */
class FakeCloudSyncStore(
    private var payload: ByteArray? = null,
    /** When set, every operation throws it — used to test failure handling. */
    var failWith: Throwable? = null,
) : CloudSyncStore {

    var uploadCount: Int = 0
        private set

    var lastUploadedSize: Long = 0
        private set

    /** Progress reported by the last upload, as (uploaded, total) pairs. */
    val progressEvents = mutableListOf<Pair<Long, Long>>()

    override suspend fun upload(
        archive: File,
        onProgress: ((uploaded: Long, total: Long) -> Unit)?,
    ): RemoteBackupInfo {
        failWith?.let { throw it }
        uploadCount++
        val total = archive.length()
        onProgress?.invoke(0L, total)
        payload = archive.readBytes()
        lastUploadedSize = total
        onProgress?.invoke(total, total)
        progressEvents.add(0L to total)
        progressEvents.add(total to total)
        return RemoteBackupInfo(
            fileId = "fake-1",
            modifiedTime = "2026-10-19T00:00:00Z",
            sizeBytes = total,
        )
    }

    override suspend fun openDownload(): InputStream? {
        failWith?.let { throw it }
        val bytes = payload ?: return null
        return ByteArrayInputStream(bytes)
    }

    override suspend fun remoteInfo(): RemoteBackupInfo? {
        failWith?.let { throw it }
        val bytes = payload ?: return null
        return RemoteBackupInfo(
            fileId = "fake-1",
            modifiedTime = "2026-10-19T00:00:00Z",
            sizeBytes = bytes.size.toLong(),
        )
    }

    /** The raw snapshot currently held by the fake cloud. */
    fun snapshot(): ByteArray? = payload
}
