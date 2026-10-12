import type { FileStat, FileStore, RemoveOptions } from "./file-store.js";

/**
 * Normalizes a path string to POSIX style without leading or trailing slashes.
 */
function normalizePath(path: string): string[] {
  return path
    .replace(/\\/g, "/")
    .split("/")
    .filter((segment) => segment.length > 0 && segment !== ".");
}

/**
 * OpfsStore implements the FileStore interface on the standard Origin Private File System (OPFS).
 * In atomic write mode, it creates a temporary sibling file and renames or commits it atomically.
 */
export class OpfsStore implements FileStore {
  private rootDirPromise?: Promise<FileSystemDirectoryHandle>;

  constructor(private customRootDir?: FileSystemDirectoryHandle) {}

  private async getRoot(): Promise<FileSystemDirectoryHandle> {
    if (this.customRootDir) {
      return this.customRootDir;
    }
    if (!this.rootDirPromise) {
      if (typeof navigator === "undefined" || !navigator.storage?.getDirectory) {
        throw new Error("OPFS is not supported in this environment");
      }
      this.rootDirPromise = navigator.storage.getDirectory();
    }
    return this.rootDirPromise;
  }

  private async getDirHandle(
    segments: string[],
    create = false,
  ): Promise<FileSystemDirectoryHandle | null> {
    let current = await this.getRoot();
    for (const segment of segments) {
      try {
        current = await current.getDirectoryHandle(segment, { create });
      } catch (err) {
        if (!create) return null;
        throw err;
      }
    }
    return current;
  }

  private async getFileHandle(
    path: string,
    create = false,
  ): Promise<{ handle: FileSystemFileHandle; parent: FileSystemDirectoryHandle; filename: string } | null> {
    const segments = normalizePath(path);
    if (segments.length === 0) {
      throw new Error("Path cannot be empty");
    }
    const filename = segments[segments.length - 1]!;
    const parentSegments = segments.slice(0, -1);
    const parent = await this.getDirHandle(parentSegments, create);
    if (!parent) return null;

    try {
      const handle = await parent.getFileHandle(filename, { create });
      return { handle, parent, filename };
    } catch {
      return null;
    }
  }

  async read(path: string): Promise<Uint8Array> {
    const fileInfo = await this.getFileHandle(path, false);
    if (!fileInfo) {
      throw new Error(`File not found: ${path}`);
    }
    const file = await fileInfo.handle.getFile();
    const arrayBuffer = await file.arrayBuffer();
    return new Uint8Array(arrayBuffer);
  }

  async readText(path: string): Promise<string> {
    const bytes = await this.read(path);
    return new TextDecoder().decode(bytes);
  }

  async write(path: string, data: Uint8Array | string): Promise<void> {
    const segments = normalizePath(path);
    if (segments.length === 0) {
      throw new Error("Cannot write to empty path");
    }
    const filename = segments[segments.length - 1]!;
    const parentSegments = segments.slice(0, -1);
    const parent = await this.getDirHandle(parentSegments, true);
    if (!parent) {
      throw new Error(`Failed to create directory structure for: ${path}`);
    }

    // Atomic write pattern: write to a temporary file first, then write or move
    const tempFilename = `.tmp-${filename}-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
    const tempFileHandle = await parent.getFileHandle(tempFilename, { create: true });
    const writable = await tempFileHandle.createWritable();

    const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
    // Ensure standard ArrayBuffer / Uint8Array view for FileSystemWriteChunkType
    const payload = new Uint8Array(bytes.buffer as ArrayBuffer, bytes.byteOffset, bytes.byteLength);
    await writable.write(payload);
    await writable.close();

    // Check if move method exists on FileSystemFileHandle
    const anyTempHandle = tempFileHandle as unknown as { move?: (name: string) => Promise<void> };
    if (typeof anyTempHandle.move === "function") {
      await anyTempHandle.move(filename);
    } else {
      // Fallback for browsers without .move(): create target and copy
      const targetHandle = await parent.getFileHandle(filename, { create: true });
      const targetWritable = await targetHandle.createWritable();
      await targetWritable.write(payload);
      await targetWritable.close();
      await parent.removeEntry(tempFilename);
    }
  }

  async exists(path: string): Promise<boolean> {
    const segments = normalizePath(path);
    if (segments.length === 0) return true;

    const filename = segments[segments.length - 1]!;
    const parentSegments = segments.slice(0, -1);
    const parent = await this.getDirHandle(parentSegments, false);
    if (!parent) return false;

    try {
      await parent.getFileHandle(filename);
      return true;
    } catch {
      try {
        await parent.getDirectoryHandle(filename);
        return true;
      } catch {
        return false;
      }
    }
  }

  async list(dir: string): Promise<string[]> {
    const segments = normalizePath(dir);
    const targetDir = await this.getDirHandle(segments, false);
    if (!targetDir) return [];

    const results: string[] = [];
    const asyncEntries = targetDir as unknown as {
      entries: () => AsyncIterable<[string, FileSystemHandle]>;
    };

    if (typeof asyncEntries.entries === "function") {
      for await (const [name] of asyncEntries.entries()) {
        results.push(name);
      }
    }
    return results;
  }

  async stat(path: string): Promise<FileStat> {
    const fileInfo = await this.getFileHandle(path, false);
    if (!fileInfo) {
      throw new Error(`File not found: ${path}`);
    }
    const file = await fileInfo.handle.getFile();
    return {
      size: file.size,
      modifiedAt: file.lastModified,
    };
  }

  async remove(path: string, opts?: RemoveOptions): Promise<void> {
    const segments = normalizePath(path);
    if (segments.length === 0) return;

    const name = segments[segments.length - 1]!;
    const parentSegments = segments.slice(0, -1);
    const parent = await this.getDirHandle(parentSegments, false);
    if (!parent) return;

    try {
      await parent.removeEntry(name, { recursive: opts?.recursive ?? false });
    } catch (err) {
      // If entry doesn't exist, ignore
      const error = err as Error;
      if (error.name === "NotFoundError") {
        return;
      }
      throw err;
    }
  }
}
