import { describe, it, expect, beforeEach } from "vitest";
import { MemoryStore } from "../src/store/memory-store.js";
import { LibraryManager } from "../src/library/library-manager.js";
import type { ImportResult } from "../src/import/epub.js";
import type { Annotation } from "../src/types.js";

describe("LibraryManager - Phase 3 Library & Data Management", () => {
  let store: MemoryStore;
  let manager: LibraryManager;

  beforeEach(() => {
    store = new MemoryStore();
    manager = new LibraryManager(store);
  });

  const sampleDoc: ImportResult = {
    meta: {
      schema: 1,
      title: "Test Book",
      author: "Jane Doe",
      kind: "book",
      sourceFormat: "epub",
      capturedAt: new Date().toISOString(),
      contentHash: "hash-12345",
      searchable: true,
      tags: ["fiction", "classics"],
      collectionIds: [],
      chapters: [
        { id: "ch1", title: "Chapter 1", order: 1, href: "text/ch1.xhtml" }
      ]
    },
    original: {
      name: "book.epub",
      bytes: new Uint8Array([1, 2, 3, 4])
    },
    searchText: [
      { chapterId: "ch1", title: "Chapter 1", text: "It was the best of times, it was the worst of times." }
    ]
  };

  it("adds document, indexes search text, and retrieves indexed hits", async () => {
    const docMeta = await manager.addDocument(sampleDoc);
    expect(docMeta.id).toBeDefined();

    // Verify search text stored
    const searchTexts = await manager.getSearchText(docMeta.id);
    expect(searchTexts.length).toBe(1);
    expect(searchTexts[0]?.text).toContain("best of times");

    // Search index query
    const index = await manager.getSearchIndex();
    const hits = index.search("times");
    expect(hits.length).toBe(1);
    expect(hits[0]?.docId).toBe(docMeta.id);
  });

  it("updates document metadata (title, author, tags) and keeps search index synchronized", async () => {
    const docMeta = await manager.addDocument(sampleDoc);

    const updated = await manager.updateDocMeta(docMeta.id, {
      title: "Updated Great Book",
      author: "Jane Smith",
      tags: ["masterpiece"]
    });

    expect(updated.title).toBe("Updated Great Book");
    expect(updated.author).toBe("Jane Smith");
    expect(updated.tags).toEqual(["masterpiece"]);

    // Check search index reflects updated title
    const index = await manager.getSearchIndex();
    const hits = index.search("Smith");
    expect(hits.length).toBe(1);
    expect(hits[0]?.title).toBe("Updated Great Book");
  });

  it("deleting a document removes folder, search text, annotations, collections link, progress, and search index", async () => {
    const docMeta = await manager.addDocument(sampleDoc);

    // Add annotation
    const annotation: Annotation = {
      id: "ann-1",
      docId: docMeta.id,
      quote: "best of times",
      prefix: "It was the ",
      suffix: ", it was the",
      kind: "highlight",
      color: "yellow",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };
    await manager.addAnnotation(annotation);

    // Add progress
    await manager.saveProgress({
      docId: docMeta.id,
      location: "epubcfi(/6/2)",
      percent: 0.25,
      updatedAt: new Date().toISOString()
    });

    // Add collection
    const collection = await manager.createCollection("Favorites");
    await manager.setDocCollections(docMeta.id, [collection.id]);

    // Verify setup
    expect(await manager.getAnnotations(docMeta.id)).toHaveLength(1);
    expect(await manager.getProgress(docMeta.id)).toBeDefined();
    const collsBefore = await manager.getCollections();
    expect(collsBefore[0]?.docIds).toContain(docMeta.id);

    // Delete document
    await manager.deleteDocument(docMeta.id);

    // Verify document removed from manifest
    const manifest = await manager.getManifest();
    expect(manifest.docs.find((d) => d.id === docMeta.id)).toBeUndefined();

    // Verify folder removed
    expect(await store.exists(`library/docs/${docMeta.slug}/meta.json`)).toBe(false);

    // Verify search text and search index removed
    expect(await store.exists(`library/index/text/${docMeta.id}.json`)).toBe(false);
    const index = await manager.getSearchIndex();
    expect(index.search("times")).toHaveLength(0);

    // Verify annotations and progress removed
    expect(await manager.getAnnotations(docMeta.id)).toHaveLength(0);
    expect(await manager.getProgress(docMeta.id)).toBeUndefined();

    // Verify removed from collection
    const collsAfter = await manager.getCollections();
    expect(collsAfter[0]?.docIds).not.toContain(docMeta.id);
  });

  it("manages user collections (create, rename, delete) without deleting documents", async () => {
    const docMeta = await manager.addDocument(sampleDoc);
    const coll = await manager.createCollection("Summer Reading");
    expect(coll.name).toBe("Summer Reading");

    await manager.setDocCollections(docMeta.id, [coll.id]);
    const updatedColls = await manager.getCollections();
    expect(updatedColls[0]?.docIds).toContain(docMeta.id);

    const renamed = await manager.renameCollection(coll.id, "Winter Reading");
    expect(renamed.name).toBe("Winter Reading");

    // Deleting collection preserves document
    await manager.deleteCollection(coll.id);
    const finalColls = await manager.getCollections();
    expect(finalColls).toHaveLength(0);
    const docs = await manager.listDocuments();
    expect(docs).toHaveLength(1);
  });

  it("rebuilds search index across documents correctly", async () => {
    const docMeta = await manager.addDocument(sampleDoc);

    // Corrupt or remove index file
    await store.remove("library/index/search.json");

    const rebuilt = await manager.rebuildSearchIndex();
    const hits = rebuilt.search("times");
    expect(hits.length).toBe(1);
    expect(hits[0]?.docId).toBe(docMeta.id);
  });
});
