import type { Annotation, Collection, Manifest, ManifestDoc, Progress } from "../types.js";
import type { FileStore } from "../store/file-store.js";
import type { LibraryManager } from "../library/library-manager.js";
import type { SyncFileEntry, SyncProgress, SyncProvider, SyncResult, SyncState } from "./types.js";
import { sha256Hex } from "../util/hash.js";

const SYNC_STATE_PATH = "sync/state.json";
const MANIFEST_PATH = "library/manifest.json";
const ANNOTATIONS_PATH = "library/user/annotations.json";
const PROGRESS_PATH = "library/user/progress.json";
const COLLECTIONS_PATH = "library/user/collections.json";

export interface SyncManagerOptions {
  fileStore: FileStore;
  libraryManager: LibraryManager;
  provider: SyncProvider;
  onProgress?: (progress: SyncProgress) => void;
  wifiOnly?: boolean;
}

export class SyncManager {
  private fileStore: FileStore;
  private libraryManager: LibraryManager;
  private provider: SyncProvider;
  private onProgress?: (progress: SyncProgress) => void;
  private wifiOnly: boolean;

  constructor(options: SyncManagerOptions) {
    this.fileStore = options.fileStore;
    this.libraryManager = options.libraryManager;
    this.provider = options.provider;
    this.onProgress = options.onProgress;
    this.wifiOnly = options.wifiOnly ?? true;
  }

  async loadState(): Promise<SyncState> {
    if (await this.fileStore.exists(SYNC_STATE_PATH)) {
      try {
        const text = await this.fileStore.readText(SYNC_STATE_PATH);
        return JSON.parse(text) as SyncState;
      } catch {
        // fallback
      }
    }
    return { version: 1, files: {} };
  }

  async saveState(state: SyncState): Promise<void> {
    await this.fileStore.write(SYNC_STATE_PATH, JSON.stringify(state, null, 2));
  }

  /**
   * Check Wi-Fi constraint if wifiOnly is configured and running in a browser
   * with Network Information API support.
   */
  private checkWifiConstraint(): boolean {
    if (!this.wifiOnly) return true;
    if (typeof navigator !== "undefined" && "connection" in navigator) {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const conn = (navigator as any).connection;
      if (conn?.type && conn.type !== "wifi" && conn.type !== "ethernet") {
        return false;
      }
      if (conn?.saveData) {
        return false;
      }
    }
    return true;
  }

  async sync(): Promise<SyncResult> {
    if (!this.checkWifiConstraint()) {
      throw new Error("Sync skipped: Wi-Fi only is enabled and connection is not Wi-Fi.");
    }

    const result: SyncResult = {
      uploadedDocs: 0,
      downloadedDocs: 0,
      uploadedFiles: 0,
      downloadedFiles: 0,
      mergedUserFiles: 0
    };

    // 1. Sign In / Connect
    this.onProgress?.({
      phase: "connecting",
      current: 0,
      total: 100,
      message: "Connecting to remote storage..."
    });

    if (!this.provider.isSignedIn()) {
      await this.provider.signIn();
    }

    // 2. Listing remote files
    this.onProgress?.({
      phase: "listing",
      current: 5,
      total: 100,
      message: "Listing remote files..."
    });

    const remoteFiles: SyncFileEntry[] = await this.provider.list();
    const remoteFileMap = new Map<string, SyncFileEntry>(remoteFiles.map((f) => [f.path, f]));
    const syncState = await this.loadState();

    // 3. Download remote manifest if present
    let remoteManifest: Manifest | null = null;
    if (remoteFileMap.has(MANIFEST_PATH)) {
      try {
        const manifestBytes = await this.provider.download(MANIFEST_PATH);
        const manifestText = new TextDecoder().decode(manifestBytes);
        remoteManifest = JSON.parse(manifestText) as Manifest;
      } catch {
        remoteManifest = null;
      }
    }

    // 4. Read local manifest
    const localManifest = await this.libraryManager.getManifest();

    const localDocsByHash = new Map(localManifest.docs.map((d) => [d.contentHash, d]));
    const localDocsById = new Map(localManifest.docs.map((d) => [d.id, d]));

    const remoteDocsByHash = new Map(remoteManifest ? remoteManifest.docs.map((d) => [d.contentHash, d]) : []);
    const remoteDocsById = new Map(remoteManifest ? remoteManifest.docs.map((d) => [d.id, d]) : []);

    // 5. Calculate diffs
    const docsToUpload: ManifestDoc[] = [];
    const docMetasToUpload: ManifestDoc[] = [];

    for (const lDoc of localManifest.docs) {
      const match = remoteDocsByHash.get(lDoc.contentHash) || remoteDocsById.get(lDoc.id);
      if (!match) {
        // Present only locally -> upload
        docsToUpload.push(lDoc);
      } else {
        // Present on both sides -> compare updatedAt
        const localTime = new Date(lDoc.updatedAt).getTime();
        const remoteTime = new Date(match.updatedAt).getTime();
        if (localTime > remoteTime) {
          docMetasToUpload.push(lDoc);
        }
      }
    }

    const docsToDownload: ManifestDoc[] = [];
    const docMetasToDownload: ManifestDoc[] = [];

    if (remoteManifest) {
      for (const rDoc of remoteManifest.docs) {
        const match = localDocsByHash.get(rDoc.contentHash) || localDocsById.get(rDoc.id);
        if (!match) {
          // Present only remotely -> download
          docsToDownload.push(rDoc);
        } else {
          // Present on both sides -> compare updatedAt
          const localTime = new Date(match.updatedAt).getTime();
          const remoteTime = new Date(rDoc.updatedAt).getTime();
          if (remoteTime > localTime) {
            docMetasToDownload.push(rDoc);
          }
        }
      }
    }

    // 6. Download remote docs
    if (docsToDownload.length > 0 || docMetasToDownload.length > 0) {
      let downloadIndex = 0;
      const totalDownloads = docsToDownload.length + docMetasToDownload.length;

      for (const rDoc of docsToDownload) {
        downloadIndex++;
        this.onProgress?.({
          phase: "downloading",
          current: downloadIndex,
          total: totalDownloads,
          currentItem: rDoc.title,
          message: `Downloading document: ${rDoc.title}`
        });

        // Download document content files
        for (const file of rDoc.files) {
          if (remoteFileMap.has(file.path)) {
            const data = await this.provider.download(file.path);
            await this.fileStore.write(file.path, data);
            const entry = remoteFileMap.get(file.path);
            syncState.files[file.path] = {
              driveId: entry?.id || "",
              sha256: file.sha256 || entry?.sha256 || "",
              remoteModifiedAt: entry?.modifiedAt || new Date().toISOString()
            };
            result.downloadedFiles++;
          }
        }

        // Download meta.json
        const metaPath = `library/docs/${rDoc.slug}/meta.json`;
        if (remoteFileMap.has(metaPath)) {
          const metaBytes = await this.provider.download(metaPath);
          await this.fileStore.write(metaPath, metaBytes);
          const entry = remoteFileMap.get(metaPath);
          syncState.files[metaPath] = {
            driveId: entry?.id || "",
            sha256: entry?.sha256 || "",
            remoteModifiedAt: entry?.modifiedAt || new Date().toISOString()
          };
          result.downloadedFiles++;
        }

        result.downloadedDocs++;
      }

      for (const rDoc of docMetasToDownload) {
        downloadIndex++;
        this.onProgress?.({
          phase: "downloading",
          current: downloadIndex,
          total: totalDownloads,
          currentItem: rDoc.title,
          message: `Updating document metadata: ${rDoc.title}`
        });

        const metaPath = `library/docs/${rDoc.slug}/meta.json`;
        if (remoteFileMap.has(metaPath)) {
          const metaBytes = await this.provider.download(metaPath);
          await this.fileStore.write(metaPath, metaBytes);
          const entry = remoteFileMap.get(metaPath);
          syncState.files[metaPath] = {
            driveId: entry?.id || "",
            sha256: entry?.sha256 || "",
            remoteModifiedAt: entry?.modifiedAt || new Date().toISOString()
          };
          result.downloadedFiles++;
        }
      }
    }

    // 7. Upload local docs
    if (docsToUpload.length > 0 || docMetasToUpload.length > 0) {
      let uploadIndex = 0;
      const totalUploads = docsToUpload.length + docMetasToUpload.length;

      for (const lDoc of docsToUpload) {
        uploadIndex++;
        this.onProgress?.({
          phase: "uploading",
          current: uploadIndex,
          total: totalUploads,
          currentItem: lDoc.title,
          message: `Uploading document: ${lDoc.title}`
        });

        for (const file of lDoc.files) {
          if (await this.fileStore.exists(file.path)) {
            const data = await this.fileStore.read(file.path);
            const sha256 = await sha256Hex(data);
            const uploadRes = await this.provider.upload(file.path, data, sha256);
            syncState.files[file.path] = {
              driveId: uploadRes?.id || "",
              sha256,
              remoteModifiedAt: new Date().toISOString()
            };
            result.uploadedFiles++;
          }
        }

        const metaPath = `library/docs/${lDoc.slug}/meta.json`;
        if (await this.fileStore.exists(metaPath)) {
          const metaBytes = await this.fileStore.read(metaPath);
          const metaHash = await sha256Hex(metaBytes);
          const uploadRes = await this.provider.upload(metaPath, metaBytes, metaHash);
          syncState.files[metaPath] = {
            driveId: uploadRes?.id || "",
            sha256: metaHash,
            remoteModifiedAt: new Date().toISOString()
          };
          result.uploadedFiles++;
        }

        result.uploadedDocs++;
      }

      for (const lDoc of docMetasToUpload) {
        uploadIndex++;
        this.onProgress?.({
          phase: "uploading",
          current: uploadIndex,
          total: totalUploads,
          currentItem: lDoc.title,
          message: `Uploading updated metadata: ${lDoc.title}`
        });

        const metaPath = `library/docs/${lDoc.slug}/meta.json`;
        if (await this.fileStore.exists(metaPath)) {
          const metaBytes = await this.fileStore.read(metaPath);
          const metaHash = await sha256Hex(metaBytes);
          const uploadRes = await this.provider.upload(metaPath, metaBytes, metaHash);
          syncState.files[metaPath] = {
            driveId: uploadRes?.id || "",
            sha256: metaHash,
            remoteModifiedAt: new Date().toISOString()
          };
          result.uploadedFiles++;
        }
      }
    }

    // 8. Reconcile and Merge User Data (annotations, progress, collections)
    this.onProgress?.({
      phase: "merging",
      current: 75,
      total: 100,
      message: "Reconciling annotations, reading progress and collections..."
    });

    // Reconcile Annotations
    await this.reconcileAnnotations(remoteFileMap, syncState, result);

    // Reconcile Progress
    await this.reconcileProgress(remoteFileMap, syncState, result);

    // Reconcile Collections
    await this.reconcileCollections(remoteFileMap, syncState, result);

    // 9. Merge and Finalize Manifest
    // Combine local docs and downloaded remote docs into unified manifest
    const mergedDocsMap = new Map<string, ManifestDoc>();
    for (const d of localManifest.docs) {
      mergedDocsMap.set(d.id, d);
    }
    for (const d of docsToDownload) {
      mergedDocsMap.set(d.id, d);
    }
    for (const d of docMetasToDownload) {
      mergedDocsMap.set(d.id, d);
    }

    const finalManifest: Manifest = {
      schema: 1,
      app: "folio",
      libraryId: localManifest.libraryId || remoteManifest?.libraryId || crypto.randomUUID(),
      updatedAt: new Date().toISOString(),
      docs: Array.from(mergedDocsMap.values())
    };

    // Save final manifest locally
    await this.fileStore.write(MANIFEST_PATH, JSON.stringify(finalManifest, null, 2));

    // Upload final manifest to remote last
    this.onProgress?.({
      phase: "uploading",
      current: 95,
      total: 100,
      currentItem: MANIFEST_PATH,
      message: "Uploading final library manifest..."
    });

    const manifestBytes = new TextEncoder().encode(JSON.stringify(finalManifest, null, 2));
    const manifestHash = await sha256Hex(manifestBytes);
    const manifestUploadRes = await this.provider.upload(MANIFEST_PATH, manifestBytes, manifestHash);
    syncState.files[MANIFEST_PATH] = {
      driveId: manifestUploadRes?.id || "",
      sha256: manifestHash,
      remoteModifiedAt: new Date().toISOString()
    };
    result.uploadedFiles++;

    // 10. Persist Sync State
    await this.saveState(syncState);

    // 11. Rebuild Search Index if new documents arrived
    if (docsToDownload.length > 0) {
      await this.libraryManager.rebuildSearchIndex();
    }

    // 12. Complete
    this.onProgress?.({
      phase: "complete",
      current: 100,
      total: 100,
      message: "Sync complete"
    });

    return result;
  }

  private async reconcileAnnotations(
    remoteFileMap: Map<string, SyncFileEntry>,
    syncState: SyncState,
    result: SyncResult
  ): Promise<void> {
    const hasRemote = remoteFileMap.has(ANNOTATIONS_PATH);
    const hasLocal = await this.fileStore.exists(ANNOTATIONS_PATH);

    if (!hasRemote && !hasLocal) return;

    let remoteAnnots: Annotation[] = [];
    if (hasRemote) {
      try {
        const bytes = await this.provider.download(ANNOTATIONS_PATH);
        remoteAnnots = JSON.parse(new TextDecoder().decode(bytes));
      } catch {
        remoteAnnots = [];
      }
    }

    const currentAnnots = await this.libraryManager.getAnnotations();
    const map = new Map<string, Annotation>(currentAnnots.map((a) => [a.id, a]));

    for (const rAnn of remoteAnnots) {
      const curr = map.get(rAnn.id);
      if (!curr || new Date(rAnn.updatedAt).getTime() > new Date(curr.updatedAt).getTime()) {
        map.set(rAnn.id, rAnn);
      }
    }

    const merged = Array.from(map.values());
    const mergedBytes = new TextEncoder().encode(JSON.stringify(merged, null, 2));
    await this.fileStore.write(ANNOTATIONS_PATH, mergedBytes);

    // Upload merged annotations to remote
    const hash = await sha256Hex(mergedBytes);
    const res = await this.provider.upload(ANNOTATIONS_PATH, mergedBytes, hash);
    syncState.files[ANNOTATIONS_PATH] = {
      driveId: res?.id || "",
      sha256: hash,
      remoteModifiedAt: new Date().toISOString()
    };
    result.mergedUserFiles++;
  }

  private async reconcileProgress(
    remoteFileMap: Map<string, SyncFileEntry>,
    syncState: SyncState,
    result: SyncResult
  ): Promise<void> {
    const hasRemote = remoteFileMap.has(PROGRESS_PATH);
    const hasLocal = await this.fileStore.exists(PROGRESS_PATH);

    if (!hasRemote && !hasLocal) return;

    let remoteProg: Record<string, Progress> = {};
    if (hasRemote) {
      try {
        const bytes = await this.provider.download(PROGRESS_PATH);
        remoteProg = JSON.parse(new TextDecoder().decode(bytes));
      } catch {
        remoteProg = {};
      }
    }

    const currentProg = await this.libraryManager.getAllProgress();
    for (const [docId, p] of Object.entries(remoteProg)) {
      const curr = currentProg[docId];
      if (!curr || new Date(p.updatedAt).getTime() > new Date(curr.updatedAt).getTime()) {
        currentProg[docId] = p;
      }
    }

    const mergedBytes = new TextEncoder().encode(JSON.stringify(currentProg, null, 2));
    await this.fileStore.write(PROGRESS_PATH, mergedBytes);

    // Upload merged progress
    const hash = await sha256Hex(mergedBytes);
    const res = await this.provider.upload(PROGRESS_PATH, mergedBytes, hash);
    syncState.files[PROGRESS_PATH] = {
      driveId: res?.id || "",
      sha256: hash,
      remoteModifiedAt: new Date().toISOString()
    };
    result.mergedUserFiles++;
  }

  private async reconcileCollections(
    remoteFileMap: Map<string, SyncFileEntry>,
    syncState: SyncState,
    result: SyncResult
  ): Promise<void> {
    const hasRemote = remoteFileMap.has(COLLECTIONS_PATH);
    const hasLocal = await this.fileStore.exists(COLLECTIONS_PATH);

    if (!hasRemote && !hasLocal) return;

    let remoteColls: Collection[] = [];
    if (hasRemote) {
      try {
        const bytes = await this.provider.download(COLLECTIONS_PATH);
        remoteColls = JSON.parse(new TextDecoder().decode(bytes));
      } catch {
        remoteColls = [];
      }
    }

    const currentColls = await this.libraryManager.getCollections();
    const map = new Map<string, Collection>(currentColls.map((c) => [c.id, c]));

    for (const rColl of remoteColls) {
      const curr = map.get(rColl.id);
      if (!curr) {
        map.set(rColl.id, rColl);
      } else {
        const combinedDocIds = Array.from(new Set([...curr.docIds, ...rColl.docIds]));
        map.set(rColl.id, {
          id: curr.id,
          name: curr.name || rColl.name,
          createdAt: curr.createdAt || rColl.createdAt,
          docIds: combinedDocIds
        });
      }
    }

    const merged = Array.from(map.values());
    const mergedBytes = new TextEncoder().encode(JSON.stringify(merged, null, 2));
    await this.fileStore.write(COLLECTIONS_PATH, mergedBytes);

    // Upload merged collections
    const hash = await sha256Hex(mergedBytes);
    const res = await this.provider.upload(COLLECTIONS_PATH, mergedBytes, hash);
    syncState.files[COLLECTIONS_PATH] = {
      driveId: res?.id || "",
      sha256: hash,
      remoteModifiedAt: new Date().toISOString()
    };
    result.mergedUserFiles++;
  }
}
