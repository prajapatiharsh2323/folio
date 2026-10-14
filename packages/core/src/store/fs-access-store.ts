import { OpfsStore } from "./opfs-store.js";

function openHandlesDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open("folio-handles-db", 1);
    req.onupgradeneeded = () => {
      req.result.createObjectStore("handles");
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function saveDirectoryHandle(handle: FileSystemDirectoryHandle): Promise<void> {
  try {
    const db = await openHandlesDb();
    const tx = db.transaction("handles", "readwrite");
    tx.objectStore("handles").put(handle, "active-folder");
    await new Promise((res, rej) => {
      tx.oncomplete = res;
      tx.onerror = rej;
    });
  } catch (err) {
    console.warn("Failed to persist folder handle:", err);
  }
}

export async function getSavedDirectoryHandle(): Promise<FileSystemDirectoryHandle | null> {
  try {
    const db = await openHandlesDb();
    const tx = db.transaction("handles", "readonly");
    const req = tx.objectStore("handles").get("active-folder");
    return new Promise((resolve) => {
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

/**
 * FsAccessStore implements FileStore backed by a chosen folder using
 * the browser File System Access API (showDirectoryPicker).
 *
 * Used for Windows folder mode (Section 8: "If the person picks a folder inside
 * their Drive for desktop sync directory, Windows keeps it synced with no Drive API calls").
 */
export class FsAccessStore extends OpfsStore {
  constructor(dirHandle: FileSystemDirectoryHandle) {
    super(dirHandle);
  }

  /**
   * Prompts the user to pick a local folder on their computer and saves the handle.
   */
  static async pickDirectory(): Promise<FsAccessStore> {
    if (typeof window === "undefined" || !("showDirectoryPicker" in window)) {
      throw new Error("File System Access API (showDirectoryPicker) is not supported in this browser");
    }
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const handle = await (window as any).showDirectoryPicker({
      id: "folio-library",
      mode: "readwrite"
    });
    await saveDirectoryHandle(handle);
    return new FsAccessStore(handle);
  }

  /**
   * Attempts to restore a previously picked directory handle from IndexedDB.
   */
  static async restoreSaved(): Promise<FsAccessStore | null> {
    if (typeof window === "undefined" || typeof indexedDB === "undefined") return null;
    const handle = await getSavedDirectoryHandle();
    if (!handle) return null;
    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const status = await (handle as any).queryPermission({ mode: "readwrite" });
      if (status === "granted") {
        return new FsAccessStore(handle);
      }
    } catch {
      // Permission query not permitted or failed
    }
    return null;
  }

  /**
   * Queries the permission state of a previously stored folder handle ("granted" | "prompt" | "denied" | "none").
   */
  static async getSavedHandleStatus(): Promise<"granted" | "prompt" | "denied" | "none"> {
    if (typeof window === "undefined" || typeof indexedDB === "undefined") return "none";
    const handle = await getSavedDirectoryHandle();
    if (!handle) return "none";
    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const status = await (handle as any).queryPermission({ mode: "readwrite" });
      return status as "granted" | "prompt" | "denied";
    } catch {
      return "none";
    }
  }

  /**
   * Prompts the user to grant permission to the saved directory handle.
   */
  static async requestSavedPermission(): Promise<FsAccessStore | null> {
    if (typeof window === "undefined" || typeof indexedDB === "undefined") return null;
    const handle = await getSavedDirectoryHandle();
    if (!handle) return null;
    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const status = await (handle as any).requestPermission({ mode: "readwrite" });
      if (status === "granted") {
        return new FsAccessStore(handle);
      }
    } catch {
      // ignore
    }
    return null;
  }
}
