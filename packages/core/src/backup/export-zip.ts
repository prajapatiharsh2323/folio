import * as fflate from "fflate";
import type { FileStore } from "../store/file-store.js";
import { sha256Hex } from "../util/hash.js";
import type { BackupMeta } from "./types.js";
import type { Manifest } from "../types.js";

/**
 * Section 8 Compression rules:
 * - Store (level 0) EPUB, PDF and images, which are already compressed
 * - Level 6 for JSON and HTML
 */
function getCompressionLevel(path: string): 0 | 6 {
  const lower = path.toLowerCase();
  if (
    lower.endsWith(".epub") ||
    lower.endsWith(".pdf") ||
    lower.endsWith(".jpg") ||
    lower.endsWith(".jpeg") ||
    lower.endsWith(".png") ||
    lower.endsWith(".webp")
  ) {
    return 0; // Store uncompressed
  }
  return 6; // Deflate
}

/**
 * Generates standard backup filename: folio-backup-YYYYMMDD-HHMM.zip
 */
export function generateBackupFilename(date: Date = new Date()): string {
  const pad = (n: number) => n.toString().padStart(2, "0");
  const yyyy = date.getFullYear();
  const mm = pad(date.getMonth() + 1);
  const dd = pad(date.getDate());
  const hh = pad(date.getHours());
  const min = pad(date.getMinutes());
  return `folio-backup-${yyyy}${mm}${dd}-${hh}${min}.zip`;
}

export interface ExportBackupOptions {
  scope?: "all" | string[]; // "all" or specific docIds
  onProgress?: (progress: { current: number; total: number; filename: string }) => void;
}

/**
 * Streams the backup ZIP chunk by chunk into an async generator or consumer callback.
 * Large libraries never sit completely in memory.
 */
export async function* streamBackupZip(
  store: FileStore,
  opts: ExportBackupOptions = {}
): AsyncGenerator<Uint8Array, void, unknown> {
  const manifestPath = "library/manifest.json";
  if (!(await store.exists(manifestPath))) {
    throw new Error("No library manifest found to back up");
  }

  const manifestText = await store.readText(manifestPath);
  const manifest: Manifest = JSON.parse(manifestText);
  const manifestSha256 = await sha256Hex(new TextEncoder().encode(manifestText));

  // Determine files to include
  const filesToInclude: string[] = [];
  filesToInclude.push(manifestPath);

  // Filter docs if scope is restricted
  const targetDocs = opts.scope && opts.scope !== "all"
    ? manifest.docs.filter((d) => opts.scope!.includes(d.id))
    : manifest.docs;

  for (const d of targetDocs) {
    // Add meta.json
    const metaPath = `library/docs/${d.slug}/meta.json`;
    if (await store.exists(metaPath)) {
      filesToInclude.push(metaPath);
    }
    // Add all registered files in manifest for this document
    for (const f of d.files) {
      if (await store.exists(f.path)) {
        filesToInclude.push(f.path);
      }
    }
  }

  // User data (annotations, progress, collections, settings)
  const userFiles = [
    "library/user/annotations.json",
    "library/user/progress.json",
    "library/user/collections.json",
    "library/user/settings.json"
  ];
  for (const uf of userFiles) {
    if (await store.exists(uf)) {
      filesToInclude.push(uf);
    }
  }

  const backupMeta: BackupMeta = {
    schema: 1,
    createdAt: new Date().toISOString(),
    appVersion: "0.1.0",
    docCount: targetDocs.length,
    manifestSha256
  };

  const chunksQueue: Uint8Array[] = [];

  const zip = new fflate.Zip((err, chunk) => {
    if (err) throw err;
    if (chunk) {
      chunksQueue.push(chunk);
    }
  });

  // 1. Add backup.json
  const backupJsonBytes = new TextEncoder().encode(JSON.stringify(backupMeta, null, 2));
  const backupJsonFile = new fflate.ZipPassThrough("backup.json");
  zip.add(backupJsonFile);
  backupJsonFile.push(backupJsonBytes, true);

  // 2. Stream all files in library
  let count = 0;
  for (const filePath of filesToInclude) {
    count++;
    opts.onProgress?.({ current: count, total: filesToInclude.length, filename: filePath });

    const fileBytes = await store.read(filePath);
    const level = getCompressionLevel(filePath);

    // Archive path: strip leading "library/" so structure is clean
    const archivePath = filePath.startsWith("library/")
      ? filePath
      : `library/${filePath}`;

    if (level === 0) {
      const zFile = new fflate.ZipPassThrough(archivePath);
      zip.add(zFile);
      zFile.push(fileBytes, true);
    } else {
      const zFile = new fflate.ZipDeflate(archivePath, { level });
      zip.add(zFile);
      zFile.push(fileBytes, true);
    }

    // Yield any chunks buffered so far
    while (chunksQueue.length > 0) {
      yield chunksQueue.shift()!;
    }
  }

  zip.end();

  // Yield any final chunks
  while (chunksQueue.length > 0) {
    yield chunksQueue.shift()!;
  }
}

/**
 * Convenience helper to export backup as a single Uint8Array (for tests or smaller libraries).
 */
export async function exportBackupBytes(
  store: FileStore,
  opts: ExportBackupOptions = {}
): Promise<{ filename: string; bytes: Uint8Array }> {
  const parts: Uint8Array[] = [];
  for await (const chunk of streamBackupZip(store, opts)) {
    parts.push(chunk);
  }

  const totalLength = parts.reduce((acc, p) => acc + p.byteLength, 0);
  const fullBytes = new Uint8Array(totalLength);
  let offset = 0;
  for (const p of parts) {
    fullBytes.set(p, offset);
    offset += p.byteLength;
  }

  return {
    filename: generateBackupFilename(),
    bytes: fullBytes
  };
}
