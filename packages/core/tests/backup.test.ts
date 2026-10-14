import { describe, it, expect, beforeEach } from "vitest";
import * as fflate from "fflate";
import { MemoryStore } from "../src/store/memory-store.js";
import { LibraryManager } from "../src/library/library-manager.js";
import { exportBackupBytes } from "../src/backup/export-zip.js";
import { verifyBackupZip, checkPathSafety } from "../src/backup/verify.js";
import { restoreBackupZip } from "../src/backup/import-zip.js";
import type { ImportResult } from "../src/import/epub.js";
import type { Annotation } from "../src/types.js";

describe("Phase 4 Backup & Restore Acceptance Tests", () => {
  let store: MemoryStore;
  let manager: LibraryManager;

  beforeEach(() => {
    store = new MemoryStore();
    manager = new LibraryManager(store);
  });

  const sampleDoc: ImportResult = {
    meta: {
      schema: 1,
      title: "Sample Book",
      author: "Jane Author",
      kind: "book",
      sourceFormat: "epub",
      capturedAt: new Date().toISOString(),
      contentHash: "hash-sample-1234",
      searchable: true,
      tags: ["philosophy"],
      collectionIds: [],
      chapters: [
        { id: "ch1", title: "Chapter 1", order: 1, href: "text/ch1.xhtml" }
      ]
    },
    original: {
      name: "sample.epub",
      bytes: new Uint8Array([10, 20, 30, 40])
    },
    reading: {
      name: "book.epub",
      bytes: new Uint8Array([10, 20, 30, 40])
    },
    searchText: [
      { chapterId: "ch1", title: "Chapter 1", text: "Knowledge begins with curiosity and reflection." }
    ]
  };

  it("exports library to zip, wipes storage, restores, and verifies file hashes match exactly", async () => {
    // 1. Setup library with document, annotation, progress, and collection
    const docMeta = await manager.addDocument(sampleDoc);

    const annotation: Annotation = {
      id: "ann-test-1",
      docId: docMeta.id,
      quote: "curiosity and reflection",
      prefix: "Knowledge begins with ",
      suffix: ".",
      kind: "highlight",
      color: "yellow",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };
    await manager.addAnnotation(annotation);

    await manager.saveProgress({
      docId: docMeta.id,
      location: "epubcfi(/6/2)",
      percent: 45,
      updatedAt: new Date().toISOString()
    });

    const collection = await manager.createCollection("Best Reads");
    await manager.setDocCollections(docMeta.id, [collection.id]);

    // 2. Export backup
    const { filename, bytes } = await exportBackupBytes(store);
    expect(filename).toMatch(/^folio-backup-\d{8}-\d{4}\.zip$/);
    expect(bytes.byteLength).toBeGreaterThan(0);

    // 3. Verify backup archive
    const verification = await verifyBackupZip(bytes);
    expect(verification.valid).toBe(true);
    expect(verification.issues).toHaveLength(0);
    expect(verification.backupMeta?.docCount).toBe(1);

    // 4. Wipe store completely
    const newStore = new MemoryStore();
    const newManager = new LibraryManager(newStore);

    // 5. Restore in replace mode
    const { summary } = await restoreBackupZip(newStore, newManager, bytes, { mode: "replace" });
    expect(summary.newDocsAdded).toBe(1);
    expect(summary.annotationsMerged).toBe(1);

    // 6. Verify restored state
    const docs = await newManager.listDocuments();
    expect(docs).toHaveLength(1);
    expect(docs[0]?.title).toBe("Sample Book");
    expect(docs[0]?.contentHash).toBe("hash-sample-1234");

    // Verify original file bytes match exactly
    const origBytes = await newStore.read(`library/docs/${docs[0]?.slug}/original/sample.epub`);
    expect(origBytes).toEqual(new Uint8Array([10, 20, 30, 40]));

    // Verify annotations restored
    const annots = await newManager.getAnnotations(docMeta.id);
    expect(annots).toHaveLength(1);
    expect(annots[0]?.quote).toBe("curiosity and reflection");

    // Verify progress restored
    const prog = await newManager.getProgress(docMeta.id);
    expect(prog?.percent).toBe(45);

    // Verify collections restored
    const colls = await newManager.getCollections();
    expect(colls).toHaveLength(1);
    expect(colls[0]?.name).toBe("Best Reads");
  });

  it("restoring the same backup twice is idempotent and changes nothing the second time", async () => {
    await manager.addDocument(sampleDoc);
    const { bytes } = await exportBackupBytes(store);

    // First restore in merge mode
    const res1 = await restoreBackupZip(store, manager, bytes, { mode: "merge" });
    expect(res1.summary.duplicatesSkipped).toBe(1);
    expect(res1.summary.newDocsAdded).toBe(0);

    // Second restore
    const res2 = await restoreBackupZip(store, manager, bytes, { mode: "merge" });
    expect(res2.summary.duplicatesSkipped).toBe(1);
    expect(res2.summary.newDocsAdded).toBe(0);

    const docs = await manager.listDocuments();
    expect(docs).toHaveLength(1);
  });

  it("rejects corrupted ZIPs and reports clear error reasons", async () => {
    const corruptBytes = new Uint8Array([0, 1, 2, 3, 4, 5, 6, 7]);
    const verification = await verifyBackupZip(corruptBytes);
    expect(verification.valid).toBe(false);
    expect(verification.issues[0]?.reason).toBe("corrupted");

    await expect(
      restoreBackupZip(store, manager, corruptBytes, { mode: "merge" })
    ).rejects.toThrow();
  });

  it("rejects ZIP archives with path traversal entries (.. or leading /)", async () => {
    expect(checkPathSafety("../evil.txt")).toBe(false);
    expect(checkPathSafety("/etc/passwd")).toBe(false);
    expect(checkPathSafety("\\windows\\system32")).toBe(false);
    expect(checkPathSafety("C:\\malicious.exe")).toBe(false);
    expect(checkPathSafety("library/docs/safe.txt")).toBe(true);

    // Create zip containing path traversal
    const maliciousZip = fflate.zipSync({
      "../escaped.txt": fflate.strToU8("malicious"),
      "library/manifest.json": fflate.strToU8("{}")
    });

    const verification = await verifyBackupZip(maliciousZip);
    expect(verification.valid).toBe(false);
    expect(verification.issues.some((i) => i.reason === "unsafe_path")).toBe(true);
  });

  it("detects checksum mismatches between files and manifest in backup", async () => {
    // Manually create zip with tampered file
    const manifest = {
      schema: 1,
      app: "folio",
      libraryId: "lib-1",
      updatedAt: new Date().toISOString(),
      docs: [
        {
          id: "doc-1",
          slug: "tampered-doc",
          title: "Tampered",
          contentHash: "hash-fake",
          updatedAt: new Date().toISOString(),
          files: [
            {
              path: "library/docs/tampered-doc/original/file.epub",
              size: 4,
              sha256: "0000000000000000000000000000000000000000000000000000000000000000"
            }
          ]
        }
      ]
    };

    const zipWithMismatch = fflate.zipSync({
      "backup.json": fflate.strToU8(JSON.stringify({ schema: 1 })),
      "library/manifest.json": fflate.strToU8(JSON.stringify(manifest)),
      "library/docs/tampered-doc/original/file.epub": new Uint8Array([1, 2, 3, 4])
    });

    const verification = await verifyBackupZip(zipWithMismatch);
    expect(verification.valid).toBe(false);
    expect(verification.issues.some((i) => i.reason === "checksum_mismatch")).toBe(true);
  });

  it("does not wipe existing library when replace-restore encounters a bad ZIP archive", async () => {
    // Add existing doc to library
    const added = await manager.addDocument(sampleDoc);
    const docsBefore = await manager.listDocuments();
    expect(docsBefore).toHaveLength(1);

    // Corrupted zip
    const badZip = new Uint8Array([0, 1, 2, 3]);

    await expect(
      restoreBackupZip(store, manager, badZip, { mode: "replace" })
    ).rejects.toThrow();

    // Verify existing library remains intact
    const docsAfter = await manager.listDocuments();
    expect(docsAfter).toHaveLength(1);
    expect(docsAfter[0]?.id).toBe(added.id);
  });

  it("does not wipe existing library when zip is missing manifest.json", async () => {
    const added = await manager.addDocument(sampleDoc);

    // Zip with no manifest
    const zipNoManifest = fflate.zipSync({
      "backup.json": fflate.strToU8(JSON.stringify({ schema: 1 })),
      "other.txt": fflate.strToU8("data")
    });

    await expect(
      restoreBackupZip(store, manager, zipNoManifest, { mode: "replace" })
    ).rejects.toThrow();

    const docsAfter = await manager.listDocuments();
    expect(docsAfter).toHaveLength(1);
    expect(docsAfter[0]?.id).toBe(added.id);
  });
});
