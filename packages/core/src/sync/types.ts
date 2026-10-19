export interface SyncFileEntry {
  path: string; // e.g. "library/manifest.json", "library/docs/slug/original/book.epub"
  size: number;
  sha256: string;
  modifiedAt: string;
  id?: string;
}

export interface SyncProvider {
  signIn(): Promise<void>;
  signOut(): Promise<void>;
  isSignedIn(): boolean;
  list(): Promise<SyncFileEntry[]>;
  download(path: string): Promise<Uint8Array>;
  upload(path: string, data: Uint8Array, sha256: string): Promise<{ id?: string } | void>;
}

export interface SyncStateItem {
  driveId: string;
  sha256: string;
  remoteModifiedAt: string;
}

export interface SyncState {
  version: 1;
  files: Record<string, SyncStateItem>; // path -> SyncStateItem
}

export interface SyncProgress {
  phase: "connecting" | "listing" | "downloading" | "uploading" | "merging" | "complete" | "error";
  current: number;
  total: number;
  currentItem?: string;
  message: string;
}

export interface SyncResult {
  uploadedDocs: number;
  downloadedDocs: number;
  uploadedFiles: number;
  downloadedFiles: number;
  mergedUserFiles: number;
}
