package dev.folio.mobile.core.sync

import java.io.File

/**
 * In-memory [SyncProvider]: the "cloud" is a map of path -> bytes.
 *
 * Lets the sync algorithm be tested exactly as the PWA tests it (two
 * devices converging on one mirror) without a network or a Google account.
 */
class FakeSyncProvider : SyncProvider {

    private val files = LinkedHashMap<String, ByteArray>()
    private val hashes = HashMap<String, String>()
    private var nextId = 1

    /** Number of upload calls, for assertions. */
    var uploadCount: Int = 0
        private set

    /** Paths currently held by the fake cloud. */
    fun paths(): Set<String> = files.keys.toSet()

    fun contains(path: String): Boolean = files.containsKey(path)

    fun bytesOf(path: String): ByteArray? = files[path]

    override suspend fun list(): List<SyncFileEntry> =
        files.map { (path, bytes) ->
            SyncFileEntry(
                path = path,
                size = bytes.size.toLong(),
                sha256 = hashes[path] ?: "",
                modifiedAt = "2026-10-19T00:00:00Z",
                id = "fake-${path.hashCode()}",
            )
        }

    override suspend fun download(path: String): ByteArray =
        files[path] ?: throw IllegalStateException("File not found in Drive: $path")

    override suspend fun upload(path: String, data: ByteArray, sha256: String): String {
        uploadCount++
        files[path] = data
        hashes[path] = sha256
        return "fake-${nextId++}"
    }

    override suspend fun uploadFile(path: String, file: File, sha256: String): String =
        upload(path, file.readBytes(), sha256)
}
