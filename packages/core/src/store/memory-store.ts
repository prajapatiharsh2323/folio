import type { FileStat, FileStore, RemoveOptions } from "./file-store.js";

interface MemEntry {
  data: Uint8Array;
  modifiedAt: number;
}

function normalize(path: string): string {
  return path.replace(/\\/g, "/").replace(/^\/+|\/+$/g, "");
}

/**
 * MemoryStore provides an in-memory implementation of the FileStore interface.
 * Useful for node environments, Vitest, and unit tests of core algorithms.
 */
export class MemoryStore implements FileStore {
  private files = new Map<string, MemEntry>();

  async read(path: string): Promise<Uint8Array> {
    const key = normalize(path);
    const entry = this.files.get(key);
    if (!entry) {
      throw new Error(`File not found: ${path}`);
    }
    return new Uint8Array(entry.data);
  }

  async readText(path: string): Promise<string> {
    const bytes = await this.read(path);
    return new TextDecoder().decode(bytes);
  }

  async write(path: string, data: Uint8Array | string): Promise<void> {
    const key = normalize(path);
    const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
    this.files.set(key, {
      data: new Uint8Array(bytes),
      modifiedAt: Date.now(),
    });
  }

  async exists(path: string): Promise<boolean> {
    const key = normalize(path);
    if (this.files.has(key)) return true;
    // Check if directory exists
    const prefix = key ? `${key}/` : "";
    for (const k of this.files.keys()) {
      if (k.startsWith(prefix)) return true;
    }
    return false;
  }

  async list(dir: string): Promise<string[]> {
    const cleanDir = normalize(dir);
    const prefix = cleanDir ? `${cleanDir}/` : "";
    const items = new Set<string>();

    for (const key of this.files.keys()) {
      if (prefix === "" || key.startsWith(prefix)) {
        const remainder = prefix === "" ? key : key.slice(prefix.length);
        const topLevel = remainder.split("/")[0];
        if (topLevel) {
          items.add(topLevel);
        }
      }
    }
    return Array.from(items).sort();
  }

  async stat(path: string): Promise<FileStat> {
    const key = normalize(path);
    const entry = this.files.get(key);
    if (!entry) {
      throw new Error(`File not found: ${path}`);
    }
    return {
      size: entry.data.byteLength,
      modifiedAt: entry.modifiedAt,
    };
  }

  async remove(path: string, opts?: RemoveOptions): Promise<void> {
    const key = normalize(path);
    if (this.files.has(key)) {
      this.files.delete(key);
      return;
    }

    if (opts?.recursive) {
      const prefix = key ? `${key}/` : "";
      for (const k of Array.from(this.files.keys())) {
        if (k.startsWith(prefix)) {
          this.files.delete(k);
        }
      }
    }
  }
}
