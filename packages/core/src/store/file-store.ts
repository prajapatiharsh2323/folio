export interface FileStat {
  size: number;
  modifiedAt: number;
}

export interface RemoveOptions {
  recursive?: boolean;
}

export interface FileStore {
  read(path: string): Promise<Uint8Array>;
  readText(path: string): Promise<string>;
  write(path: string, data: Uint8Array | string): Promise<void>; // atomic: write temp file, then replace
  exists(path: string): Promise<boolean>;
  list(dir: string): Promise<string[]>;
  stat(path: string): Promise<FileStat>;
  remove(path: string, opts?: RemoveOptions): Promise<void>;
}
