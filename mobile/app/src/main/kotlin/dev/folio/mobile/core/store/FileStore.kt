package dev.folio.mobile.core.store

import java.io.File

/**
 * Direct Kotlin port of packages/core/src/store/file-store.ts.
 *
 * Paths are library-relative POSIX (e.g. "library/manifest.json"), never absolute,
 * and must never contain ".." segments — implementations enforce this.
 *
 * Kotlin note: implementations do blocking IO; callers must invoke from
 * Dispatchers.IO (suspend functions here make that the natural call pattern).
 */
data class FileStat(val size: Long, val modifiedAt: Long)

interface FileStore {
    /**
     * Stable key naming the storage root this instance addresses. Locking
     * registries (LibraryManager) key shared mutexes on [rootKey]+"<mutex>"
     * so two instances of the same tree serialize against each other,
     * while instances over different roots (tests, multi-store callers)
     * stay independent.
     */
    val rootKey: String

    suspend fun read(path: String): ByteArray
    suspend fun readText(path: String): String

    /** Atomic: write a temp file in the same directory, fsync, then rename over. */
    suspend fun write(path: String, data: ByteArray)
    suspend fun write(path: String, data: String)
    suspend fun exists(path: String): Boolean

    /** Names of entries directly inside [dir] (not recursive, not full paths). */
    suspend fun list(dir: String): List<String>
    suspend fun stat(path: String): FileStat
    suspend fun remove(path: String, recursive: Boolean = false)

    /**
     * Renames a file or a whole directory tree within the same library root,
     * atomically where the backing filesystem allows it. Backs failure-atomic
     * backup restores (staging tree → live swap, audit P0-03). Stores that
     * cannot rename throw [UnsupportedOperationException]; [restore] falls
     * back to plain copy writes only in write paths that tolerate it.
     */
    suspend fun rename(from: String, to: String) {
        throw UnsupportedOperationException("rename not supported by this FileStore")
    }

    /**
     * Direct file access for a library path, when the backing store can hand
     * out a real file (the app-private filesystem). Null for stores that
     * cannot — callers fall back to [read].
     *
     * Used to stream a large file to the network without pulling it into the
     * heap first.
     */
    fun localFile(path: String): File? = null
}
