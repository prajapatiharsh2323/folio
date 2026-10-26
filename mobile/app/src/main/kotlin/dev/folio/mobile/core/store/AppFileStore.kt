package dev.folio.mobile.core.store

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.UUID

/**
 * FileStore over the app-private filesystem (context.filesDir on Android).
 *
 * Robustness rules (better than the PWA's OpfsStore, same interface):
 *  - Every path is validated: no ".." segments, no leading "/", no Windows drive
 *    letters, no empty paths.
 *  - Every write is atomic: sibling temp file + fsync + atomic rename, so a process
 *    kill can never leave a torn file at the target path.
 *  - The root is injectable (constructor param) so unit tests run on a plain
 *    temp directory without Android.
 */
class AppFileStore(private val root: File) : FileStore {

    /** Absolute root path; identity of the tree this store addresses (locking scope). */
    override val rootKey: String = root.absolutePath

    private val tmpRandom = SecureRandom()

    init {
        require(root.exists() || root.mkdirs() || root.isDirectory) {
            "AppFileStore root not usable: ${root.absolutePath}"
        }
    }

    /** Resolves a library-relative path to a File, rejecting traversal attacks. */
    fun resolve(path: String): File {
        if (path.isBlank()) throw IllegalArgumentException("Path cannot be empty")
        if (path.startsWith("/") || path.startsWith("\\")) {
            throw SecurityException("Absolute paths are not permitted: $path")
        }
        if (path.contains(Regex("^[A-Za-z]:"))) {
            throw SecurityException("Windows drive-letter paths are not permitted: $path")
        }
        val segments = path.split('/', '\\')
            .filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) {
            throw SecurityException("Path traversal is not permitted: $path")
        }
        var resolved = root
        for (segment in segments) {
            resolved = File(resolved, segment)
        }
        return resolved
    }

    override suspend fun read(path: String): ByteArray {
        val file = resolve(path)
        if (!file.isFile) throw java.io.FileNotFoundException("File not found: $path")
        return file.readBytes()
    }

    override suspend fun readText(path: String): String =
        read(path).toString(Charsets.UTF_8)

    override suspend fun write(path: String, data: ByteArray) {
        val target = resolve(path)
        target.parentFile?.let { it.mkdirs() }
        val temp = File(
            target.parentFile,
            target.name + ".tmp-" + UUID.randomUUID().toString().substring(0, 8)
        )
        try {
            FileOutputStream(temp).use { out ->
                out.write(data)
                out.flush()
                out.fd.sync() // fsync before the rename: survives power loss
            }
            moveWithRetry(temp.toPath(), target.toPath())
        } finally {
            temp.delete()
        }
    }

    /**
     * Atomic rename with a bounded retry for Windows-only sharing collisions:
     * two concurrent writers renaming over the same target transiently deny
     * each other's move (AccessDeniedException). POSIX rename (Android's
     * filesystem) does not fail this way, so the backoff is invisible
     * on-device — it exists to keep heavy multi-writer flows (imports during
     * progress writes, backups during deletes) from surfacing an IO error on
     * desktop test hosts.
     */
    private fun moveWithRetry(source: Path, target: Path) {
        var lastError: AccessDeniedException? = null
        for (attempt in 0 until 10) {
            try {
                Files.move(
                    source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                return
            } catch (e: AccessDeniedException) {
                lastError = e
                runCatching { Thread.sleep(5L * (attempt + 1)) }
            }
        }
        throw lastError ?: IllegalStateException("Could not move: $source -> $target")
    }

    override suspend fun write(path: String, data: String) =
        write(path, data.toByteArray(Charsets.UTF_8))

    override suspend fun exists(path: String): Boolean = resolve(path).isFile

    override suspend fun list(dir: String): List<String> {
        val dirFile = resolve(dir)
        if (!dirFile.isDirectory) return emptyList()
        return dirFile.listFiles()?.map { it.name } ?: emptyList()
    }

    override suspend fun stat(path: String): FileStat {
        val file = resolve(path)
        if (!file.isFile) throw java.io.FileNotFoundException("File not found: $path")
        return FileStat(size = file.length(), modifiedAt = file.lastModified())
    }

    /** Direct access to a library file (used to stream large files to the network). */
    override fun localFile(path: String): File? = resolve(path).takeIf { it.isFile }

    /**
     * Atomic rename of a file or directory tree (files/dirs, never across
     * stores — both paths resolve under the same root). Used by the backup
     * restore swap (audit P0-03): a POSIX rename makes the staging→live swap
     * a sequence of single-atomic-operation steps.
     */
    override suspend fun rename(from: String, to: String) {
        val source = resolve(from)
        val target = resolve(to)
        if (!source.exists()) {
            throw java.io.FileNotFoundException("Rename source does not exist: $from")
        }
        target.parentFile?.let { it.mkdirs() }
        moveWithRetry(source.toPath(), target.toPath())
    }

    override suspend fun remove(path: String, recursive: Boolean) {
        val file = resolve(path)
        if (!file.exists()) return
        if (file.isDirectory && !recursive) {
            if (file.listFiles()?.isNotEmpty() == true) {
                throw IllegalStateException("Directory not empty, use recursive=true: $path")
            }
        }
        val removed = if (recursive) file.deleteRecursively() else file.delete()
        if (!removed && file.exists()) {
            throw IllegalStateException("Could not remove: $path")
        }
    }
}
