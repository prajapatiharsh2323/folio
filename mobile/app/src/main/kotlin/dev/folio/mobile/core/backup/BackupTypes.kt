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
    val unzipped: Map<String, ByteArray> = emptyMap(),
    /**
     * Human-readable, user-visible notes for non-fatal findings (audit P0-04):
     * skipped entries, checksum mismatches, or sections that carried no warnings
     * after merge. Empty when the archive verified clean.
     */
    val warnings: List<String> = emptyList()
)

data class RestoreSummary(
    val mode: String,
    val totalDocsInBackup: Int,
    val newDocsAdded: Int,
    val duplicatesSkipped: Int,
    val metadataUpdated: Int,
    val annotationsMerged: Int,
    val progressUpdated: Int,
    val collectionsMerged: Int,
    /**
     * Non-fatal, user-visible recovery notes (audit P0-04): sections that
     * could not be merged honestly, unreadable local state, repaired
     * corrections. UIs must distinguish "restore completed" from
     * "restore completed with warnings" using this list.
     */
    val warnings: List<String> = emptyList()
)

/** Restore mode matching the PWA's RestoreOptions.mode. */
enum class RestoreMode { Merge, Replace }
