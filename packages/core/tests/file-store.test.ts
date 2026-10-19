import { describe, it, expect, beforeEach } from "vitest";
import { MemoryStore } from "../src/store/memory-store.js";
import { OpfsStore } from "../src/store/opfs-store.js";

describe("MemoryStore", () => {
  let store: MemoryStore;

  beforeEach(() => {
    store = new MemoryStore();
  });

  it("reads and writes text files atomically", async () => {
    await store.write("manifest.json", '{"schema":1}');
    expect(await store.exists("manifest.json")).toBe(true);

    const content = await store.readText("manifest.json");
    expect(content).toBe('{"schema":1}');
  });

  it("reads and writes binary data", async () => {
    const data = new Uint8Array([1, 2, 3, 4, 5]);
    await store.write("docs/book-1/original/file.epub", data);

    const readBack = await store.read("docs/book-1/original/file.epub");
    expect(readBack).toEqual(data);
  });

  it("lists directory contents accurately", async () => {
    await store.write("library/manifest.json", "{}");
    await store.write("library/user/settings.json", "{}");
    await store.write("library/docs/doc-1/meta.json", "{}");

    const rootList = await store.list("library");
    expect(rootList).toEqual(["docs", "manifest.json", "user"]);

    const userList = await store.list("library/user");
    expect(userList).toEqual(["settings.json"]);
  });

  it("returns file stats", async () => {
    const text = "Hello Folio";
    await store.write("test.txt", text);

    const stat = await store.stat("test.txt");
    expect(stat.size).toBe(new TextEncoder().encode(text).byteLength);
    expect(stat.modifiedAt).toBeGreaterThan(0);
  });

  it("removes files and directories recursively", async () => {
    await store.write("library/docs/doc-1/meta.json", "{}");
    await store.write("library/docs/doc-1/clean/index.html", "<h1>Test</h1>");

    expect(await store.exists("library/docs/doc-1")).toBe(true);

    await store.remove("library/docs/doc-1", { recursive: true });
    expect(await store.exists("library/docs/doc-1/meta.json")).toBe(false);
    expect(await store.exists("library/docs/doc-1/clean/index.html")).toBe(false);
  });
});

describe("OpfsStore contract", () => {
  it("instantiates without error and provides standard FileStore interface", () => {
    const opfs = new OpfsStore();
    expect(typeof opfs.read).toBe("function");
    expect(typeof opfs.write).toBe("function");
    expect(typeof opfs.readText).toBe("function");
    expect(typeof opfs.exists).toBe("function");
    expect(typeof opfs.list).toBe("function");
    expect(typeof opfs.stat).toBe("function");
    expect(typeof opfs.remove).toBe("function");
  });
});
