package dev.folio.mobile.core.store

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
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
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            temp.delete()
        }
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
