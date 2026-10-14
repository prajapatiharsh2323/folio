import type { SyncProvider, SyncFileEntry } from "./types.js";

export interface GoogleDriveConfig {
  clientId: string;
  folderName?: string; // default "Folio Library"
}

// Drive API endpoints
const DRIVE_API = "https://www.googleapis.com/drive/v3";
const UPLOAD_API = "https://www.googleapis.com/upload/drive/v3";
const SCOPE = "https://www.googleapis.com/auth/drive.file";

interface GoogleTokenResponse {
  access_token?: string;
  error?: string;
  error_description?: string;
}

interface GoogleTokenClient {
  requestAccessToken: () => void;
}

interface GoogleOauth2 {
  initTokenClient: (config: {
    client_id: string;
    scope: string;
    callback: (response: GoogleTokenResponse) => void;
  }) => GoogleTokenClient;
  revoke: (token: string, done: () => void) => void;
}

interface WindowWithGoogle {
  google?: {
    accounts?: {
      oauth2?: GoogleOauth2;
    };
  };
}

export class GoogleDriveSyncProvider implements SyncProvider {
  private accessToken: string | null = null;
  private folderId: string | null = null;
  private fileCache = new Map<string, { id: string; sha256: string; size: number; modifiedAt: string }>();

  constructor(
    private config: GoogleDriveConfig,
    private fetchFn: typeof fetch = globalThis.fetch.bind(globalThis)
  ) {}

  setAccessToken(token: string): void {
    this.accessToken = token;
  }

  isSignedIn(): boolean {
    return Boolean(this.accessToken);
  }

  async signIn(): Promise<void> {
    if (this.accessToken) return;

    // Browser Google Identity Services (GIS) token client flow
    const win = typeof window !== "undefined" ? (window as unknown as WindowWithGoogle) : undefined;
    if (win?.google?.accounts?.oauth2) {
      return new Promise((resolve, reject) => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const client = (win.google!.accounts!.oauth2! as any).initTokenClient({
          client_id: this.config.clientId,
          scope: SCOPE,
          callback: (response: GoogleTokenResponse) => {
            if (response.error) {
              reject(new Error(response.error_description || response.error));
            } else if (response.access_token) {
              this.accessToken = response.access_token;
              resolve();
            } else {
              reject(new Error("Failed to receive Google Drive access token"));
            }
          },
          error_callback: (error: { message?: string; type?: string }) => {
            reject(new Error(error?.message || "Google OAuth sign-in popup closed or failed"));
          }
        });
        client.requestAccessToken();
      });
    }

    throw new Error("Google Identity Services script not loaded");
  }

  async signOut(): Promise<void> {
    const win = typeof window !== "undefined" ? (window as unknown as WindowWithGoogle) : undefined;
    if (this.accessToken && win?.google?.accounts?.oauth2) {
      try {
        win.google.accounts.oauth2.revoke(this.accessToken, () => {});
      } catch {
        // ignore
      }
    }
    this.accessToken = null;
    this.folderId = null;
    this.fileCache.clear();
  }

  private async getHeaders(): Promise<HeadersInit> {
    if (!this.accessToken) {
      throw new Error("Not signed in to Google Drive");
    }
    return {
      Authorization: `Bearer ${this.accessToken}`,
      "Content-Type": "application/json"
    };
  }

  /**
   * Finds or creates the remote "Folio Library" root folder.
   */
  async getOrCreateRootFolder(): Promise<string> {
    if (this.folderId) return this.folderId;

    const folderName = this.config.folderName || "Folio Library";
    const headers = await this.getHeaders();

    // Search for existing folder
    const q = `name = '${folderName}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false`;
    const res = await this.fetchFn(
      `${DRIVE_API}/files?q=${encodeURIComponent(q)}&spaces=drive&fields=files(id,name)`,
      { headers }
    );

    if (!res.ok) {
      throw new Error(`Failed to query Drive root folder: ${res.statusText}`);
    }

    const data = await res.json();
    if (data.files && data.files.length > 0) {
      this.folderId = data.files[0].id;
      return this.folderId!;
    }

    // Create folder
    const createRes = await this.fetchFn(`${DRIVE_API}/files`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        name: folderName,
        mimeType: "application/vnd.google-apps.folder"
      })
    });

    if (!createRes.ok) {
      throw new Error(`Failed to create Drive root folder: ${createRes.statusText}`);
    }

    const created = await createRes.json();
    this.folderId = created.id;
    return this.folderId!;
  }

  /**
   * Lists all files within the "Folio Library" folder.
   */
  async list(): Promise<SyncFileEntry[]> {
    const rootId = await this.getOrCreateRootFolder();
    const headers = await this.getHeaders();

    const q = `'${rootId}' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'`;
    const entries: SyncFileEntry[] = [];
    this.fileCache.clear();
    let pageToken: string | undefined = undefined;

    interface DriveApiFile {
      id: string;
      name: string;
      size?: string;
      modifiedTime?: string;
      appProperties?: {
        sha256?: string;
        relPath?: string;
      };
    }

    do {
      const pageParam: string = pageToken ? `&pageToken=${encodeURIComponent(pageToken)}` : "";
      const res: any = await this.fetchFn(
        `${DRIVE_API}/files?q=${encodeURIComponent(q)}&fields=nextPageToken,files(id,name,size,modifiedTime,appProperties)&pageSize=1000${pageParam}`,
        { headers }
      );

      if (!res.ok) {
        throw new Error(`Failed to list files from Drive: ${res.statusText}`);
      }

      const data: any = await res.json();
      const files: DriveApiFile[] = (data.files || []) as DriveApiFile[];
      for (const f of files) {
        const sha256 = f.appProperties?.sha256 || "";
        const path = f.appProperties?.relPath || f.name;
        const size = parseInt(f.size || "0", 10);
        const modifiedAt = f.modifiedTime || new Date().toISOString();

        this.fileCache.set(path, { id: f.id, sha256, size, modifiedAt });
        entries.push({ id: f.id, path, size, sha256, modifiedAt });
      }

      pageToken = data.nextPageToken || undefined;
    } while (pageToken);

    return entries;
  }

  /**
   * Downloads file content by relative path.
   */
  async download(path: string): Promise<Uint8Array> {
    const cached = this.fileCache.get(path);
    if (!cached) {
      await this.list();
    }
    const file = this.fileCache.get(path);
    if (!file) {
      throw new Error(`File not found in Drive: ${path}`);
    }

    const headers = { Authorization: `Bearer ${this.accessToken}` };
    const res = await this.fetchFn(`${DRIVE_API}/files/${file.id}?alt=media`, { headers });
    if (!res.ok) {
      throw new Error(`Failed to download ${path} from Drive: ${res.statusText}`);
    }

    const buffer = await res.arrayBuffer();
    return new Uint8Array(buffer);
  }

  /**
   * Uploads file to Drive using multipart or resumable upload in chunks.
   * Section 8: Stores each file's SHA-256 in Drive appProperties.
   */
  async upload(path: string, data: Uint8Array, sha256: string): Promise<{ id: string }> {
    const rootId = await this.getOrCreateRootFolder();
    const existing = this.fileCache.get(path);

    const metadata = {
      name: path.split("/").pop() || path,
      parents: existing ? undefined : [rootId],
      appProperties: {
        relPath: path,
        sha256
      }
    };

    let driveId: string;
    // Resumable upload for files > 5MB, simple multipart for smaller
    if (data.byteLength > 5 * 1024 * 1024) {
      driveId = await this.uploadResumable(existing?.id, metadata, data);
    } else {
      driveId = await this.uploadMultipart(existing?.id, metadata, data);
    }

    // Update cache
    const now = new Date().toISOString();
    this.fileCache.set(path, {
      id: driveId,
      sha256,
      size: data.byteLength,
      modifiedAt: now
    });

    return { id: driveId };
  }

  private async uploadMultipart(existingId: string | undefined, metadata: object, data: Uint8Array): Promise<string> {
    const boundary = "folio_sync_boundary";
    const delimiter = `\r\n--${boundary}\r\n`;
    const closeDelimiter = `\r\n--${boundary}--`;

    const metaString = JSON.stringify(metadata);
    const bodyHeader = `${delimiter}Content-Type: application/json; charset=UTF-8\r\n\r\n${metaString}${delimiter}Content-Type: application/octet-stream\r\n\r\n`;

    const enc = new TextEncoder();
    const headerBytes = enc.encode(bodyHeader);
    const footerBytes = enc.encode(closeDelimiter);

    const fullLength = headerBytes.byteLength + data.byteLength + footerBytes.byteLength;
    const body = new Uint8Array(fullLength);
    body.set(headerBytes, 0);
    body.set(data, headerBytes.byteLength);
    body.set(footerBytes, headerBytes.byteLength + data.byteLength);

    const url = existingId
      ? `${UPLOAD_API}/files/${existingId}?uploadType=multipart`
      : `${UPLOAD_API}/files?uploadType=multipart`;

    const res = await this.fetchFn(url, {
      method: existingId ? "PATCH" : "POST",
      headers: {
        Authorization: `Bearer ${this.accessToken}`,
        "Content-Type": `multipart/related; boundary=${boundary}`
      },
      body: body as unknown as BodyInit
    });

    if (!res.ok) {
      throw new Error(`Drive upload failed for ${url}: ${res.statusText}`);
    }

    try {
      const json = await res.json();
      return json.id || existingId || "uploaded";
    } catch {
      return existingId || "uploaded";
    }
  }

  private async uploadResumable(existingId: string | undefined, metadata: object, data: Uint8Array): Promise<string> {
    const initUrl = existingId
      ? `${UPLOAD_API}/files/${existingId}?uploadType=resumable`
      : `${UPLOAD_API}/files?uploadType=resumable`;

    const initRes = await this.fetchFn(initUrl, {
      method: existingId ? "PATCH" : "POST",
      headers: {
        Authorization: `Bearer ${this.accessToken}`,
        "Content-Type": "application/json; charset=UTF-8",
        "X-Upload-Content-Type": "application/octet-stream",
        "X-Upload-Content-Length": data.byteLength.toString()
      },
      body: JSON.stringify(metadata)
    });

    if (!initRes.ok) {
      throw new Error(`Failed to initiate resumable upload: ${initRes.statusText}`);
    }

    const locationUrl = initRes.headers.get("Location");
    if (!locationUrl) {
      throw new Error("Missing upload Location header from Google Drive");
    }

    // Chunk size 5MB (Section 8: resumable uploads in 5 MB chunks, exponential backoff)
    const CHUNK_SIZE = 5 * 1024 * 1024;
    let offset = 0;
    let finalId = existingId || "uploaded";

    while (offset < data.byteLength) {
      const end = Math.min(offset + CHUNK_SIZE, data.byteLength);
      const chunk = data.subarray(offset, end);
      const contentRange = `bytes ${offset}-${end - 1}/${data.byteLength}`;

      let attempts = 0;
      let success = false;

      while (!success && attempts < 5) {
        attempts++;
        const res = await this.fetchFn(locationUrl, {
          method: "PUT",
          headers: {
            "Content-Range": contentRange,
            "Content-Length": chunk.byteLength.toString()
          },
          body: chunk as unknown as BodyInit
        });

        if (res.status === 308) {
          success = true;
          const rangeHeader = res.headers.get("Range");
          if (rangeHeader) {
            const match = rangeHeader.match(/bytes=0-(\d+)/);
            if (match && match[1]) {
              offset = parseInt(match[1], 10) + 1;
            } else {
              offset = end;
            }
          } else {
            offset = end;
          }
        } else if (res.ok) {
          success = true;
          offset = end;
          try {
            const json = await res.json();
            if (json.id) finalId = json.id;
          } catch {
            // ignore
          }
        } else if (res.status === 429 || res.status >= 500) {
          // Exponential backoff
          const delay = Math.pow(2, attempts) * 500;
          await new Promise((r) => setTimeout(r, delay));
        } else {
          throw new Error(`Resumable upload chunk failed: ${res.statusText}`);
        }
      }

      if (!success) {
        throw new Error(`Resumable upload failed after ${attempts} retry attempts`);
      }
    }

    return finalId;
  }
}

