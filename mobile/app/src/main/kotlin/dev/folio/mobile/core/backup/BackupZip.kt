package dev.folio.mobile.core.backup

import dev.folio.mobile.core.model.Manifest
import dev.folio.mobile.core.util.sha256Hex
import kotlinx.serialization.builtins.ListSerializer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Kotlin port of packages/core/src/backup/{verify,export-zip}.ts (java.util.zip
 * instead of fflate). The archive layout and safety limits are the PWA contract:
 * - `backup.json` (BackupMeta) first
 * - `library/manifest.json` (commit point, SHA-256 recorded in backup.json)
 * - every file listed in the manifest's docs
 * - `library/user/{annotations,progress,collections,settings}.json`
 */

/** §10 Safety limits: entry-name traversal, entry count and total size caps. */
object BackupLimits {
    const val MAX_ENTRIES = 20000
    const val MAX_TOTAL_UNCOMPRESSED = 1024L * 1024L * 1024L // 1 GB
}

/** Validates zip entry names against path traversal and drive-letter abuse. */
fun checkPathSafety(entryName: String): Boolean {
    if (entryName.isEmpty()) return false
    if (entryName.startsWith("/") || entryName.startsWith("\\")) return false
    if (entryName.contains("..")) return false
    // Windows drive letter check (e.g. C:)
    if (Regex("^[a-zA-Z]:").containsMatchIn(entryName)) return false
    return true
}

private val STORE_SUFFIXES =
    listOf(".epub", ".pdf", ".jpg", ".jpeg", ".png", ".webp")

/** §8 Compression rules: store already-compressed binaries, deflate level 6 for the rest. */
private fun useStore(path: String): Boolean =
    STORE_SUFFIXES.any { path.lowercase().endsWith(it) }

/** Standard backup filename: folio-backup-YYYYMMDD-HHMM.zip (local time). */
fun generateBackupFilename(now: java.util.Date = java.util.Date()): String =
    java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(now)

/**
 * Unzips [zipBytes] with safety checks and verifies every manifest-listed file
 * against its SHA-256 (verify.ts semantics: unsafe paths are recorded, corrupt
 * archives return `valid = false` with a single `corrupted` issue).
 */
fun unzipAndVerifyBackup(zipBytes: ByteArray): VerificationResult {
    val issues = mutableListOf<VerificationIssue>()
    val unzipped = LinkedHashMap<String, ByteArray>()
    var totalFiles = 0
    var totalSize = 0L

    // 1. Unzip with per-entry safety checks.
    var metMeta: String? = null
    var metManifest: String? = null
    run unzip@{
        try {
            ZipInputStream(zipBytes.inputStream()).use { stream ->
                while (true) {
                    val entry = stream.nextEntry ?: break
                    if (!checkPathSafety(entry.name)) {
                        issues.add(
                            VerificationIssue(
                                path = entry.name, reason = "unsafe_path",
                                message = "Unsafe archive path detected: ${entry.name}"
                            )
                        )
                        stream.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        stream.closeEntry()
                        continue
                    }
                    totalFiles++
                    val chunks = ArrayList<ByteArray>()
                    var len = 0
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = stream.read(buf)
                        if (n <= 0) break
                        len += n
                        if (totalSize + len > BackupLimits.MAX_TOTAL_UNCOMPRESSED) {
                            issues.add(
                                VerificationIssue(
                                    path = "<archive>", reason = "corrupted",
                                    message = "Archive exceeds maximum uncompressed size of 1 GB"
                                )
                            )
                            return@unzip
                        }
                        chunks.addIfRoom(buf, n)
                        if (totalFiles > BackupLimits.MAX_ENTRIES) {
                            issues.add(
                                VerificationIssue(
                                    path = "<archive>", reason = "corrupted",
                                    message = "Archive exceeds maximum entry count of ${BackupLimits.MAX_ENTRIES}"
                                )
                            )
                            return@unzip
                        }
                    }
                    totalSize += len
                    unzipped[entry.name] = chunks.flatten()
                    when (entry.name) {
                        "backup.json" -> metMeta = chunks.flatten().decodeToString()
                        "library/manifest.json" -> metManifest = chunks.flatten().decodeToString()
                    }
                    stream.closeEntry()
                }
            }
        } catch (e: java.util.zip.ZipException) {
            // CRC/sync errors on any entry make the whole archive untrustworthy.
            issues.add(
                VerificationIssue(
                    path = "<archive>", reason = "corrupted",
                    message = "Archive failed to unzip: ${e.message}"
                )
            )
        }
    }
    if (issues.any { it.path == "<archive>" }) {
        return VerificationResult(false, null, issues, totalFiles, totalSize)
    }

    var backupMeta: BackupMeta? = try {
        metMeta?.takeIf { it.isNotBlank() }?.let { parseBackupMeta(it) }
    } catch (_: Exception) {
        null
    }
    if (metMeta != null && backupMeta == null) {
        issues.add(
            VerificationIssue(
                path = "backup.json", reason = "corrupted",
                message = "Failed to parse backup.json metadata"
            )
        )
    }

    // 2. Parse library/manifest.json (mandatory).
    val manifest = metManifest?.let { runCatching { parseManifest(it) }.getOrNull() }
    if (metManifest == null) {
        issues.add(
            VerificationIssue(
                path = "library/manifest.json", reason = "missing_in_archive",
                message = "library/manifest.json not found in backup archive"
            )
        )
        return VerificationResult(false, backupMeta, issues, totalFiles, totalSize)
    }
    if (manifest == null) {
        issues.add(
            VerificationIssue(
                path = "library/manifest.json", reason = "corrupted",
                message = "Failed to parse library/manifest.json"
            )
        )
        return VerificationResult(false, backupMeta, issues, totalFiles, totalSize)
    }

    // 3. Verify the manifest's sha256 against backup.json when present.
    backupMeta?.let { meta ->
        if (meta.manifestSha256.isNotBlank()) {
            val computed = sha256Hex(metManifest!!.encodeToByteArray())
            if (computed != meta.manifestSha256) {
                issues.add(
                    VerificationIssue(
                        path = "library/manifest.json", reason = "checksum_mismatch",
                        expectedSha256 = meta.manifestSha256, actualSha256 = computed,
                        message = "Manifest SHA-256 does not match backup.json"
                    )
                )
            }
        }
    }

    // 4. Verify each document file against manifest checksums.
    for (doc in manifest.docs) {
        for (f in doc.files) {
            val data = unzipped[f.path]
            if (data == null) {
                issues.add(
                    VerificationIssue(
                        path = f.path, reason = "missing_in_archive",
                        expectedSha256 = f.sha256,
                        message = "File listed in manifest is missing from backup: ${f.path}"
                    )
                )
                continue
            }
            val actual = sha256Hex(data)
            if (actual != f.sha256) {
                issues.add(
                    VerificationIssue(
                        path = f.path, reason = "checksum_mismatch",
                        expectedSha256 = f.sha256, actualSha256 = actual,
                        message = "Checksum mismatch for ${f.path}"
                    )
                )
            }
        }
    }

    return VerificationResult(
        valid = issues.isEmpty(),
        backupMeta = backupMeta,
        issues = issues,
        totalFiles = totalFiles,
        totalSize = totalSize,
        unzipped = unzipped
    )
}

private fun MutableList<ByteArray>.addIfRoom(buf: ByteArray, n: Int) {
    this.add(buf.copyOf(n))
}

private fun ArrayList<ByteArray>.flatten(): ByteArray {
    val size = this.sumOf { it.size }
    val out = ByteArray(size)
    var off = 0
    for (c in this) {
        c.copyInto(out, off)
        off += c.size
    }
    return out
}

/** Streams a full backup ZIP to [out], reading each file individually so the
 *  whole library never sits in memory (PWA export-zip.ts contract). */
suspend fun exportBackupZip(
    library: dev.folio.mobile.core.store.FileStore,
    out: java.io.OutputStream,
    onProgress: ((current: Int, total: Int, filename: String) -> Unit)? = null
): BackupMeta {
    val manifest = parseManifest(library.readText(dev.folio.mobile.core.library.MANIFEST_PATH))

    // Deterministic file set (no duplicates) + files missing on disk dropped.
    val files = LinkedHashSet<String>()
    val sanitizedDocs = manifest.docs.map { doc ->
        val present = doc.files.filter { f ->
            val ok = library.exists(f.path)
            if (ok) {
                files.add(f.path)
            } else {
                java.util.logging.Logger.getLogger("BackupExport")
                    .warning("exportBackup: missing file for doc ${doc.id}: ${f.path}")
            }
            ok
        }
        doc.copy(files = present)
    }
    for (d in manifest.docs) {
        val metaPath = "library/docs/${d.slug}/meta.json"
        if (library.exists(metaPath)) files.add(metaPath)
    }
    val userFiles = listOf(
        "library/user/annotations.json",
        "library/user/progress.json",
        "library/user/collections.json",
        "library/user/settings.json"
    )
    for (uf in userFiles) {
        if (library.exists(uf)) files.add(uf)
    }

    val exportManifest = manifest.copy(
        docs = sanitizedDocs,
        updatedAt = java.time.Instant.now().toString()
    )
    val manifestBytes = backupJson.encodeToString(Manifest.serializer(), exportManifest).encodeToByteArray()
    val meta = BackupMeta(
        createdAt = java.time.Instant.now().toString(),
        appVersion = "0.1.0",
        docCount = sanitizedDocs.size,
        manifestSha256 = sha256Hex(manifestBytes)
    )

    val zipStream = ZipOutputStream(out.buffered())
    zipStream.setLevel(Deflater.DEFAULT_COMPRESSION)

    fun add(name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        if (useStore(name)) {
            val crc = CRC32().apply { update(data) }
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            entry.crc = crc.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zipStream.putNextEntry(entry)
        zipStream.write(data)
        zipStream.closeEntry()
    }

    add("backup.json", backupJson.encodeToString(BackupMeta.serializer(), meta).encodeToByteArray())
    add("library/manifest.json", manifestBytes)

    val paths = files.toList()
    for ((index, path) in paths.withIndex()) {
        onProgress?.invoke(index + 1, paths.size + 1, path)
        add(path, library.read(path))
    }
    zipStream.finish()
    zipStream.flush()
    return meta
}
