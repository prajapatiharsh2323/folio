export interface BackupMeta {
  schema: 1;
  createdAt: string;
  appVersion: string;
  docCount: number;
  manifestSha256: string;
}

export interface VerificationIssue {
  path: string;
  reason: "checksum_mismatch" | "missing_in_archive" | "unsafe_path" | "corrupted";
  expectedSha256?: string;
  actualSha256?: string;
  message: string;
}

export interface VerificationResult {
  valid: boolean;
  backupMeta?: BackupMeta;
  issues: VerificationIssue[];
  totalFiles: number;
  totalSize: number;
}

export interface RestoreSummary {
  mode: "merge" | "replace";
  totalDocsInBackup: number;
  newDocsAdded: number;
  duplicatesSkipped: number;
  metadataUpdated: number;
  annotationsMerged: number;
  progressUpdated: number;
  collectionsMerged: number;
}
