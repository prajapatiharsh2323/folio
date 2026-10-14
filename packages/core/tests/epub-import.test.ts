import { describe, it, expect, beforeEach } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { MemoryStore } from "../src/store/memory-store.js";
import { importEpub, DrmProtectedError } from "../src/import/epub.js";
import { LibraryManager } from "../src/library/library-manager.js";

describe("EPUB Importer & Library Manager", () => {
  let store: MemoryStore;
  let manager: LibraryManager;

  beforeEach(() => {
    store = new MemoryStore();
    manager = new LibraryManager(store);
  });

  const fixturesDir = path.resolve(__dirname, "fixtures");

  it("imports an English EPUB, extracts metadata, chapters and search text", async () => {
    const englishBytes = fs.readFileSync(path.join(fixturesDir, "english-novel.epub"));
    const result = await importEpub(englishBytes, "english-novel.epub");

    expect(result.meta.title).toBe("The Solitary Reader");
    expect(result.meta.author).toBe("Arthur Pendelton");
    expect(result.meta.language).toBe("en");
    expect(result.meta.chapters.length).toBe(2);
    expect(result.searchText.length).toBe(2);
    expect(result.searchText[0]?.text).toContain("sun slanted across the cedar bookshelves");

    // Commit to library
    const committedDoc = await manager.addDocument(result);
    expect(committedDoc.id).toBeDefined();
    expect(committedDoc.slug).toMatch(/^[0-9]{4}-[0-9]{2}-[0-9]{2}-the-solitary-reader-[a-f0-9]{4}$/);

    // Verify manifest has been updated (commit point)
    const manifest = await manager.getManifest();
    expect(manifest.docs.length).toBe(1);
    expect(manifest.docs[0]?.title).toBe("The Solitary Reader");

    // Verify original and reading files exist in store
    expect(await store.exists(`library/docs/${committedDoc.slug}/original/english-novel.epub`)).toBe(true);
    expect(await store.exists(`library/docs/${committedDoc.slug}/reading/book.epub`)).toBe(true);
    expect(await store.exists(`library/docs/${committedDoc.slug}/meta.json`)).toBe(true);
  });

  it("imports a Hindi EPUB preserving Devanagari characters and chapters", async () => {
    const hindiBytes = fs.readFileSync(path.join(fixturesDir, "hindi-book.epub"));
    const result = await importEpub(hindiBytes, "hindi-book.epub");

    expect(result.meta.title).toBe("निर्मल धारा (Nirmal Dhara)");
    expect(result.meta.author).toBe("प्रेमचंद (Premchand)");
    expect(result.meta.language).toBe("hi");
    expect(result.meta.chapters.length).toBe(2);
    expect(result.searchText[0]?.text).toContain("गंगा की लहरों");

    const committedDoc = await manager.addDocument(result);
    expect(committedDoc.title).toBe("निर्मल धारा (Nirmal Dhara)");
  });

  it("detects duplicate documents by contentHash", async () => {
    const englishBytes = fs.readFileSync(path.join(fixturesDir, "english-novel.epub"));
    const result = await importEpub(englishBytes, "english-novel.epub");

    await manager.addDocument(result);

    const duplicate = await manager.findDuplicate(result.meta.contentHash);
    expect(duplicate).toBeDefined();
    expect(duplicate?.title).toBe("The Solitary Reader");
  });

  it("detects interrupted imports during startup recovery", async () => {
    // Simulate an uncommitted interrupted import
    await store.write("library/docs/2026-10-12-interrupted-book-1234/original/test.epub", "dummy");

    const uncommitted = await manager.recoverInterruptedImports();
    expect(uncommitted).toContain("2026-10-12-interrupted-book-1234");
  });

  it("detects DRM protected EPUBs and raises DrmProtectedError", async () => {
    const englishBytes = fs.readFileSync(path.join(fixturesDir, "english-novel.epub"));
    const fflate = await import("fflate");
    const unzipped = fflate.unzipSync(englishBytes);

    // Inject encryption.xml with CipherData to simulate DRM
    unzipped["META-INF/encryption.xml"] = fflate.strToU8(`<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <EncryptedData>
    <CipherData><CipherValue>dummy-secret</CipherValue></CipherData>
  </EncryptedData>
</encryption>`);

    const repackaged = fflate.zipSync(unzipped);

    await expect(importEpub(repackaged, "locked.epub")).rejects.toThrow(DrmProtectedError);
  });

  it("decodes numeric entities above U+FFFF properly using fromCodePoint", async () => {
    const englishBytes = fs.readFileSync(path.join(fixturesDir, "english-novel.epub"));
    const fflate = await import("fflate");
    const unzipped = fflate.unzipSync(englishBytes);

    // Replace text in chapter_1.xhtml with an astral plane character like &#128512; (😀) and &#x1F4DA; (📚)
    const ch1 = fflate.strFromU8(unzipped["OEBPS/chapter_1.xhtml"]!);
    const modifiedCh1 = ch1.replace(
      "The quiet afternoon",
      "The quiet &#128512; afternoon &#x1F4DA;"
    );
    unzipped["OEBPS/chapter_1.xhtml"] = fflate.strToU8(modifiedCh1);
    const repackaged = fflate.zipSync(unzipped);

    const result = await importEpub(repackaged, "emoji-novel.epub");
    expect(result.searchText[0]?.text).toContain("😀");
    expect(result.searchText[0]?.text).toContain("📚");
  });
});
