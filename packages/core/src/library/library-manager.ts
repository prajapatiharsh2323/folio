import type { FileStore } from "../store/file-store.js";
import type {
  DocMeta,
  Manifest,
  ManifestDoc,
  ManifestFile,
  Progress,
  Annotation,
  Collection
} from "../types.js";
import type { ImportResult } from "../import/epub.js";
import { createDocSlug, sha256Hex } from "../util/hash.js";
import { SearchIndex, type SearchDocEntry } from "../search/index.js";

export const MANIFEST_PATH = "library/manifest.json";

export class LibraryManager {
  constructor(private store: FileStore) {}

  private manifestInitPromise: Promise<Manifest> | null = null;

  /**
   * Reads the current library manifest or initializes a new one.
   */
  async getManifest(): Promise<Manifest> {
    if (await this.store.exists(MANIFEST_PATH)) {
      try {
        const text = await this.store.readText(MANIFEST_PATH);
        return JSON.parse(text) as Manifest;
      } catch (err) {
        console.warn("getManifest: corrupted manifest, initializing clean manifest:", err);
      }
    }
    if (this.manifestInitPromise) {
      return this.manifestInitPromise;
    }
    this.manifestInitPromise = (async () => {
      if (await this.store.exists(MANIFEST_PATH)) {
        try {
          const text = await this.store.readText(MANIFEST_PATH);
          return JSON.parse(text) as Manifest;
        } catch {
          // ignore
        }
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
    })().finally(() => {
      this.manifestInitPromise = null;
    });
    return this.manifestInitPromise;
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

    // 5. Save search text in index/text/<docId>.json if present
    if (importResult.searchText && importResult.searchText.length > 0) {
      await this.store.write(
        `library/index/text/${id}.json`,
        JSON.stringify(importResult.searchText, null, 2)
      );
      try {
        const searchIndex = await this.getSearchIndex();
        const entries: SearchDocEntry[] = importResult.searchText.map((st) => ({
          id: `${id}:${st.chapterId}`,
          docId: id,
          chapterId: st.chapterId,
          title: meta.title,
          chapterTitle: st.title,
          author: meta.author || "",
          tags: meta.tags,
          text: st.text
        }));
        searchIndex.addAll(entries);
        await this.saveSearchIndex(searchIndex);
      } catch {
        // Can be rebuilt on demand
      }
    }

    // 6. Commit to manifest.json (the commit point)
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
   * Updates metadata (title, author, tags, collections) for a document.
   * Section 5: Edits update library and search index at once.
   */
  async updateDocMeta(
    id: string,
    updates: Partial<Pick<DocMeta, "title" | "author" | "tags" | "collectionIds">>
  ): Promise<DocMeta> {
    const manifest = await this.getManifest();
    const mDoc = manifest.docs.find((d) => d.id === id);
    if (!mDoc) {
      throw new Error(`Document not found in manifest: ${id}`);
    }
    const metaPath = `library/docs/${mDoc.slug}/meta.json`;
    const metaText = await this.store.readText(metaPath);
    const currentMeta: DocMeta = JSON.parse(metaText);

    const updatedMeta: DocMeta = {
      ...currentMeta,
      ...updates,
      tags: updates.tags !== undefined ? updates.tags : currentMeta.tags,
      collectionIds:
        updates.collectionIds !== undefined ? updates.collectionIds : currentMeta.collectionIds
    };

    const updatedMetaBytes = new TextEncoder().encode(JSON.stringify(updatedMeta, null, 2));
    await this.store.write(metaPath, updatedMetaBytes);

    // Update manifest entry
    const now = new Date().toISOString();
    mDoc.title = updatedMeta.title;
    mDoc.updatedAt = now;
    manifest.updatedAt = now;

    // Update sha256 and size in manifest files
    const metaFileEntry = mDoc.files.find((f) => f.path === metaPath);
    if (metaFileEntry) {
      metaFileEntry.size = updatedMetaBytes.byteLength;
      metaFileEntry.sha256 = await sha256Hex(updatedMetaBytes);
    }
    await this.store.write(MANIFEST_PATH, JSON.stringify(manifest, null, 2));

    // Update search index
    try {
      const searchTexts = await this.getSearchText(id);
      const searchIndex = await this.getSearchIndex();
      searchIndex.removeByDocId(id);
      if (searchTexts && searchTexts.length > 0) {
        const entries: SearchDocEntry[] = searchTexts.map((st) => ({
          id: `${id}:${st.chapterId}`,
          docId: id,
          chapterId: st.chapterId,
          title: updatedMeta.title,
          chapterTitle: st.title,
          author: updatedMeta.author || "",
          tags: updatedMeta.tags,
          text: st.text
        }));
        searchIndex.addAll(entries);
      } else {
        searchIndex.add({
          id: `${id}:meta`,
          docId: id,
          chapterId: "",
          title: updatedMeta.title,
          chapterTitle: "",
          author: updatedMeta.author || "",
          tags: updatedMeta.tags || [],
          text: ""
        });
      }
      await this.saveSearchIndex(searchIndex);
    } catch {
      // Rebuildable on demand
    }

    return updatedMeta;
  }

  /**
   * Deleting a document removes its folder, its annotations and progress, its entries
   * in collections and the search index, then rewrites the manifest (Section 4 Rule 5).
   */
  async deleteDocument(id: string): Promise<void> {
    const manifest = await this.getManifest();
    const docIdx = manifest.docs.findIndex((d) => d.id === id);
    if (docIdx === -1) return;

    const mDoc = manifest.docs[docIdx]!;
    const docDir = `library/docs/${mDoc.slug}`;

    // 1. Remove folder
    try {
      await this.store.remove(docDir, { recursive: true });
    } catch (err) {
      console.warn(`Could not remove ${docDir}:`, err);
    }

    // 2. Remove search text
    const textPath = `library/index/text/${id}.json`;
    if (await this.store.exists(textPath)) {
      try {
        await this.store.remove(textPath);
      } catch {
        // ignore
      }
    }

    // 3. Remove from search index
    try {
      const searchIndex = await this.getSearchIndex();
      searchIndex.removeByDocId(id);
      await this.saveSearchIndex(searchIndex);
    } catch {
      // ignore
    }

    // 4. Remove progress
    const progressPath = "library/user/progress.json";
    if (await this.store.exists(progressPath)) {
      try {
        const allProgress: Record<string, Progress> = JSON.parse(
          await this.store.readText(progressPath)
        );
        if (allProgress[id]) {
          delete allProgress[id];
          await this.store.write(progressPath, JSON.stringify(allProgress, null, 2));
        }
      } catch {
        // ignore
      }
    }

    // 5. Remove annotations
    const annotPath = "library/user/annotations.json";
    if (await this.store.exists(annotPath)) {
      try {
        const allAnn: Annotation[] = JSON.parse(await this.store.readText(annotPath));
        const filtered = allAnn.filter((a) => a.docId !== id);
        await this.store.write(annotPath, JSON.stringify(filtered, null, 2));
      } catch {
        // ignore
      }
    }

    // 6. Remove from collections
    const collPath = "library/user/collections.json";
    if (await this.store.exists(collPath)) {
      try {
        const allColl: Collection[] = JSON.parse(await this.store.readText(collPath));
        let changed = false;
        for (const coll of allColl) {
          if (coll.docIds.includes(id)) {
            coll.docIds = coll.docIds.filter((dId) => dId !== id);
            changed = true;
          }
        }
        if (changed) {
          await this.store.write(collPath, JSON.stringify(allColl, null, 2));
        }
      } catch {
        // ignore
      }
    }

    // 7. Remove from manifest
    manifest.docs.splice(docIdx, 1);
    manifest.updatedAt = new Date().toISOString();
    await this.store.write(MANIFEST_PATH, JSON.stringify(manifest, null, 2));
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
        const allProgress: Record<string, Progress> = JSON.parse(
          await this.store.readText(progressPath)
        );
        return allProgress[docId];
      } catch {
        return undefined;
      }
    }
    return undefined;
  }

  /**
   * Retrieves all progress.
   */
  async getAllProgress(): Promise<Record<string, Progress>> {
    const progressPath = "library/user/progress.json";
    if (await this.store.exists(progressPath)) {
      try {
        return JSON.parse(await this.store.readText(progressPath));
      } catch {
        return {};
      }
    }
    return {};
  }

  // --- Annotations (Section 4 & Section 7) ---

  async getAnnotations(docId?: string): Promise<Annotation[]> {
    const annotPath = "library/user/annotations.json";
    if (!(await this.store.exists(annotPath))) return [];
    try {
      const items: Annotation[] = JSON.parse(await this.store.readText(annotPath));
      return docId ? items.filter((a) => a.docId === docId) : items;
    } catch {
      return [];
    }
  }

  async addAnnotation(annotation: Annotation): Promise<void> {
    const annotPath = "library/user/annotations.json";
    const items = await this.getAnnotations();
    items.push(annotation);
    await this.store.write(annotPath, JSON.stringify(items, null, 2));
  }

  async updateAnnotation(annotation: Annotation): Promise<void> {
    const annotPath = "library/user/annotations.json";
    const items = await this.getAnnotations();
    const idx = items.findIndex((a) => a.id === annotation.id);
    if (idx !== -1) {
      items[idx] = annotation;
    } else {
      items.push(annotation);
    }
    await this.store.write(annotPath, JSON.stringify(items, null, 2));
  }

  async deleteAnnotation(id: string): Promise<void> {
    const annotPath = "library/user/annotations.json";
    const items = await this.getAnnotations();
    const filtered = items.filter((a) => a.id !== id);
    await this.store.write(annotPath, JSON.stringify(filtered, null, 2));
  }

  // --- Collections (Section 4 & Section 5) ---

  async getCollections(): Promise<Collection[]> {
    const collPath = "library/user/collections.json";
    if (!(await this.store.exists(collPath))) return [];
    try {
      return JSON.parse(await this.store.readText(collPath));
    } catch {
      return [];
    }
  }

  async createCollection(name: string): Promise<Collection> {
    const collPath = "library/user/collections.json";
    const collections = await this.getCollections();
    const newColl: Collection = {
      id: crypto.randomUUID(),
      name,
      docIds: [],
      createdAt: new Date().toISOString()
    };
    collections.push(newColl);
    await this.store.write(collPath, JSON.stringify(collections, null, 2));
    return newColl;
  }

  async renameCollection(id: string, name: string): Promise<Collection> {
    const collPath = "library/user/collections.json";
    const collections = await this.getCollections();
    const coll = collections.find((c) => c.id === id);
    if (!coll) throw new Error(`Collection not found: ${id}`);
    coll.name = name;
    await this.store.write(collPath, JSON.stringify(collections, null, 2));
    return coll;
  }

  async deleteCollection(id: string): Promise<void> {
    const collPath = "library/user/collections.json";
    const collections = await this.getCollections();
    const filtered = collections.filter((c) => c.id !== id);
    await this.store.write(collPath, JSON.stringify(filtered, null, 2));
  }

  async setDocCollections(docId: string, collectionIds: string[]): Promise<void> {
    const collPath = "library/user/collections.json";
    const collections = await this.getCollections();
    for (const coll of collections) {
      const hasDoc = coll.docIds.includes(docId);
      const shouldHave = collectionIds.includes(coll.id);
      if (shouldHave && !hasDoc) {
        coll.docIds.push(docId);
      } else if (!shouldHave && hasDoc) {
        coll.docIds = coll.docIds.filter((d) => d !== docId);
      }
    }
    await this.store.write(collPath, JSON.stringify(collections, null, 2));
    await this.updateDocMeta(docId, { collectionIds });
  }

  // --- Search Index & Text (Section 7) ---

  async getSearchIndex(): Promise<SearchIndex> {
    const indexPath = "library/index/search.json";
    if (await this.store.exists(indexPath)) {
      try {
        const text = await this.store.readText(indexPath);
        return SearchIndex.loadJSON(text);
      } catch (err) {
        console.warn("Failed to load search index, rebuilding:", err);
      }
    }
    return this.rebuildSearchIndex();
  }

  async saveSearchIndex(index: SearchIndex): Promise<void> {
    const indexPath = "library/index/search.json";
    await this.store.write(indexPath, index.toJSON());
  }

  async getSearchText(
    docId: string
  ): Promise<{ chapterId: string; title: string; text: string }[]> {
    const textPath = `library/index/text/${docId}.json`;
    if (await this.store.exists(textPath)) {
      try {
        return JSON.parse(await this.store.readText(textPath));
      } catch {
        return [];
      }
    }
    return [];
  }

  /**
   * Re-extracts search text from stored document files if index/text was excluded from backup or sync.
   */
  async extractDocSearchText(
    doc: DocMeta
  ): Promise<{ chapterId: string; title: string; text: string }[]> {
    try {
      // 1. Article / clip with clean/index.html
      const cleanPath = doc.files.clean
        ? `library/docs/${doc.slug}/${doc.files.clean}`
        : `library/docs/${doc.slug}/clean/index.html`;

      if (await this.store.exists(cleanPath)) {
        const html = await this.store.readText(cleanPath);
        const text = html
          .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, "")
          .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, "")
          .replace(/<[^>]+>/g, " ")
          .replace(/&nbsp;/g, " ")
          .replace(/&amp;/g, "&")
          .replace(/&lt;/g, "<")
          .replace(/&gt;/g, ">")
          .replace(/\s+/g, " ")
          .trim();
        return [{ chapterId: "ch-1", title: doc.title, text }];
      }

      // 2. Text file
      if (doc.sourceFormat === "txt" && doc.files.original) {
        const origPath = `library/docs/${doc.slug}/${doc.files.original}`;
        if (await this.store.exists(origPath)) {
          const text = await this.store.readText(origPath);
          return [{ chapterId: "ch-1", title: doc.title, text: text.trim() }];
        }
      }

      // 3. EPUB book
      if (doc.sourceFormat === "epub" || doc.files.reading?.endsWith(".epub")) {
        const epubPath = doc.files.reading
          ? `library/docs/${doc.slug}/${doc.files.reading}`
          : `library/docs/${doc.slug}/${doc.files.original}`;

        if (epubPath && (await this.store.exists(epubPath))) {
          const bytes = await this.store.read(epubPath);
          const { importEpub } = await import("../import/epub.js");
          const res = await importEpub(bytes, doc.files.original || "book.epub");
          if (res.searchText && res.searchText.length > 0) {
            return res.searchText;
          }
        }
      }

      // 4. PDF document
      if (doc.sourceFormat === "pdf" && doc.files.original) {
        const pdfPath = `library/docs/${doc.slug}/${doc.files.original}`;
        if (await this.store.exists(pdfPath)) {
          const bytes = await this.store.read(pdfPath);
          const { importPdf } = await import("../import/pdf.js");
          const res = await importPdf(bytes, doc.files.original);
          if (res.searchText && res.searchText.length > 0) {
            return res.searchText;
          }
        }
      }
    } catch {
      // Re-extraction failed (corrupted or stub data) - fallback to metadata entry
    }
    return [];
  }

  async rebuildSearchIndex(): Promise<SearchIndex> {
    const index = new SearchIndex();
    const docs = await this.listDocuments();
    for (const doc of docs) {
      let searchTexts = await this.getSearchText(doc.id);
      if (!searchTexts || searchTexts.length === 0) {
        // Re-extract from source files if index/text was omitted from backup/sync
        searchTexts = await this.extractDocSearchText(doc);
        if (searchTexts && searchTexts.length > 0) {
          try {
            await this.store.write(
              `library/index/text/${doc.id}.json`,
              JSON.stringify(searchTexts, null, 2)
            );
          } catch {
            // ignore
          }
        }
      }

      if (searchTexts && searchTexts.length > 0) {
        const entries: SearchDocEntry[] = searchTexts.map((st) => ({
          id: `${doc.id}:${st.chapterId}`,
          docId: doc.id,
          chapterId: st.chapterId,
          title: doc.title,
          chapterTitle: st.title,
          author: doc.author || "",
          tags: doc.tags,
          text: st.text
        }));
        index.addAll(entries);
      } else {
        index.add({
          id: `${doc.id}:meta`,
          docId: doc.id,
          chapterId: "",
          title: doc.title,
          chapterTitle: "",
          author: doc.author || "",
          tags: doc.tags || [],
          text: ""
        });
      }
    }
    await this.saveSearchIndex(index);
    return index;
  }
}
