package dev.folio.mobile.core.dictionary

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Copies the bundled dictionary pack from app assets into app-private
 * storage on first use (D1), verifying integrity before promotion:
 *
 * write to `<target>.part` → stream sha256 while copying → compare with the
 * expected digest → rename `.part` → `<target>` (atomic on same volume) →
 * keep only on success. A previous corrupt file is replaced; a matching
 * existing file is reused (no repeat copying every launch).
 *
 * The source seam keeps this JVM-testable: production reads
 * `android.content.res.AssetManager.open("dictionary/eng-hin.db")`; tests
 * pipe any InputStream.
 */
interface DictionaryPackSource {
    /** Opens the pack stream, or null when the asset is missing. */
    fun open(): InputStream?

    /** Expected sha256 of the pack (from the tracker/meta), or null if unknown. */
    fun expectedSha256(): String?
}

fun interface DictionaryPackLocator {
    /** Directory the promoted pack lives in (app filesDir/dictionary). */
    fun packDir(): File
}

class DictionaryPackInstaller(
    private val source: DictionaryPackSource,
    private val locator: DictionaryPackLocator,
    private val targetFileName: String = DEFAULT_TARGET,
) {
    data class InstallResult(val file: File?)

    /**
     * Returns the verified pack file, or null when the asset is missing or
     * fails verification. Never throws for corrupt data — recoverable then.
     */
    suspend fun ensureInstalled(): File? {
        val dir = locator.packDir()
        val target = File(dir, targetFileName)
        val expected = source.expectedSha256()
        if (target.exists() && target.length() > 0) {
            // Cheap re-validation: only re-hash when no digest marker matches.
            val marker = File(dir, "$targetFileName.sha256")
            val recorded = if (marker.exists()) marker.readText().trim() else null
            if (recorded != null && (expected == null || recorded == expected)) {
                return target
            }
            val sha = sha256(target.inputStream())
            if (expected == null || sha == expected) {
                return target
            }
            // Corrupt leftover: fall through to a fresh copy.
            target.delete()
            marker.delete()
        }
        val stream = source.open() ?: return null
        stream.use {
            val part = File(dir, "$targetFileName.part.")
            dir.mkdirs()
            val digest = File(dir, "$targetFileName.sha256")
            try {
                val sha = copyWithDigest(it, part)
                if (expected != null && sha != expected) {
                    part.delete()
                    return null
                }
                if (!part.renameTo(target)) {
                    return null
                }
                digest.writeText(sha)
                return target
            } finally {
                part.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_TARGET = "eng-hin.db"

        fun sha256(stream: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(DIGEST_BUF)
            var n = stream.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = stream.read(buf)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Streams [from] into the [to] file, returning the computed digest of
         * what was written (single read, no double-cost verification pass).
         */
        fun copyWithDigest(from: InputStream, to: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(DIGEST_BUF)
            var n = from.read(buf)
            to.outputStream().use { out ->
                while (n > 0) {
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    n = from.read(buf)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private const val DIGEST_BUF = 1 shl 16
    }
}
