# Folio Desktop — Architecture and Stack

Decisions locked: **Tauri 2 + TypeScript/React**, **Readium web toolkit (@readium/*)**
reader engine, **full schema-1 compatibility** with the mobile app.

---

## 1. Stack

| Layer | Choice | Notes |
|---|---|---|
| Shell | Tauri 2 (Rust) | Webview2 on Windows, NSIS installer |
| Frontend | TypeScript + React 18 + Vite | Same tooling style as the historical PWA |
| Styling | Plain CSS modules / CSS custom properties | Mirror `.docs/design-system.md` tokens exactly; no Tailwind (keeps token fidelity) |
| State | Zustand (or equivalent atom store) | Small stores; no ORM |
| Reader | `@readium/*` web toolkit navigator | Portal the same semantics the Android app gets from `EpubNavigatorFragment` |
| ZIP | `fflate` (JS) | Matches the PWA-era choice; used for backup ZIP |
| SQLite | bundled SQLite via Rust (`rusqlite`) Tauri command | Only used for the dictionary pack (P2) |
| SHA-256 | Rust command (`sha2`) for big files; JS (`crypto.subtle`) for small | Content hash must be SHA-256 hex, identical to mobile `Hashing.kt` |
| Persistence | plain JSON files (no database) | `FileStore` port — see §4 |
| Drive | Drive REST v3 + browser-loopback OAuth | see `09-platform-decisions.md` |

Rationale: the mobile app already proves the entire app is JSON-files + a webview reader;
nothing needs a real DB, native components, or heavy state management.

## 2. Process model & responsibilities

**Frontend (TS, in webview)** — everything app-specific:

- All screens/components and the design system.
- Reader navigation, theming, selection UI, annotation rendering.
- All data-model code: importers, EpubBuilder, search index (MiniSearch port), backup
  JSON, merge logic.
- Pure functions — unit-testable in Vitest without the shell (the mobile app keeps ~253
  JVM tests; parity here = Vitest suite).

**Rust core (thin, generic commands only):**

```
core:
  fs_read / fs_write_atomic / fs_delete / fs_move / fs_stat / fs_list_dir
  sha256_file
  zip_read / zip_write (streaming, through JS-provided entries)
  open_external_url           # shell.open
  prevent_display_sleep(bool) # windows power API / SET_THREAD_EXECUTION_STATE
  dict_sqlite_query           # P2, dictionary pack only
```

Rules:
- Rust holds **no Folio data-model logic**. It never parses DocMeta/Manifest.
- All path constants and folder layout come from the TS layer as arguments; Rust has no
  hardcoded Folio paths.
- Every Rust path write goes through one normalized-absolute-path guard (port of the
  mobile path-traversal defense in `AppFileStore.kt`).

## 3. Window model

Start with a **single-window app** (matching the mobile mental model):

- Main window `Library` (min 720×560, default ~1100×760, remembers size/pos in
  `user/window.json` — desktop-only file, never in backups).
- Reader, Collections detail, Notes can open **in place** (route change) like mobile
  Activities, one window with a route stack. Full-screen reader option via F11.
- Modal sheets/panels open *inside* the window — never separate popup windows.

## 4. FileStore port (`mobile/.../core/store/AppFileStore.kt`)

Interface (async, all ops serialized per-path by a mutex):

```ts
interface FileStore {
  readText(path: string): Promise<string>
  readBytes(path: string): Promise<Uint8Array>
  writeTextAtomic(path: string, data: string): Promise<void>
  writeBytesAtomic(path: string, data: Uint8Array): Promise<void>
  delete(path: string): Promise<void>          // idempotent on missing
  list(dir: string): Promise<Entry[]>          // {path, isDir}
  stat(path: string): Promise<Stat | null>
  exists(path: string): Promise<boolean>
  freeSpace(): Promise<number>                 // storage section in Settings
}
```

Atomic write procedure, identical to mobile: write to `path + ".tmp"` → flush → rename
over the target. On Windows, rename-over-existing can fail transiently
(ACCESS_DENIED when a reader holds the handle): retry up to 3× with 10/50/250 ms
backoff, then surface an error.

**Root resolution:**

- Default: `%APPDATA%/Folio` as the app dir; library under `library/`.
- Optional user-chosen folder root ("Windows Folder Mode"): a Settings picker sets
  `settings-root.json` → all FileStore paths resolve from that root instead. This is a
  *desktop enhancement*, opt-in; default stays the app dir so behavior matches mobile.

## 5. Library tree (identical to mobile)

```
library/
  manifest.json               # commit point; written LAST on any mutation
  docs/<slug>/
    meta.json
    original/<file>           # untouched source file (epub/html/txt)
    clean/index.html          # sanitized article HTML (clips/web imports)
    clean/images/*            # extracted inline images
    reading/book.epub         # EPUB 3 reading edition — what the reader opens
    cover.*                   # cover image (png/jpg)
  user/
    annotations.json          # Annotation[]
    progress.json             # Record<docId, Progress>
    collections.json          # Collection[]
    settings.json             # ReadingPrefs (global defaults)
    doc-settings.json         # per-doc ReadingPrefs overrides (desktop keeps writing exactly these)
  index/text/<docId>.json     # per-doc searchable text cache: {schema:1, chapters:[{id,title,order,text}]}
sync/state.json              # Drive mirror state: {path → {driveId, sha256}}
```

Hard rules carried over from mobile (`LibraryManager.kt`):

1. Data JSON files are schema-1 and byte-compatible; `ignoreUnknownKeys` on read.
2. Any library mutation = write file(s) → then rewrite `manifest.json` atomically. The
   manifest is the commit point; if it's unreadable we enter Recovery (damaged docs).
3. Dual-write invariant: `collection.docIds` ↔ `docmeta.collectionIds` stay in sync
   (`setDocCollections`, `mutateCollectionDocs`).
4. A process-wide (frontend) mutex serializes write-batches; never do two concurrent
   manifest rewrites.
5. Deleting a doc removes its folder recursively and drops it from manifest,
   progress, annotations, collections (via the dual-write), index cache, drive state.

## 6. Import pipeline (direct port of `core/importer/*` + `core/clip/EpubBuilder.kt`)

```
pick file (native dialog, filter: .epub/.html/.txt, max 50MB)
→ read bytes, contentHash = sha256(bytes)
→ detect format:
     magic/extension ----------
       EPUB: zip with META-INF/container.xml
       PDF: %PDF magic → REFUSED with "PDF is not supported" dialog
       HTML: extension or sniff
       TXT: extension or sniff
     DRM check for EPUB: META-INF/encryption.xml (except font-obfuscation algos) or rights.xml → refuse
→ pipeline:
     EPUB : parse container.xml → OPF → spine order → per-spine-item plain text
            cover from properties="cover-image" or EPUB2 meta heuristic
     HTML : sanitize to Folio article allow-list (DOMPurify-equivalent port used by
            both PWA and mobile HtmlImporter — allow-list, drop scripts/styles/forms),
            extract up to 40 inline data-URI images as hash-named files
     TXT  : split paragraphs, detect language via Devanagari letter ratio > 0.3 → 'hi' else 'en'
→ Build EPUB 3 reading edition (EpubBuilder):
     mimetype first entry, stored uncompressed; deterministic UUID from contentHash;
     nav.xhtml with chapter list (3–5 chapters for articles);
     store as docs/<slug>/reading/book.epub
→ write original/, clean/ (for HTML), cover.* , meta.json (with chapters[] ORDERed),
   index/text/<docId>.json
→ finally rewrite manifest.json
```

- Import must be async and cancellable; show progress (mobile shows nothing blocking:
  consider a lightweight progress snackbar).
- Dedup gate happens *before* the pipeline: if manifest already contains the
  contentHash → dialog Skip / Keep both (new id+slug-2) / Replace (delete old folder
  first).
- `importedAt` = now (ISO 8601 UTC); `capturedAt` reused for clips (from clip timestamp
  if present).

## 7. Testing strategy

- Vitest unit tests for: types (round-trip fixtures matching the mobile test JSON),
  FileStore semantics (against a temp dir via mock FS), importers, EpubBuilder
  determinism, MiniSearch+SearchNormalise, ReadingPaceStats, backup merge/replace.
- The mobile app keeps **253 green tests** as the parity floor; desktop tests should
  cover every unit the mobile tests cover (same fixtures where portable).
- Rust commands get a couple of Integration tests (atomic rename, sha256).

## 8. Key repos / references

- Android reference: `mobile/app/src/main/kotlin/dev/folio/mobile/**`
- Design system: `.docs/design-system.md` (the 1:1 port source for `03-ui-ux-spec.md`)
- PWA-era spec with reader/search/backup details: `docs/spec.md` §6–§8
- User-facing behavior reference: `docs/guide.md`
