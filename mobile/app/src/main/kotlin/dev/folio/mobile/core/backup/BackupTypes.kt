package dev.folio.mobile.core.backup

import kotlinx.serialization.Serializable

/**
 * Direct Kotlin port of packages/core/src/backup/types.ts — the schema-1
 * backup ZIP contract is byte-compatible with the PWA (see
 * .docs/MOBILE-TRACKING.md "carried constraints").
 */

@Serializable
data class BackupMeta(
    val schema: Int = 1,
    val createdAt: String,
    val appVersion: String,
    val docCount: Int,
    val manifestSha256: String
)

@Serializable
data class VerificationIssue(
    val path: String,
    /** "checksum_mismatch" | "missing_in_archive" | "unsafe_path" | "corrupted". */
    val reason: String,
    val expectedSha256: String? = null,
    val actualSha256: String? = null,
    val message: String
)

data class VerificationResult(
    val valid: Boolean,
    val backupMeta: BackupMeta? = null,
    val issues: List<VerificationIssue> = emptyList(),
    val totalFiles: Int,
    val totalSize: Long,
    /** Decompressed archive bytes keyed by entry path (empty when the archive failed to unzip). */
    val unzipped: Map<String, ByteArray> = emptyMap()
)

data class RestoreSummary(
    val mode: String,
    val totalDocsInBackup: Int,
    val newDocsAdded: Int,
    val duplicatesSkipped: Int,
    val metadataUpdated: Int,
    val annotationsMerged: Int,
    val progressUpdated: Int,
    val collectionsMerged: Int
)

/** Restore mode matching the PWA's RestoreOptions.mode. */
enum class RestoreMode { Merge, Replace }
