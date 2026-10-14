import * as fflate from "fflate";
import { sha256Hex } from "../util/hash.js";
import type { BackupMeta, VerificationIssue, VerificationResult } from "./types.js";
import type { Manifest } from "../types.js";

// Section 10 Safety limits:
// Reject entry names containing .. or starting with /; cap entry count (20,000), total uncompressed size (1 GB)
const MAX_ENTRIES = 20000;
const MAX_TOTAL_UNCOMPRESSED = 1024 * 1024 * 1024; // 1 GB

/**
 * Validates zip entry safety against path traversal and zip bomb exploits.
 */
export function checkPathSafety(entryName: string): boolean {
  if (!entryName) return false;
  if (entryName.startsWith("/") || entryName.startsWith("\\")) return false;
  if (entryName.includes("..")) return false;
  // Windows drive letter check (e.g. C:)
  if (/^[a-zA-Z]:/.test(entryName)) return false;
  return true;
}

/**
 * Verifies a backup archive before restoring:
 * 1. Checks archive safety (path traversal, entry limits)
 * 2. Parses backup.json and library/manifest.json
 * 3. Verifies every file's SHA-256 against the manifest
 */
export async function verifyBackupZip(zipBytes: Uint8Array): Promise<VerificationResult> {
  const issues: VerificationIssue[] = [];
  let backupMeta: BackupMeta | undefined;
  let manifest: Manifest | undefined;
  let totalFiles = 0;
  let totalSize = 0;

  // Unzip archive with safety checks
  let unzipped: fflate.Unzipped;
  try {
    unzipped = fflate.unzipSync(zipBytes, {
      filter(file) {
        if (!checkPathSafety(file.name)) {
          issues.push({
            path: file.name,
            reason: "unsafe_path",
            message: `Unsafe archive path detected: ${file.name}`
          });
          return false;
        }
        totalFiles++;
        totalSize += file.originalSize;
        if (totalFiles > MAX_ENTRIES) {
          throw new Error(`Archive exceeds maximum entry count of ${MAX_ENTRIES}`);
        }
        if (totalSize > MAX_TOTAL_UNCOMPRESSED) {
          throw new Error(`Archive exceeds maximum uncompressed size of 1 GB`);
        }
        return true;
      }
    });
  } catch (err) {
    const error = err as Error;
    return {
      valid: false,
      issues: [
        {
          path: "<archive>",
          reason: "corrupted",
          message: error.message || "Failed to unzip archive"
        }
      ],
      totalFiles,
      totalSize
    };
  }

  // 1. Check backup.json
  if (unzipped["backup.json"]) {
    try {
      const metaText = fflate.strFromU8(unzipped["backup.json"]);
      backupMeta = JSON.parse(metaText) as BackupMeta;
    } catch {
      issues.push({
        path: "backup.json",
        reason: "corrupted",
        message: "Failed to parse backup.json metadata"
      });
    }
  }

  // 2. Read library/manifest.json
  const manifestData = unzipped["library/manifest.json"];
  if (!manifestData) {
    issues.push({
      path: "library/manifest.json",
      reason: "missing_in_archive",
      message: "library/manifest.json not found in backup archive"
    });
    return {
      valid: false,
      backupMeta,
      issues,
      totalFiles,
      totalSize
    };
  }

  try {
    const manifestText = fflate.strFromU8(manifestData);
    manifest = JSON.parse(manifestText) as Manifest;
  } catch {
    issues.push({
      path: "library/manifest.json",
      reason: "corrupted",
      message: "Failed to parse library/manifest.json"
    });
    return {
      valid: false,
      backupMeta,
      issues,
      totalFiles,
      totalSize
    };
  }

  // 3. Verify manifest checksum against backup.json if present
  if (backupMeta?.manifestSha256) {
    const computedManifestHash = await sha256Hex(manifestData);
    if (computedManifestHash !== backupMeta.manifestSha256) {
      issues.push({
        path: "library/manifest.json",
        reason: "checksum_mismatch",
        expectedSha256: backupMeta.manifestSha256,
        actualSha256: computedManifestHash,
        message: "Manifest SHA-256 does not match backup.json"
      });
    }
  }

  // 4. Verify each document file against manifest checksums
  if (Array.isArray(manifest.docs)) {
    for (const doc of manifest.docs) {
      if (!Array.isArray(doc.files)) continue;
      for (const f of doc.files) {
        const fileData = unzipped[f.path];
        if (!fileData) {
          issues.push({
            path: f.path,
            reason: "missing_in_archive",
            expectedSha256: f.sha256,
            message: `File listed in manifest is missing from backup: ${f.path}`
          });
          continue;
        }

        const actualSha = await sha256Hex(fileData);
        if (actualSha !== f.sha256) {
          issues.push({
            path: f.path,
            reason: "checksum_mismatch",
            expectedSha256: f.sha256,
            actualSha256: actualSha,
            message: `Checksum mismatch for ${f.path}`
          });
        }
      }
    }
  }

  return {
    valid: issues.length === 0,
    backupMeta,
    issues,
    totalFiles,
    totalSize,
    unzipped
  };
}
