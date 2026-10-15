import { describe, it, expect, beforeEach } from "vitest";
import { MemoryStore } from "../src/store/memory-store.js";
import { LibraryManager } from "../src/library/library-manager.js";
import type { ImportResult } from "../src/import/epub.js";
import type { Annotation } from "../src/types.js";

describe("Reader Stabilization Acceptance & Progress Contracts", () => {
  let store: MemoryStore;
  let manager: LibraryManager;

  beforeEach(() => {
    store = new MemoryStore();
    manager = new LibraryManager(store);
  });

  const sampleDoc: ImportResult = {
    meta: {
      schema: 1,
      title: "The Solitary Reader",
      author: "Arthur Pendelton",
      kind: "book",
      sourceFormat: "epub",
      capturedAt: new Date().toISOString(),
      contentHash: "hash-sr-001",
      searchable: true,
      tags: ["novel"],
      collectionIds: [],
      chapters: [
        { id: "ch1", title: "Chapter I: The Discovery", order: 1, href: "text/ch1.xhtml" },
        { id: "ch2", title: "Chapter II: The Library at Night", order: 2, href: "text/ch2.xhtml" }
      ]
    },
    original: {
      name: "novel.epub",
      bytes: new Uint8Array([1, 2, 3, 4, 5])
    },
    searchText: [
      {
        chapterId: "ch1",
        title: "Chapter I: The Discovery",
        text: "The sun slanted across the cedar bookshelves, illuminating dust motes dancing in the quiet air."
      },
      {
        chapterId: "ch2",
        title: "Chapter II: The Library at Night",
        text: "Silence enveloped the sanctuary as midnight chimed from the grandfather clock."
      }
    ]
  };

  it("stores and updates reading progress accurately with CFI and percentage", async () => {
    const docMeta = await manager.addDocument(sampleDoc);

    await manager.saveProgress({
      docId: docMeta.id,
      location: "epubcfi(/6/4[ch1]!/4/2/1:0)",
      percent: 15.5,
      updatedAt: new Date().toISOString()
    });

    const progress = await manager.getProgress(docMeta.id);
    expect(progress).not.toBeNull();
    expect(progress?.location).toBe("epubcfi(/6/4[ch1]!/4/2/1:0)");
    expect(progress?.percent).toBe(15.5);

    // Update progress on page advance
    await manager.saveProgress({
      docId: docMeta.id,
      location: "epubcfi(/6/6[ch2]!/4/2/1:10)",
      percent: 62.0,
      updatedAt: new Date().toISOString()
    });

    const updatedProgress = await manager.getProgress(docMeta.id);
    expect(updatedProgress?.percent).toBe(62.0);
    expect(updatedProgress?.location).toBe("epubcfi(/6/6[ch2]!/4/2/1:10)");
  });

  it("handles bookmark toggling without duplicate creation", async () => {
    const docMeta = await manager.addDocument(sampleDoc);

    const bookmark: Annotation = {
      id: "bm-001",
      docId: docMeta.id,
      kind: "bookmark",
      color: "yellow",
      location: "epubcfi(/6/4[ch1]!/4/2/1:0)",
      createdAt: new Date().toISOString()
    };

    // Add bookmark
    await manager.addAnnotation(bookmark);
    let annotations = await manager.getAnnotations(docMeta.id);
    expect(annotations.length).toBe(1);
    expect(annotations[0]?.id).toBe("bm-001");
    expect(annotations[0]?.kind).toBe("bookmark");

    // Remove bookmark
    await manager.deleteAnnotation("bm-001");
    annotations = await manager.getAnnotations(docMeta.id);
    expect(annotations.length).toBe(0);
  });

  it("handles highlights with notes and preserves highlight color changes", async () => {
    const docMeta = await manager.addDocument(sampleDoc);

    const highlight: Annotation = {
      id: "hl-001",
      docId: docMeta.id,
      kind: "highlight",
      color: "yellow",
      quote: "dust motes dancing in the quiet air",
      location: "epubcfi(/6/4[ch1]!/4/2/1:15)",
      createdAt: new Date().toISOString()
    };

    await manager.addAnnotation(highlight);
    let annotations = await manager.getAnnotations(docMeta.id);
    expect(annotations.length).toBe(1);
    expect(annotations[0]?.color).toBe("yellow");

    // Update color and add a note
    const updatedHighlight: Annotation = {
      ...highlight,
      color: "green",
      note: "Atmospheric imagery",
      updatedAt: new Date().toISOString()
    };

    await manager.updateAnnotation(updatedHighlight);
    annotations = await manager.getAnnotations(docMeta.id);
    expect(annotations.length).toBe(1);
    expect(annotations[0]?.color).toBe("green");
    expect(annotations[0]?.note).toBe("Atmospheric imagery");
  });

  it("searches chapter content and returns matching snippets across chapters", async () => {
    const docMeta = await manager.addDocument(sampleDoc);
    const index = await manager.getSearchIndex();

    // Search for "cedar bookshelves"
    const hits = index.search("cedar");
    expect(hits.length).toBe(1);
    expect(hits[0]?.docId).toBe(docMeta.id);
    expect(hits[0]?.hits[0]?.chapterId).toBe("ch1");

    // Search for "midnight" in chapter 2
    const midnightHits = index.search("midnight");
    expect(midnightHits.length).toBe(1);
    expect(midnightHits[0]?.hits[0]?.chapterId).toBe("ch2");
  });
});
