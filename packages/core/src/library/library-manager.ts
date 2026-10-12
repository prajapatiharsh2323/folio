import type { FileStore } from "../store/file-store.js";
import type { DocMeta, Manifest, ManifestDoc, ManifestFile, Progress } from "../types.js";
import type { ImportResult } from "../import/epub.js";
import { createDocSlug, sha256Hex } from "../util/hash.js";

export const MANIFEST_PATH = "library/manifest.json";

export class LibraryManager {
  constructor(private store: FileStore) {}

  /**
   * Reads the current library manifest or initializes a new one.
   */
  async getManifest(): Promise<Manifest> {
    if (await this.store.exists(MANIFEST_PATH)) {
      const text = await this.store.readText(MANIFEST_PATH);
      return JSON.parse(text) as Manifest;
    }
    const initial: Manifest = {
      schema: 1,
      app: "folio",
      libraryId: crypto.randomUUID(),
      updatedAt: new Date().toISOString(),
      docs: []
    };
    await this.store.write(MANIFEST_PATH, JSON.stringify(initial, null, 2));
    return initial;
  }

  /**
   * Checks if an incoming contentHash already exists in the manifest.
   */
  async findDuplicate(contentHash: string): Promise<ManifestDoc | undefined> {
    const manifest = await this.getManifest();
    return manifest.docs.find((d) => d.contentHash === contentHash);
  }

  /**
   * Commits an imported document into the FileStore following the exact Section 4 write order rules:
   * Rule 1: original, clean and reading files first, then meta.json, then update manifest.json last.
   */
  async addDocument(importResult: ImportResult): Promise<DocMeta> {
    const manifest = await this.getManifest();
    const id = crypto.randomUUID();
    const now = new Date().toISOString();
    const slug = createDocSlug(importResult.meta.title);

    const docDir = `library/docs/${slug}`;
    const manifestFiles: ManifestFile[] = [];

    // 1. Write original file
    const origPath = `${docDir}/original/${importResult.original.name}`;
    await this.store.write(origPath, importResult.original.bytes);
    manifestFiles.push({
      path: origPath,
      size: importResult.original.bytes.byteLength,
      sha256: await sha256Hex(importResult.original.bytes)
    });

    // 2. Write clean files if present
    let cleanIndexRel: string | undefined;
    if (importResult.clean) {
      const cleanPath = `${docDir}/clean/index.html`;
      const cleanBytes = new TextEncoder().encode(importResult.clean.indexHtml);
      await this.store.write(cleanPath, cleanBytes);
      manifestFiles.push({
        path: cleanPath,
        size: cleanBytes.byteLength,
        sha256: await sha256Hex(cleanBytes)
      });
      cleanIndexRel = `clean/index.html`;

      for (const img of importResult.clean.images) {
        const imgPath = `${docDir}/clean/images/${img.name}`;
        await this.store.write(imgPath, img.bytes);
        manifestFiles.push({
          path: imgPath,
          size: img.bytes.byteLength,
          sha256: await sha256Hex(img.bytes)
        });
      }
    }

    // 3. Write reading edition if present
    let readingRel: string | undefined;
    if (importResult.reading) {
      const readingPath = `${docDir}/reading/${importResult.reading.name}`;
      await this.store.write(readingPath, importResult.reading.bytes);
      manifestFiles.push({
        path: readingPath,
        size: importResult.reading.bytes.byteLength,
        sha256: await sha256Hex(importResult.reading.bytes)
      });
      readingRel = `reading/${importResult.reading.name}`;
    }

    // 4. Construct complete DocMeta and write meta.json
    const meta: DocMeta = {
      ...importResult.meta,
      id,
      slug,
      importedAt: now,
      files: {
        original: `original/${importResult.original.name}`,
        clean: cleanIndexRel,
        reading: readingRel
      }
    };

    const metaBytes = new TextEncoder().encode(JSON.stringify(meta, null, 2));
    const metaPath = `${docDir}/meta.json`;
    await this.store.write(metaPath, metaBytes);
    manifestFiles.push({
      path: metaPath,
      size: metaBytes.byteLength,
      sha256: await sha256Hex(metaBytes)
    });

    // 5. Commit to manifest.json (the commit point)
    const manifestDoc: ManifestDoc = {
      id,
      slug,
      title: meta.title,
      contentHash: meta.contentHash,
      updatedAt: now,
      files: manifestFiles
    };

    manifest.docs.push(manifestDoc);
    manifest.updatedAt = now;
    await this.store.write(MANIFEST_PATH, JSON.stringify(manifest, null, 2));

    return meta;
  }

  /**
   * Lists all documents registered in the manifest.
   */
  async listDocuments(): Promise<DocMeta[]> {
    const manifest = await this.getManifest();
    const docs: DocMeta[] = [];

    for (const d of manifest.docs) {
      const metaPath = `library/docs/${d.slug}/meta.json`;
      if (await this.store.exists(metaPath)) {
        try {
          const text = await this.store.readText(metaPath);
          docs.push(JSON.parse(text) as DocMeta);
        } catch {
          // If damaged, skip or handle via recovery
        }
      }
    }

    return docs;
  }

  /**
   * Startup recovery check as specified in Section 4 Rule 2:
   * A docs/ folder not listed in the manifest is an interrupted import.
   */
  async recoverInterruptedImports(): Promise<string[]> {
    const manifest = await this.getManifest();
    const registeredSlugs = new Set(manifest.docs.map((d) => d.slug));
    const existingFolders = await this.store.list("library/docs");
    const uncommitted: string[] = [];

    for (const folder of existingFolders) {
      if (!registeredSlugs.has(folder)) {
        uncommitted.push(folder);
      }
    }

    return uncommitted;
  }

  /**
   * Saves reader progress in user/progress.json.
   */
  async saveProgress(progress: Progress): Promise<void> {
    const progressPath = "library/user/progress.json";
    let allProgress: Record<string, Progress> = {};
    if (await this.store.exists(progressPath)) {
      try {
        allProgress = JSON.parse(await this.store.readText(progressPath));
      } catch {
        allProgress = {};
      }
    }
    allProgress[progress.docId] = progress;
    await this.store.write(progressPath, JSON.stringify(allProgress, null, 2));
  }

  /**
   * Retrieves reader progress.
   */
  async getProgress(docId: string): Promise<Progress | undefined> {
    const progressPath = "library/user/progress.json";
    if (await this.store.exists(progressPath)) {
      try {
        const allProgress: Record<string, Progress> = JSON.parse(await this.store.readText(progressPath));
        return allProgress[docId];
      } catch {
        return undefined;
      }
    }
    return undefined;
  }
}
