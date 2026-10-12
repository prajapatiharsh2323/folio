import { OpfsStore } from "./opfs-store.js";

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
   * Prompts the user to pick a local folder on their computer.
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
    return new FsAccessStore(handle);
  }
}
