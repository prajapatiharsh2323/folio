import * as fflate from "fflate";
import type { FileStore } from "../store/file-store.js";
import type { LibraryManager } from "../library/library-manager.js";
import { MANIFEST_PATH } from "../library/library-manager.js";
import { verifyBackupZip } from "./verify.js";
import type { RestoreSummary, VerificationResult } from "./types.js";
import type { Annotation, Collection, Manifest, Progress } from "../types.js";

export interface RestoreOptions {
  mode: "merge" | "replace";
  onProgress?: (progress: { current: number; total: number; message: string }) => void;
}

/**
 * Restores a backup ZIP into the FileStore following the exact Section 8 merge & replace rules:
 *
 * 1. Merge (default):
 *    - A document whose contentHash already exists is skipped
 *    - For the same id, the newer updatedAt metadata wins
 *    - Annotations merge by id with the newer updatedAt winning
 *    - Progress keeps the newer entry per document
 *    - Collections union by id with their docIds combined
 *    - Local settings are kept
 *    - Merge never deletes anything
 *
 * 2. Replace:
 *    - Wipes the library first (manifest, docs/, user/, index/)
 *
 * 3. Rebuild search index afterwards.
 */
export async function restoreBackupZip(
  store: FileStore,
  manager: LibraryManager,
  zipBytes: Uint8Array,
  opts: RestoreOptions
): Promise<{ summary: RestoreSummary; verification: VerificationResult }> {
  // First, verify archive thoroughly
  const verification = await verifyBackupZip(zipBytes);
  const fatalIssues = verification.issues.filter(
    (i) =>
      i.reason === "unsafe_path" ||
      i.reason === "corrupted" ||
      i.path === "library/manifest.json"
  );
  if (fatalIssues.length > 0) {
    const errorMsg = fatalIssues.map((i) => i.message).join("; ");
    throw new Error(`Backup archive failed verification: ${errorMsg || "Invalid archive"}`);
  }

  const unzipped = verification.unzipped || fflate.unzipSync(zipBytes);

  // Read and validate backup manifest before any modifications
  const backupManifestBytes = unzipped["library/manifest.json"];
  if (!backupManifestBytes) {
    throw new Error("Missing library/manifest.json in backup archive");
  }

  let backupManifest: Manifest;
  try {
    backupManifest = JSON.parse(fflate.strFromU8(backupManifestBytes));
  } catch {
    throw new Error("Corrupted library/manifest.json in backup archive");
  }

  const summary: RestoreSummary = {
    mode: opts.mode,
    totalDocsInBackup: backupManifest.docs.length,
    newDocsAdded: 0,
    duplicatesSkipped: 0,
    metadataUpdated: 0,
    annotationsMerged: 0,
    progressUpdated: 0,
    collectionsMerged: 0
  };

  // If REPLACE mode, wipe existing library only AFTER backup is verified and parsed successfully
  if (opts.mode === "replace") {
    opts.onProgress?.({ current: 0, total: 100, message: "Wiping existing library for replace..." });

    // Remove manifest
    if (await store.exists(MANIFEST_PATH)) {
      await store.remove(MANIFEST_PATH);
    }
    // Remove docs
    if (await store.exists("library/docs")) {
      await store.remove("library/docs", { recursive: true });
    }
    // Remove user
    if (await store.exists("library/user")) {
      await store.remove("library/user", { recursive: true });
    }
    // Remove index
    if (await store.exists("library/index")) {
      await store.remove("library/index", { recursive: true });
    }
  }

  // Read current manifest (if exists)
  let currentManifest: Manifest;
  if (await store.exists(MANIFEST_PATH)) {
    try {
      currentManifest = JSON.parse(await store.readText(MANIFEST_PATH));
    } catch (err) {
      console.warn("restoreBackupZip: corrupted local manifest, initializing clean manifest:", err);
      currentManifest = {
        schema: 1,
        app: "folio",
        libraryId: crypto.randomUUID(),
        updatedAt: new Date().toISOString(),
        docs: []
      };
    }
  } else {
    currentManifest = {
      schema: 1,
      app: "folio",
      libraryId: backupManifest.libraryId || crypto.randomUUID(),
      updatedAt: new Date().toISOString(),
      docs: []
    };
  }

  const existingByHash = new Map(currentManifest.docs.map((d) => [d.contentHash, d]));
  const existingById = new Map(currentManifest.docs.map((d) => [d.id, d]));

  // Restore Documents
  let processed = 0;
  for (const bDoc of backupManifest.docs) {
    processed++;
    opts.onProgress?.({
      current: processed,
      total: backupManifest.docs.length,
      message: `Restoring doc: ${bDoc.title}`
    });

    const matchByHash = existingByHash.get(bDoc.contentHash);
    const matchById = existingById.get(bDoc.id);

    if (opts.mode === "merge") {
      // Rule: A document whose contentHash already exists is skipped
      if (matchByHash) {
        summary.duplicatesSkipped++;
        continue;
      }

      // Rule: For the same id, the newer updatedAt metadata wins
      if (matchById) {
        if (new Date(bDoc.updatedAt).getTime() > new Date(matchById.updatedAt).getTime()) {
          // Update meta.json from backup
          const metaPath = `library/docs/${bDoc.slug}/meta.json`;
          if (unzipped[metaPath]) {
            await store.write(metaPath, unzipped[metaPath]!);
          }
          matchById.title = bDoc.title;
          matchById.updatedAt = bDoc.updatedAt;
          summary.metadataUpdated++;
        }
        continue;
      }
    }

    // Write all files belonging to this document
    for (const f of bDoc.files) {
      const data = unzipped[f.path];
      if (data) {
        await store.write(f.path, data);
      }
    }

    // Write meta.json if present
    const docMetaPath = `library/docs/${bDoc.slug}/meta.json`;
    if (unzipped[docMetaPath]) {
      await store.write(docMetaPath, unzipped[docMetaPath]!);
    }

    currentManifest.docs.push(bDoc);
    existingByHash.set(bDoc.contentHash, bDoc);
    existingById.set(bDoc.id, bDoc);
    summary.newDocsAdded++;
  }

  // Restore/Merge User Data
  // 1. Annotations
  const backupAnnotData = unzipped["library/user/annotations.json"];
  if (backupAnnotData) {
    try {
      const backupAnnots: Annotation[] = JSON.parse(fflate.strFromU8(backupAnnotData));
      if (opts.mode === "replace") {
        await store.write("library/user/annotations.json", backupAnnotData);
        summary.annotationsMerged = backupAnnots.length;
      } else {
        const currentAnnots = await manager.getAnnotations();
        const annotMap = new Map(currentAnnots.map((a) => [a.id, a]));

        for (const bAnn of backupAnnots) {
          const curr = annotMap.get(bAnn.id);
          if (!curr) {
            annotMap.set(bAnn.id, bAnn);
            summary.annotationsMerged++;
          } else if (new Date(bAnn.updatedAt).getTime() > new Date(curr.updatedAt).getTime()) {
            annotMap.set(bAnn.id, bAnn);
            summary.annotationsMerged++;
          }
        }
        const mergedList = Array.from(annotMap.values());
        await store.write("library/user/annotations.json", JSON.stringify(mergedList, null, 2));
      }
    } catch {
      // ignore
    }
  }

  // 2. Progress
  const backupProgData = unzipped["library/user/progress.json"];
  if (backupProgData) {
    try {
      const backupProg: Record<string, Progress> = JSON.parse(fflate.strFromU8(backupProgData));
      if (opts.mode === "replace") {
        await store.write("library/user/progress.json", backupProgData);
        summary.progressUpdated = Object.keys(backupProg).length;
      } else {
        const currentProg = await manager.getAllProgress();
        for (const [docId, p] of Object.entries(backupProg)) {
          const curr = currentProg[docId];
          if (!curr || new Date(p.updatedAt).getTime() > new Date(curr.updatedAt).getTime()) {
            currentProg[docId] = p;
            summary.progressUpdated++;
          }
        }
        await store.write("library/user/progress.json", JSON.stringify(currentProg, null, 2));
      }
    } catch {
      // ignore
    }
  }

  // 3. Collections
  const backupCollData = unzipped["library/user/collections.json"];
  if (backupCollData) {
    try {
      const backupColls: Collection[] = JSON.parse(fflate.strFromU8(backupCollData));
      if (opts.mode === "replace") {
        await store.write("library/user/collections.json", backupCollData);
        summary.collectionsMerged = backupColls.length;
      } else {
        const currentColls = await manager.getCollections();
        const collMap = new Map(currentColls.map((c) => [c.id, c]));

        for (const bColl of backupColls) {
          const curr = collMap.get(bColl.id);
          if (!curr) {
            collMap.set(bColl.id, bColl);
            summary.collectionsMerged++;
          } else {
            // Union docIds
            const combined = Array.from(new Set([...curr.docIds, ...bColl.docIds]));
            curr.docIds = combined;
            summary.collectionsMerged++;
          }
        }
        const mergedColls = Array.from(collMap.values());
        await store.write("library/user/collections.json", JSON.stringify(mergedColls, null, 2));
      }
    } catch {
      // ignore
    }
  }

  // 4. Settings: Keep local settings in merge mode; restore in replace mode
  const backupSettingsData = unzipped["library/user/settings.json"];
  if (backupSettingsData && opts.mode === "replace") {
    await store.write("library/user/settings.json", backupSettingsData);
  }

  // Commit updated manifest
  currentManifest.updatedAt = new Date().toISOString();
  await store.write(MANIFEST_PATH, JSON.stringify(currentManifest, null, 2));

  // Rebuild search index
  opts.onProgress?.({
    current: 100,
    total: 100,
    message: "Rebuilding search index..."
  });
  try {
    await manager.rebuildSearchIndex();
  } catch (err) {
    console.warn("Failed to rebuild search index after restore:", err);
  }

  return { summary, verification };
}
