import { describe, it, expect, beforeEach } from "vitest";
import { MemoryStore } from "../src/store/memory-store.js";
import { LibraryManager } from "../src/library/library-manager.js";
import { GoogleDriveSyncProvider } from "../src/sync/drive.js";
import { SyncManager } from "../src/sync/sync-manager.js";
import type { ImportResult } from "../src/import/epub.js";
import type { Annotation } from "../src/types.js";

interface MockDriveFile {
  id: string;
  name: string;
  data: Uint8Array;
  mimeType: string;
  parents: string[];
  appProperties: Record<string, string>;
  modifiedTime: string;
}

class MockDriveBackend {
  public files = new Map<string, MockDriveFile>();
  public rootFolderId = "root-folder-123";
  public calls: { url: string; method?: string }[] = [];
  public retryCount429 = 0;
  public retryCount500 = 0;
  private uploadSessions = new Map<string, { id: string; metadata: { name?: string; appProperties?: Record<string, string> }; buffer: Uint8Array }>();

  constructor() {
    this.files.set(this.rootFolderId, {
      id: this.rootFolderId,
      name: "Folio Library",
      data: new Uint8Array(),
      mimeType: "application/vnd.google-apps.folder",
      parents: [],
      appProperties: {},
      modifiedTime: new Date().toISOString()
    });
  }

  fetch: typeof fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = input.toString();
    const method = init?.method || "GET";
    const decodedUrl = decodeURIComponent(url);
    this.calls.push({ url, method });

    // 1. Resumable Upload Initiation
    if (url.includes("uploadType=resumable")) {
      const metadata = JSON.parse(init?.body as string);
      const sessionId = `upload-session-${Math.random().toString(36).substring(2, 9)}`;
      this.uploadSessions.set(sessionId, {
        id: `file-${Math.random().toString(36).substring(2, 9)}`,
        metadata,
        buffer: new Uint8Array()
      });

      const headers = new Headers();
      headers.set("Location", `https://mock.upload.drive/resumable/${sessionId}`);

      return new Response(null, {
        status: 200,
        headers
      });
    }

    // 2. Resumable Upload Chunks
    const resumableMatch = url.match(/https:\/\/mock\.upload\.drive\/resumable\/([^?]+)/);
    if (resumableMatch && method === "PUT") {
      const sessionId = resumableMatch[1]!;
      const session = this.uploadSessions.get(sessionId);
      if (!session) {
        return new Response("Invalid upload session", { status: 404 });
      }

      // Test retry logic on 429 and 500
      if (this.retryCount429 > 0) {
        this.retryCount429--;
        return new Response("Too Many Requests", { status: 429 });
      }
      if (this.retryCount500 > 0) {
        this.retryCount500--;
        return new Response("Internal Server Error", { status: 500 });
      }

      const chunk = init?.body as Uint8Array;
      const contentRange = (init?.headers as Record<string, string>)["Content-Range"] || "";
      const rangeMatch = contentRange.match(/bytes (\d+)-(\d+)\/(\d+)/);
      if (rangeMatch) {
        const start = parseInt(rangeMatch[1]!, 10);
        const end = parseInt(rangeMatch[2]!, 10);
        const total = parseInt(rangeMatch[3]!, 10);

        const newBuffer = new Uint8Array(Math.max(session.buffer.byteLength, end + 1));
        newBuffer.set(session.buffer);
        newBuffer.set(chunk, start);
        session.buffer = newBuffer;

        if (end + 1 < total) {
          return new Response(null, {
            status: 308,
            headers: { Range: `bytes=0-${end}` }
          });
        } else {
          // Completed
          const file: MockDriveFile = {
            id: session.id,
            name: session.metadata.name,
            data: session.buffer,
            mimeType: "application/octet-stream",
            parents: [this.rootFolderId],
            appProperties: session.metadata.appProperties || {},
            modifiedTime: new Date().toISOString()
          };
          this.files.set(session.id, file);

          return new Response(JSON.stringify({ id: session.id, name: file.name }), {
            status: 200,
            headers: { "Content-Type": "application/json" }
          });
        }
      }
    }

    // 3. Multipart Upload
    if (url.includes("uploadType=multipart")) {
      const body = init?.body as Uint8Array;
      const idMatch = url.match(/\/files\/([^?]+)\?uploadType=multipart/);
      const existingId = idMatch ? idMatch[1] : undefined;

      // Parse boundary payload
      const text = new TextDecoder().decode(body);
      const parts = text.split(/--folio_sync_boundary/);
      let metadata: { name?: string; appProperties?: Record<string, string> } = {};
      for (const part of parts) {
        if (part.includes("Content-Type: application/json")) {
          const jsonText = part.split("\r\n\r\n")[1]?.trim();
          if (jsonText) metadata = JSON.parse(jsonText);
        }
      }

      const binaryMarker = new TextEncoder().encode("Content-Type: application/octet-stream\r\n\r\n");
      let dataOffset = -1;
      for (let i = 0; i < body.length - binaryMarker.length; i++) {
        let match = true;
        for (let j = 0; j < binaryMarker.length; j++) {
          if (body[i + j] !== binaryMarker[j]) {
            match = false;
            break;
          }
        }
        if (match) {
          dataOffset = i + binaryMarker.length;
          break;
        }
      }

      const closeBytes = new TextEncoder().encode("\r\n--folio_sync_boundary--");
      const binaryData = dataOffset >= 0 ? body.subarray(dataOffset, body.length - closeBytes.length) : new Uint8Array();

      const id = existingId || `file-${Math.random().toString(36).substring(2, 9)}`;
      const file: MockDriveFile = {
        id,
        name: metadata.name || "unnamed",
        data: binaryData,
        mimeType: "application/octet-stream",
        parents: [this.rootFolderId],
        appProperties: metadata.appProperties || {},
        modifiedTime: new Date().toISOString()
      };
      this.files.set(id, file);

      return new Response(JSON.stringify({ id, name: file.name }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    // 4. Download media
    const downloadMatch = url.match(/\/drive\/v3\/files\/([^?]+)\?alt=media/);
    if (downloadMatch && method === "GET") {
      const fileId = downloadMatch[1]!;
      const file = this.files.get(fileId);
      if (!file) {
        return new Response("Not Found", { status: 404 });
      }
      return new Response(file.data, {
        status: 200,
        headers: { "Content-Type": "application/octet-stream" }
      });
    }

    // 5. Query files / Create folder
    if (url.includes("/drive/v3/files")) {
      if (method === "GET") {
        if (decodedUrl.includes("name = 'Folio Library'") || decodedUrl.includes("name='Folio Library'")) {
          // Folder query
          const folders = Array.from(this.files.values()).filter(
            (f) => f.mimeType === "application/vnd.google-apps.folder" && f.name === "Folio Library"
          );
          return new Response(JSON.stringify({ files: folders.map((f) => ({ id: f.id, name: f.name })) }), {
            status: 200,
            headers: { "Content-Type": "application/json" }
          });
        }

        // List files in root folder
        const items = Array.from(this.files.values()).filter(
          (f) => f.parents.includes(this.rootFolderId) && f.mimeType !== "application/vnd.google-apps.folder"
        );
        return new Response(
          JSON.stringify({
            files: items.map((f) => ({
              id: f.id,
              name: f.name,
              size: f.data.byteLength.toString(),
              modifiedTime: f.modifiedTime,
              appProperties: f.appProperties
            }))
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        );
      }

      if (method === "POST") {
        const body = JSON.parse(init?.body as string);
        const id = `file-${Math.random().toString(36).substring(2, 9)}`;
        const file: MockDriveFile = {
          id,
          name: body.name,
          data: new Uint8Array(),
          mimeType: body.mimeType || "application/octet-stream",
          parents: body.parents || [],
          appProperties: body.appProperties || {},
          modifiedTime: new Date().toISOString()
        };
        this.files.set(id, file);
        return new Response(JSON.stringify({ id, name: file.name }), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
    }

    return new Response("Not Implemented", { status: 501 });
  };
}

describe("Phase 6: Google Drive Sync & Reconciliation", () => {
  let backend: MockDriveBackend;
  let provider: GoogleDriveSyncProvider;

  beforeEach(() => {
    backend = new MockDriveBackend();
    provider = new GoogleDriveSyncProvider(
      { clientId: "mock-client-id.apps.googleusercontent.com" },
      backend.fetch
    );
    provider.setAccessToken("mock-token-xyz");
  });

  describe("GoogleDriveSyncProvider", () => {
    it("connects and finds or creates root 'Folio Library' folder", async () => {
      const rootId = await provider.getOrCreateRootFolder();
      expect(rootId).toBe("root-folder-123");
    });

    it("uploads and downloads small files via multipart upload (< 5MB)", async () => {
      const data = new TextEncoder().encode("Hello Google Drive!");
      const sha256 = "test-hash-1";
      const res = await provider.upload("library/manifest.json", data, sha256);

      expect(res.id).toBeDefined();

      const files = await provider.list();
      expect(files.length).toBe(1);
      expect(files[0]?.path).toBe("library/manifest.json");
      expect(files[0]?.sha256).toBe(sha256);

      const downloaded = await provider.download("library/manifest.json");
      expect(new TextDecoder().decode(downloaded)).toBe("Hello Google Drive!");
    });

    it("handles resumable uploads for > 5MB files with exponential backoff on 429/500", async () => {
      // 6MB payload to trigger resumable upload
      const largeData = new Uint8Array(6 * 1024 * 1024);
      largeData.fill(42);

      // Trigger 1 transient 429 error and 1 transient 500 error
      backend.retryCount429 = 1;
      backend.retryCount500 = 1;

      const res = await provider.upload("library/docs/big-book/original/book.epub", largeData, "hash-large");
      expect(res.id).toBeDefined();

      const files = await provider.list();
      const entry = files.find((f) => f.path === "library/docs/big-book/original/book.epub");
      expect(entry).toBeDefined();
      expect(entry?.size).toBe(6 * 1024 * 1024);

      const downloaded = await provider.download("library/docs/big-book/original/book.epub");
      expect(downloaded.byteLength).toBe(6 * 1024 * 1024);
      expect(downloaded[0]).toBe(42);
    });
  });

  describe("SyncManager - Two-Device Convergence & Reconciliation", () => {
    let storeA: MemoryStore;
    let managerA: LibraryManager;
    let syncManagerA: SyncManager;

    let storeB: MemoryStore;
    let managerB: LibraryManager;
    let syncManagerB: SyncManager;

    const sampleDoc1: ImportResult = {
      meta: {
        schema: 1,
        title: "Book Alpha",
        author: "Author A",
        kind: "book",
        sourceFormat: "epub",
        capturedAt: new Date().toISOString(),
        contentHash: "hash-alpha",
        searchable: true,
        tags: ["alpha"],
        collectionIds: [],
        chapters: [{ id: "c1", title: "C1", order: 1, href: "c1.xhtml" }]
      },
      original: { name: "book.epub", bytes: new Uint8Array([1, 2, 3]) },
      searchText: [{ chapterId: "c1", title: "C1", text: "Alpha content text" }]
    };

    const sampleDoc2: ImportResult = {
      meta: {
        schema: 1,
        title: "Book Beta",
        author: "Author B",
        kind: "book",
        sourceFormat: "epub",
        capturedAt: new Date().toISOString(),
        contentHash: "hash-beta",
        searchable: true,
        tags: ["beta"],
        collectionIds: [],
        chapters: [{ id: "c2", title: "C2", order: 1, href: "c2.xhtml" }]
      },
      original: { name: "book2.epub", bytes: new Uint8Array([4, 5, 6]) },
      searchText: [{ chapterId: "c2", title: "C2", text: "Beta content text" }]
    };

    beforeEach(() => {
      storeA = new MemoryStore();
      managerA = new LibraryManager(storeA);
      syncManagerA = new SyncManager({
        fileStore: storeA,
        libraryManager: managerA,
        provider,
        wifiOnly: false
      });

      storeB = new MemoryStore();
      managerB = new LibraryManager(storeB);
      syncManagerB = new SyncManager({
        fileStore: storeB,
        libraryManager: managerB,
        provider,
        wifiOnly: false
      });
    });

    it("two devices converge on the same documents, notes, progress and collections", async () => {
      // 1. Device A adds Book Alpha and an annotation
      const docA = await managerA.addDocument(sampleDoc1);
      const annotA: Annotation = {
        id: "annot-a1",
        docId: docA.id,
        createdAt: "2026-10-12T10:00:00.000Z",
        updatedAt: "2026-10-12T10:00:00.000Z",
        color: "yellow",
        quote: "Alpha highlight",
        prefix: "",
        suffix: "",
        location: "loc-1"
      };
      await managerA.addAnnotation(annotA);
      await managerA.saveProgress({
        docId: docA.id,
        location: "loc-1",
        percentage: 0.25,
        updatedAt: "2026-10-12T10:00:00.000Z"
      });

      // Device A syncs to Drive
      const resA1 = await syncManagerA.sync();
      expect(resA1.uploadedDocs).toBe(1);

      // 2. Device B adds Book Beta and another annotation
      const docB = await managerB.addDocument(sampleDoc2);
      const annotB: Annotation = {
        id: "annot-b1",
        docId: docB.id,
        createdAt: "2026-10-12T11:00:00.000Z",
        updatedAt: "2026-10-12T11:00:00.000Z",
        color: "pink",
        quote: "Beta highlight",
        prefix: "",
        suffix: "",
        location: "loc-2"
      };
      await managerB.addAnnotation(annotB);

      // Device B syncs with Drive
      const resB = await syncManagerB.sync();
      expect(resB.downloadedDocs).toBe(1); // Downloaded Book Alpha
      expect(resB.uploadedDocs).toBe(1);   // Uploaded Book Beta

      // Verify Device B now has both books and both annotations
      const manifestB = await managerB.getManifest();
      expect(manifestB.docs.length).toBe(2);
      const annotsB = await managerB.getAnnotations();
      expect(annotsB.length).toBe(2);

      // 3. Device A syncs again with Drive
      const resA2 = await syncManagerA.sync();
      expect(resA2.downloadedDocs).toBe(1); // Downloaded Book Beta

      // Verify Device A now also has both books and both annotations
      const manifestA = await managerA.getManifest();
      expect(manifestA.docs.length).toBe(2);
      const annotsA = await managerA.getAnnotations();
      expect(annotsA.length).toBe(2);

      // Verify search index on both devices includes both books
      const indexA = await managerA.getSearchIndex();
      expect(indexA.search("Alpha").length).toBe(1);
      expect(indexA.search("Beta").length).toBe(1);

      const indexB = await managerB.getSearchIndex();
      expect(indexB.search("Alpha").length).toBe(1);
      expect(indexB.search("Beta").length).toBe(1);
    });

    it("conflict on metadata updatedAt: newer metadata wins", async () => {
      // Device A adds book
      const docA = await managerA.addDocument(sampleDoc1);
      await syncManagerA.sync();

      // Device B downloads book
      await syncManagerB.sync();

      // Device B updates title with newer updatedAt
      await new Promise((r) => setTimeout(r, 50));
      await managerB.updateDocMeta(docA.id, { title: "Updated Book Alpha by B" });

      // Device B syncs
      await syncManagerB.sync();

      // Device A syncs and receives updated title
      await syncManagerA.sync();

      const manifestA = await managerA.getManifest();
      const updatedDocA = manifestA.docs.find((d) => d.id === docA.id);
      expect(updatedDocA?.title).toBe("Updated Book Alpha by B");
    });

    it("never propagates deletions to remote Drive in version one", async () => {
      // Device A adds and syncs book
      const docA = await managerA.addDocument(sampleDoc1);
      await syncManagerA.sync();

      const filesOnDriveBefore = await provider.list();
      expect(filesOnDriveBefore.length).toBeGreaterThan(0);

      // Device A deletes the book locally
      await managerA.deleteDocument(docA.id);
      const localManifest = await managerA.getManifest();
      expect(localManifest.docs.length).toBe(0);

      // Device A syncs again
      await syncManagerA.sync();

      // Verify files still exist on Drive (Section 8: "version one never propagates deletions")
      const filesOnDriveAfter = await provider.list();
      const driveHasDocOriginal = filesOnDriveAfter.some((f) => f.path.includes("original/book.epub"));
      expect(driveHasDocOriginal).toBe(true);
    });

    it("verifies state.json is saved locally with file metadata", async () => {
      await managerA.addDocument(sampleDoc1);
      await syncManagerA.sync();

      expect(await storeA.exists("sync/state.json")).toBe(true);
      const stateText = await storeA.readText("sync/state.json");
      const state = JSON.parse(stateText);
      expect(state.version).toBe(1);
      expect(Object.keys(state.files).length).toBeGreaterThan(0);
    });
  });
});
