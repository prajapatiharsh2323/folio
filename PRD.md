# Folio - Build Specification

Oct 12, 2026 · @Harsh

## 1. Overview

Folio is a local-first reading library for articles and books: an installable PWA for Android and Windows, plus a Windows browser extension that clips articles. Files are the source of truth, search is instant and offline, and the Android app should feel native, not like a website.

**Goals**

- Import EPUB, PDF, HTML and TXT files you own or may legitimately keep.
- Clip web articles from the rendered page into clean, sanitized, offline-readable copies.
- Search titles, authors, tags and full text, in English and Hindi.
- Read with progress, bookmarks, highlights, notes and comfortable typography.
- Back up and restore the whole library; later mirror it to Google Drive.

**Non-goals**

- No DRM removal, no bulk site crawling, no scraping behind logins.
- No accounts, no server of our own, no analytics.
- No automatic cloud sync in version one.

**Rules for the AI coding tool**

1. Build one phase at a time (section 11). Do not start the next phase until the current phase's acceptance checks pass.
2. Keep the stack in section 2. Ask before adding a dependency not listed there.
3. TypeScript strict mode everywhere. No `any` without a comment explaining why.
4. Content files are written once and never edited; user data (notes, progress) lives in separate files.
5. Every piece of imported HTML passes through the sanitizer before it is stored or shown.
6. After each phase, run lint, type-check and tests, and report what passed.
7. Where this spec is silent, choose the simplest option and note the decision in `docs/decisions.md`.

## 2. Tech stack

Use the latest stable release of each package at scaffold time and pin exact versions in `pnpm-lock.yaml`. The choice of EPUB engine is settled by a spike in phase 1; everything else is fixed.

| Layer                  | Choice                                                           | Why                                                                             |
| ---------------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| Language and build     | TypeScript (strict), Vite, pnpm workspaces                       | Fast dev loop; one repo for app, extension and shared code                      |
| UI                     | React, React Router                                              | Mature, easy for coding tools to generate correctly                             |
| Styling                | Tailwind CSS with CSS variables for theme tokens                 | Fast to build; themes switch by changing variables                              |
| Components             | Radix UI primitives, `vaul` for bottom sheets                    | Accessible dialogs, menus and sheets that feel app-like on a phone              |
| State                  | Zustand                                                          | Small, no boilerplate; holds library, settings and reader state                 |
| PWA                    | `vite-plugin-pwa` (Workbox)                                      | Manifest, service worker, precache of the app shell                             |
| Storage                | Origin-private file system (OPFS) behind a `FileStore` interface | Real files; the same interface can later point at a Windows folder or Capacitor |
| Search                 | MiniSearch, index saved as JSON                                  | Small and fast; swap for SQLite FTS5 only if needed                             |
| EPUB reading           | epub.js or foliate-js (decide in phase 1)                        | Both render EPUB in the browser; wrap behind a `ReaderEngine` interface         |
| PDF                    | `pdfjs-dist`                                                     | Text extraction for search, and page rendering                                  |
| ZIP and EPUB packaging | `fflate`                                                         | Small, fast, works in the browser and a service worker                          |
| Article extraction     | `@mozilla/readability`                                           | Standard article extractor                                                      |
| Sanitizing             | DOMPurify                                                        | Mandatory before storing or showing any HTML                                    |
| Hashing                | Web Crypto SHA-256                                               | Duplicate detection and backup integrity, no dependency                         |
| Clipper extension      | Manifest V3, built with WXT                                      | Handles the extension build and reloading; the code is plain TypeScript         |
| Tests                  | Vitest, Testing Library, Playwright                              | Unit, component and end-to-end, including mobile viewport                       |
| Quality                | ESLint, Prettier, `tsc --noEmit` in CI                           | Catches generated-code mistakes early                                           |

**Hosting.** A service worker needs HTTPS, so deploy the built PWA as static files to a free host such as Cloudflare Pages or GitHub Pages. The host serves only the app code; your library never leaves the device. During development use `localhost`.

**Later, if needed.** Capacitor to wrap the same code as a real Android app; Tauri for a Windows desktop app.

## 3. Repository structure

A pnpm monorepo with three packages. All logic that does not touch the screen lives in `packages/core`, so the web app and the extension share it and it can be tested without a browser UI.

```text
folio/
  package.json  pnpm-workspace.yaml  tsconfig.base.json
  docs/
    spec.md              # this document, exported
    decisions.md         # running log of choices the coding tool made
  packages/
    core/                # no React, no DOM-only UI code
      src/
        types.ts         # Doc, Chapter, Progress, Annotation, Manifest
        store/           # FileStore interface + OpfsStore implementation
        library/         # add/remove/list docs, manifest read/write
        import/          # epub.ts pdf.ts html.ts txt.ts detect.ts
        clip/            # readability.ts sanitize.ts images.ts buildEpub.ts
        search/          # normalise.ts index.ts query.ts
        backup/          # exportZip.ts importZip.ts verify.ts
        sync/            # drive.ts (phase 6, behind a SyncProvider interface)
        util/            # hash.ts id.ts date.ts debounce.ts
      tests/  fixtures/  # sample EPUB, PDF, HTML, Hindi samples
  apps/
    web/                 # the PWA
      public/            # icons, manifest assets
      src/
        app/             # routes, layout, providers
        screens/         # Library, Reader, Import, Search, Collections, Settings
        components/      # BottomNav, DocCard, Sheet, ThemePicker, ...
        reader/          # ReaderEngine interface + epub/pdf/html adapters
        state/           # Zustand stores
        styles/          # tokens.css, tailwind config
      vite.config.ts
    clipper/             # Manifest V3 extension (WXT)
      entrypoints/       # popup, background, content scripts
      src/
  e2e/                   # Playwright tests
```

Conventions:

- `apps/*` may import from `packages/core`; `packages/core` never imports from `apps/*`.
- Every module in `packages/core` has a Vitest file beside it or in `tests/`.
- File names are kebab-case; React components are PascalCase; one component per file.
- Dates are ISO 8601 strings in UTC; ids are `crypto.randomUUID()` unless stated otherwise.

## 4. Data model and file formats

The library is a folder tree. The coding tool must implement it exactly, through the `FileStore` interface, so the same code later runs on OPFS, a Windows folder or Capacitor.

```text
library/
  manifest.json                 # commit point: lists every document and file checksum
  docs/<slug>/
    meta.json                   # descriptive fields; title, author, tags may be edited
    original/<file>             # untouched import (epub, pdf, html, txt); never modified
    clean/index.html            # sanitized text (articles) or chapter files (books)
    clean/images/<hash>.<ext>   # images downloaded for offline reading
    reading/book.epub           # generated or copied reading edition
    cover.jpg                   # optional
  user/
    annotations.json            # Annotation[]
    progress.json               # Record<docId, Progress>
    collections.json            # Collection[]
    settings.json               # theme, font, size, line height, margins
  index/
    search.json                 # MiniSearch index; safe to delete, rebuilt on demand
```

`<slug>` is `YYYY-MM-DD-title-words-xxxx`: lower-case ASCII (transliterate or fall back to `doc`), at most 60 characters, ending with 4 random hex characters.

### Types (`packages/core/src/types.ts`)

```ts
export type DocKind = "article" | "book";
export type SourceFormat = "epub" | "pdf" | "html" | "txt" | "clip";

export interface Chapter {
  id: string;
  title: string;
  order: number;
  href: string;
} // href relative to the doc folder

export interface DocMeta {
  schema: 1;
  id: string; // crypto.randomUUID()
  slug: string;
  title: string;
  author?: string;
  kind: DocKind;
  language?: string; // BCP 47, e.g. 'en', 'hi'
  sourceFormat: SourceFormat;
  sourceUrl?: string; // clips and web-origin files
  sourceName?: string; // site or publisher name
  capturedAt: string; // ISO 8601 UTC
  importedAt: string;
  contentHash: string; // SHA-256 hex of the original bytes (clips: of clean/index.html)
  wordCount?: number;
  searchable: boolean; // false for scanned PDFs without a text layer
  tags: string[];
  collectionIds: string[];
  files: {
    original?: string;
    clean?: string;
    reading?: string;
    cover?: string;
  };
  chapters: Chapter[];
}

export interface Progress {
  docId: string;
  location: string;
  percent: number;
  updatedAt: string;
} // location: EPUB CFI, PDF page, or scroll offset

export interface Annotation {
  id: string;
  docId: string;
  chapterId?: string;
  quote: string;
  prefix: string;
  suffix: string; // 32 characters of context each side
  location?: string; // engine-specific hint; quote+context is authoritative
  kind: "highlight" | "note" | "bookmark";
  color?: "yellow" | "green" | "blue" | "pink";
  note?: string;
  createdAt: string;
  updatedAt: string;
}

export interface Collection {
  id: string;
  name: string;
  docIds: string[];
  createdAt: string;
}

export interface ManifestFile {
  path: string;
  size: number;
  sha256: string;
}
export interface ManifestDoc {
  id: string;
  slug: string;
  title: string;
  contentHash: string;
  updatedAt: string;
  files: ManifestFile[];
}
export interface Manifest {
  schema: 1;
  app: "folio";
  libraryId: string;
  updatedAt: string;
  docs: ManifestDoc[];
}
```

### FileStore interface

```ts
export interface FileStore {
  read(path: string): Promise<Uint8Array>;
  readText(path: string): Promise<string>;
  write(path: string, data: Uint8Array | string): Promise<void>; // atomic: write temp file, then replace
  exists(path: string): Promise<boolean>;
  list(dir: string): Promise<string[]>;
  stat(path: string): Promise<{ size: number; modifiedAt: number }>;
  remove(path: string, opts?: { recursive?: boolean }): Promise<void>;
}
```

### Rules

1. **Write order when adding a document:** original, clean and reading files first, then `meta.json`, then update `manifest.json` last. The manifest is the commit point.
2. **Recovery on startup:** a `docs/` folder not listed in the manifest is an interrupted import; offer to finish it or delete it. A manifest entry whose file is missing is shown as damaged, never silently dropped.
3. **Duplicates:** if an incoming `contentHash` already exists, ask the person to skip, replace or keep both.
4. **Persistence:** call `navigator.storage.persist()` on first launch and show the result in Settings.
5. **Deleting a document** removes its folder, its annotations and progress, its entries in collections and the search index, then rewrites the manifest.
6. **Annotation anchoring:** match by `quote` with `prefix` and `suffix` for disambiguation; use `location` only as a fast path. If no match is found after re-extraction, keep the note and mark it "detached".

## 5. Screens and features

On a phone the app uses a bottom navigation bar with Library, Search, Notes and Settings. On a wide window (Windows) the same screens use a left sidebar and a two-pane layout. Every screen works offline.

| Screen         | What it does                                                                                                                                                                | Acceptance checks                                                                                                              |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Library (home) | Grid or list of documents; "Continue reading" row; sort by recent, title, author, progress; filter by kind, tag, collection; multi-select by long-press                     | Empty state offers Import; list is virtualised and scrolls smoothly at 2,000 items; sort and filter choices survive a reload   |
| Import         | Pick several files at once; per-file progress; duplicate prompt; clear error reasons (unsupported, DRM-protected, corrupt)                                                  | A mixed batch of EPUB, PDF, HTML and TXT imports; one bad file never blocks the others; duplicates are detected by hash        |
| Reader         | Renders the document; auto-hiding top bar; table of contents sheet; typography sheet; progress bar; bookmarks; highlights and notes; in-book search; keeps the screen awake | Reopens at the last position; typography changes apply instantly and persist; highlights survive closing and reopening         |
| Search         | One box searching title, author, tags and full text; results grouped by document with chapter and snippet; recent searches                                                  | Results appear while typing; tapping a result opens the reader at that spot with the term marked; Hindi and English both work  |
| Document sheet | Edit title, author, tags, collections; show source link and capture date; export or delete                                                                                  | Edits update the library and search at once; delete asks for confirmation and removes every trace                              |
| Collections    | Create, rename, delete; add or remove documents                                                                                                                             | A document can belong to several collections; deleting a collection never deletes documents                                    |
| Notes          | All highlights, notes and bookmarks across the library; filter by document or colour; export as Markdown                                                                    | Tapping an item opens the reader at that passage; detached notes are labelled                                                  |
| Clip inbox     | Receives articles from the Windows clipper; shows a preview with editable title, author and tags; Save or Discard                                                           | Nothing is stored until Save; a clip with a duplicate hash prompts before saving                                               |
| Settings       | Theme (light, warm, dark, system); default reading preferences; storage status and persistence; backup and restore; later, Drive sync; version                              | Shows used storage and whether persistence was granted; backup and restore buttons work offline; "last backup" date is visible |

**Reader typography defaults.** Font size 18 px, line height 1.6, margins 20 px on phones, serif body with a sans-serif option, and a Devanagari-capable font stack (for example Noto Serif Devanagari and Noto Sans Devanagari, bundled locally so it works offline). Justify text is off by default.

## 6. Import pipeline and clipper

All import code lives in `packages/core/src/import` and `packages/core/src/clip`. Each importer is a pure function from bytes to a result object; saving to the `FileStore` is a separate step shared by all importers.

```ts
interface ImportResult {
  meta: Omit<DocMeta, "id" | "slug" | "importedAt" | "files">;
  original: { name: string; bytes: Uint8Array };
  clean?: { indexHtml: string; images: { name: string; bytes: Uint8Array }[] };
  reading?: { name: "book.epub"; bytes: Uint8Array };
  searchText: { chapterId: string; title: string; text: string }[];
}
```

### File detection

Detect by content, never by extension: ZIP containing a `mimetype` entry of `application/epub+zip` is EPUB; bytes starting with `%PDF-` are PDF; text that parses as HTML (`<html`, `<!doctype`, `<body`) is HTML; anything else valid UTF-8 is TXT. Unknown binary is rejected with "Unsupported file type".

### Per format

| Format | Steps                                                                                                                                                                                                                                                                                          | Edge cases                                                                                                                                                                                    |
| ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| EPUB   | Unzip with fflate; read `META-INF/container.xml` to find the OPF; read title, creator, language, cover; follow the spine; read the table of contents from `nav.xhtml` or `toc.ncx`; extract plain text per spine item for search; keep the file as `original/` and copy to `reading/book.epub` | If `META-INF/encryption.xml` lists anything other than font obfuscation, or `META-INF/rights.xml` exists, the file is DRM-protected: stop and say so. No TOC means one chapter per spine item |
| PDF    | Open with `pdfjs-dist`; read title and author from metadata; extract text per page; use the outline as chapters, otherwise one chapter                                                                                                                                                         | Average under 50 characters a page means no text layer: set `searchable: false`. Password-protected files show "This PDF is locked"                                                           |
| HTML   | Parse; run Readability; sanitize; download or keep embedded images; build clean HTML and an EPUB                                                                                                                                                                                               | SingleFile pages embed images as data URIs; convert them to files in `clean/images/`                                                                                                          |
| TXT    | Split on blank lines into paragraphs; title from the file name                                                                                                                                                                                                                                 | If over 30% of letters are Devanagari, set `language: 'hi'`; handle UTF-8 and UTF-8 with BOM                                                                                                  |

### Windows clipper (Manifest V3)

**Permissions:** `activeTab`, `scripting`, `storage`, `contextMenus`, `downloads`, and optional host permission for all sites, requested the first time a clip needs to fetch images.

**Entry points:** toolbar button, right-click "Clip to Folio", and the shortcut Alt+Shift+F.

1. The content script clones the page's DOM, checks `isProbablyReaderable`, and runs `new Readability(clone).parse()`. If it fails, the popup offers "Clip the selection" or "Clip the whole page".
2. It collects the title, byline, site name, language (`<html lang>`), published time (`article:published_time`), and the canonical URL (`<link rel="canonical">`, else the page URL).
3. The background worker fetches each image in the article, choosing the largest `srcset` candidate. Limits: 40 images, 10 MB each; failures keep the alt text and the original URL.
4. The clip is stored in the extension until delivered:

```ts
interface ClipPayload {
  version: 1;
  url: string;
  title: string;
  byline?: string;
  siteName?: string;
  lang?: string;
  publishedAt?: string;
  contentHtml: string; // unsanitized
  images: { originalUrl: string; mime: string; base64: string }[];
}
```

5. The extension opens or focuses a tab at the Folio address and shows the Clip inbox. A small content script, registered only for the Folio origins listed in the extension options (the deployed URL and `localhost`), bridges the clip to the page with `window.postMessage`. The page accepts a message only if `event.source === window`, `event.origin === location.origin`, and it carries the one-time nonce the page announced. Extension messaging does not reliably carry binary data, so images travel as base64.
6. **Fallback:** "Download .folioclip" saves a ZIP with `clip.json` and the images; the Import screen accepts it.

### Clip inbox (in the PWA)

1. **Sanitize here, never in the extension.** DOMPurify allowing headings, paragraphs, lists, `blockquote`, `pre`, `code`, `table` elements, `figure`, `figcaption`, `img`, `a`, `em`, `strong`, `sup`, `sub`, `hr`, `br`; forbid `script`, `style`, `iframe`, `object`, `form`, `svg`, `math` and every event-handler attribute. Links get `rel="noopener noreferrer"`.
2. Rewrite each `img` to `images/<sha256 prefix>.<ext>` and store the bytes.
3. Wrap the body in a minimal template (charset, `lang`, title, byline, source link, capture date) and save as `clean/index.html`.
4. Build an EPUB 3 with fflate: `mimetype` stored uncompressed and first, `META-INF/container.xml`, `OEBPS/content.opf`, `nav.xhtml`, one XHTML chapter, images, and a small stylesheet. Serialise the chapter as XHTML (self-closed void tags, escaped entities).
5. Show the preview; on Save write files in the order given in the data-model rules.

### Android intake

The PWA manifest declares a `share_target` that accepts files (EPUB, PDF, HTML, TXT) and text or URLs. Shared files go to a temporary `inbox/` in OPFS and open the Import screen with them queued. A shared link is added to `user/reading-list.json` as `{ id, url, title?, addedAt }` and shown in the Library under "To clip"; it is clipped later on Windows, or opened in the browser.

## 7. Search and reader

### Search

Search runs in a Web Worker so typing never blocks the interface.

1. **Normalise** (`normalise.ts`): convert to Unicode NFC; lower-case; remove zero-width joiner and non-joiner (U+200C, U+200D); collapse whitespace. Fold accents only on Latin letters (NFD, drop combining marks, recompose). Never strip combining marks from Devanagari: vowel signs are part of the word.
2. **Tokenise:** split on `/[^\p{L}\p{M}\p{N}]+/u`. The `\p{M}` class keeps Devanagari vowel signs and virama inside words. Do not use `\w` or a letters-only class.
3. **Index one entry per chapter** with id `docId:chapterId`. Fields: `title`, `author`, `tags`, `text`. Boost: title 4, author 3, tags 3, text 1. Store `docId`, `chapterId`, `title`, `chapterTitle`.
4. **Query options:** prefix matching on; AND across terms; fuzzy 0.2 only for Latin terms longer than 4 characters; group hits by document; return at most 50 documents.
5. **Snippets:** chapter plain text is saved in `index/text/<docId>.json` (rebuildable). Build a snippet of about 80 characters either side of the first hit and wrap matches in `<mark>`.
6. **Persist:** save `index/search.json` (debounced) after changes. If it is missing or its version differs, rebuild in the worker and show progress. Updating or deleting a document updates the index incrementally.
7. **Targets:** a query returns in under 100 ms for 500 documents on a mid-range phone; results update 120 ms after the last keystroke.

**Required tests (Vitest, `fixtures/hindi/`):** searching a Hindi word finds every document containing it; a prefix finds inflected forms; the same word typed with and without a zero-width joiner matches; nukta forms match after NFC; mixed Hindi and English queries work; a Latin query with accents matches the unaccented text.

### Reader

The screen talks only to this interface; each format has an adapter.

```ts
interface ReadingPrefs {
  theme: "light" | "warm" | "dark";
  fontFamily: "serif" | "sans";
  fontSize: number;
  lineHeight: number;
  margin: number;
  justify: boolean;
  flow: "paginated" | "scrolled";
}
interface ReaderEngine {
  open(
    doc: DocMeta,
    el: HTMLElement,
    o: { location?: string; prefs: ReadingPrefs },
  ): Promise<void>;
  goTo(location: string): Promise<void>;
  next(): Promise<void>;
  prev(): Promise<void>;
  getToc(): Promise<{ title: string; location: string; depth: number }[]>;
  applyPrefs(p: ReadingPrefs): void;
  onRelocate(
    cb: (r: { location: string; percent: number; chapterId?: string }) => void,
  ): void;
  onSelection(
    cb: (s: {
      quote: string;
      prefix: string;
      suffix: string;
      location: string;
      rect: DOMRect;
    }) => void,
  ): void;
  addHighlight(a: Annotation): void;
  removeHighlight(id: string): void;
  search(q: string): AsyncIterable<{ location: string; excerpt: string }>;
  destroy(): void;
}
```

| Adapter      | Used for                                                            | Notes                                                                                            |
| ------------ | ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `EpubEngine` | Books and clipped articles (`reading/book.epub`)                    | Built on epub.js or foliate-js, whichever wins the phase 1 spike                                 |
| `HtmlEngine` | Articles shown from `clean/index.html` when scrolled flow is chosen | Sandboxed iframe, scripts disabled, same-origin so the app can inject styles and read selections |
| `PdfEngine`  | PDF files                                                           | `pdfjs-dist` canvas plus text layer; fit-width default; pinch zoom                               |

**EPUB engine spike (phase 1, one day).** Open the same three files in both libraries: an English novel, a Hindi EPUB, and an image-heavy book. Compare: Devanagari conjuncts render correctly; page turns are smooth on a real phone; the saved location stays valid after changing font size; text selection and highlights work; memory use on a 5 MB file; recent maintenance activity. Keep the winner, record the result and reasons in `docs/decisions.md`, and keep the other behind the same interface only if it is cheap.

**Behaviour.**

- Tap the left or right third of the screen to turn a page, the middle to show or hide the interface; swipe horizontally to turn pages. On Windows: arrow keys, Space, Page Up and Page Down, `/` for in-book search, `t` for contents.
- Save progress 1.5 s after the position changes, and immediately when the page is hidden.
- Selecting text shows a small toolbar with four highlight colours, Add note, and Copy. Saving stores `quote`, `prefix` (32 characters) and `suffix` (32 characters).
- Typography changes apply live and are stored in `user/settings.json`; each document may override them.
- Keep the screen awake while the reader is open, using the Screen Wake Lock API where available.
- Never run scripts from book or article content.

## 8. Backup, restore and Drive sync

### Backup ZIP (phase 4)

- **Name:** `folio-backup-YYYYMMDD-HHMM.zip`. **Contents:** the `library/` tree without `index/`, plus `backup.json` with `{ schema, createdAt, appVersion, docCount, manifestSha256 }`.
- **Scope:** whole library, or selected documents together with their annotations and progress.
- **Compression:** store (level 0) EPUB, PDF and images, which are already compressed; level 6 for JSON and HTML.
- **Memory:** write with fflate's streaming `Zip` so a large library never sits in memory at once. On Windows use `showSaveFilePicker`; on Android build a Blob download or use the share sheet. Warn above about 500 MB and suggest a partial backup or Drive sync.
- **Reminder:** Settings shows the last backup date; show a banner after 14 days or 20 changes without a backup.

### Restore

1. Pick a ZIP, read `backup.json`, and verify every file's SHA-256 against the manifest. Any mismatch is reported by file name; the person decides whether to continue.
2. Show a summary: new documents, duplicates, conflicts.
3. **Merge** (default): a document whose `contentHash` already exists is skipped; for the same `id`, the newer `updatedAt` metadata wins; annotations merge by `id` with the newer `updatedAt` winning; progress keeps the newer entry per document; collections union by `id` with their `docIds` combined; local settings are kept. Merge never deletes anything.
4. **Replace:** wipes the library first. Require typing the word REPLACE to confirm.
5. Rebuild the search index afterwards.

**Acceptance:** export, clear storage, restore, then compare every file hash; restoring the same backup twice changes nothing the second time.

### Google Drive sync (phase 6)

Drive is a mirror and backup of the library folder, not the working copy. The app works fully offline and syncs when asked or when it opens on Wi-Fi.

```ts
interface SyncProvider {
  signIn(): Promise<void>;
  signOut(): Promise<void>;
  list(): Promise<
    { path: string; size: number; sha256: string; modifiedAt: string }[]
  >;
  download(path: string): Promise<Uint8Array>;
  upload(path: string, data: Uint8Array, sha256: string): Promise<void>;
}
```

- **Sign-in:** Google Identity Services token flow with the scope `https://www.googleapis.com/auth/drive.file`, so the app can only see files it created. The person creates their own OAuth client ID in Google Cloud Console and pastes it in Settings; there is no server of ours. Access tokens last about an hour and are kept in memory only; expect an occasional re-consent prompt. Sync runs while the app is open; there is no background sync in version one.
- **Layout:** a Drive folder `Folio Library` mirroring `library/` (without `index/`). Drive addresses files by id, so keep a local `sync/state.json` mapping `path → { driveId, sha256, remoteModifiedAt }`, and store each file's SHA-256 in Drive `appProperties`.
- **Algorithm:** download the remote `manifest.json`; documents present only locally are uploaded, only remotely are downloaded; for documents on both sides compare `updatedAt` on `meta.json`; merge `user/*.json` with the same rules as restore, then upload the merged result; finally upload the new manifest last.
- **Deletions:** version one never propagates deletions. Deleting a document removes it from this device only; "Delete from Drive" is a separate, explicit action.
- **Transfer:** resumable uploads in 5 MB chunks, exponential backoff on 429 and 5xx, a visible progress sheet, and a "Wi-Fi only" setting that is on by default.
- **Privacy:** files on Drive are not encrypted by Folio; say so in Settings. Optional client-side encryption is a later feature.

**Windows shortcut (optional, phase 6).** Add a second `FileStore` implementation backed by the File System Access API. If the person picks a folder inside their Drive for desktop sync directory, Windows keeps it synced with no Drive API calls. Android still uses the API path above.

## 9. Design system and native feel

Folio should look calm and bookish and behave like an Android app: full-screen, instant, gesture-driven, with no browser chrome and no text that looks like a web page.

### Theme tokens (`styles/tokens.css`)

Define as CSS variables on `:root[data-theme="…"]`; Tailwind reads them. Check every text and background pair for WCAG AA contrast (4.5:1 for body text) before shipping.

| Token       | Light     | Warm      | Dark      |
| ----------- | --------- | --------- | --------- |
| `--bg`      | `#FAFAF7` | `#F4ECD8` | `#121214` |
| `--surface` | `#FFFFFF` | `#FBF5E6` | `#1C1C20` |
| `--text`    | `#1C1B19` | `#3B3226` | `#E6E3DC` |
| `--muted`   | `#6B6860` | `#7A6C58` | `#9A968C` |
| `--border`  | `#E6E3DC` | `#E0D4B8` | `#2C2C32` |
| `--accent`  | `#2F6F5E` | `#8A5A2B` | `#7FC8B2` |

Highlight colours (use at about 50% opacity over text): yellow `#FFE58A`, green `#B7E4B0`, blue `#A9D1F5`, pink `#F5B7CC`.

**Typography.** Interface: `system-ui` (Roboto on Android, Segoe UI on Windows). Reading: Literata for Latin and Noto Serif Devanagari for Hindi, with Noto Sans Devanagari for the sans option; bundle as WOFF2 through `@fontsource` packages so they work offline. **Spacing:** 4 px scale. **Radius:** 12 px cards, 20 px bottom sheets. **Motion:** 150 to 250 ms ease-out; honour `prefers-reduced-motion`.

### Web app manifest (`vite-plugin-pwa` config)

```json
{
  "name": "Folio",
  "short_name": "Folio",
  "description": "Offline reading library",
  "start_url": "/?source=pwa",
  "scope": "/",
  "display": "standalone",
  "background_color": "#FAFAF7",
  "theme_color": "#FAFAF7",
  "orientation": "any",
  "categories": ["books", "productivity"],
  "icons": [
    { "src": "/icons/icon-192.png", "sizes": "192x192", "type": "image/png" },
    { "src": "/icons/icon-512.png", "sizes": "512x512", "type": "image/png" },
    {
      "src": "/icons/maskable-512.png",
      "sizes": "512x512",
      "type": "image/png",
      "purpose": "maskable"
    }
  ],
  "shortcuts": [
    { "name": "Search", "url": "/#/search" },
    { "name": "Import", "url": "/#/import" }
  ],
  "share_target": {
    "action": "/share-target",
    "method": "POST",
    "enctype": "multipart/form-data",
    "params": {
      "title": "title",
      "text": "text",
      "url": "url",
      "files": [
        {
          "name": "files",
          "accept": [
            ".epub",
            ".pdf",
            ".html",
            ".htm",
            ".txt",
            "application/epub+zip",
            "application/pdf",
            "text/html",
            "text/plain"
          ]
        }
      ]
    }
  },
  "file_handlers": [
    {
      "action": "/open",
      "accept": {
        "application/epub+zip": [".epub"],
        "application/pdf": [".pdf"]
      }
    }
  ]
}
```

`file_handlers` makes Windows offer "Open with Folio"; handle launches through `window.launchQueue`. The icons must be designed, not placeholders: a simple monogram on a solid background, with the maskable version keeping its mark inside the central 80%.

### Native-feel checklist

1. **Installed look.** Standalone display, theme colour matching the active theme (update the `theme-color` meta tag when the theme changes), splash from `background_color` and the icon, `viewport-fit=cover` with `env(safe-area-inset-*)` padding.
2. **Navigation.** Bottom bar on phones, sidebar on wide screens. Use real routes so the Android back gesture closes sheets and the reader before leaving the app, and never leaves a blank page.
3. **Touch.** Minimum 48 px targets; no hover-only actions; `-webkit-tap-highlight-color: transparent`; `user-select: none` on interface chrome only, never on reading content; `overscroll-behavior: none` so there is no pull-to-refresh bounce.
4. **Sheets and menus.** Use bottom sheets (`vaul`) for contents, typography, document details and filters on phones; dialogs only on wide screens.
5. **Instant.** Precache the whole app shell and fonts; show skeleton cards instead of spinners; transitions between library and reader use the View Transitions API where supported.
6. **Feedback.** A 10 ms `navigator.vibrate` on long-press and highlight where supported; toasts for confirmations with Undo for destructive actions.
7. **Updates.** When a new service worker is waiting, show an "Update available" toast; apply it only when tapped, never while a book is open.
8. **Install.** Capture `beforeinstallprompt` and show an Install button in Settings; if already installed, show nothing.
9. **Performance targets** on a mid-range Android phone: cold start to usable Library under 2 seconds with 500 documents; reader opens a 5 MB EPUB in under 1.5 seconds; 60 fps list scrolling.
10. **Accessibility.** Visible focus rings on Windows, proper labels on icon buttons, and system font scaling respected in the interface.

## 10. Security, legal guardrails and testing

### Security

- **Content security policy,** set as headers on the host (for example a `_headers` file on Cloudflare Pages): `default-src 'self'; script-src 'self' https://accounts.google.com; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; font-src 'self'; connect-src 'self' https://www.googleapis.com https://accounts.google.com; frame-src 'self' blob: https://accounts.google.com; object-src 'none'; base-uri 'none'; form-action 'self'`. Drop the Google entries until phase 6.
- **No scripts from content.** Book and article HTML is rendered in sandboxed iframes with scripts disabled. Sanitize at import and never trust a stored file.
- **Archive safety.** When reading ZIP or EPUB files, reject entry names containing `..` or starting with `/`; cap entry count (20,000), total uncompressed size (1 GB) and compression ratio; warn on files over 200 MB.
- **Bridge safety.** The clip bridge checks origin, source window and a one-time nonce (section 6). The extension bundles all code locally and loads nothing remotely.
- **Privacy.** No analytics, no telemetry, no network calls except app updates and optional Drive sync. Never log document text. Keep errors local.
- **Secrets.** The Drive client ID is public by design; access tokens stay in memory only.

### Legal guardrails

- Folio never removes or works around DRM. A protected file is detected, refused with a clear message, and left alone.
- No crawlers and no site-specific scrapers in version one. The clipper acts only on the page the person is viewing, when they ask.
- Every clip stores its source URL and capture date. Show them in the document sheet.
- On first launch show one short note: Folio is for content you own or are allowed to keep; check each source's terms.
- Any future adapter for a specific site requires reading that site's terms first and a written decision in `docs/decisions.md`.

### Testing

| Level      | Tool                                                                 | What to cover                                                                                                                                                      |
| ---------- | -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Unit       | Vitest                                                               | `normalise`, tokeniser, file detection, EPUB and PDF parsing, DRM detection, slug rules, ZIP safety limits, manifest recovery, merge rules, backup round trip      |
| Component  | Testing Library                                                      | Library list and filters, Import errors, Reader preferences, Clip inbox preview                                                                                    |
| End to end | Playwright (Pixel 7 emulation and desktop Chromium, offline mode on) | Import then read then highlight then reload; search in English and Hindi; backup then clear storage then restore; clip delivery through a simulated bridge message |
| Manual     | Real devices                                                         | See the checklist below                                                                                                                                            |

**Fixtures** (`packages/core/tests/fixtures`): create small synthetic or public-domain files only; never commit copyrighted books. Needed: an English EPUB, a Hindi EPUB, a text PDF, a scanned PDF, an EPUB with a synthetic `encryption.xml` for the DRM check, a SingleFile-style HTML page, a UTF-8 BOM TXT file, and five Hindi articles for search tests.

**Manual device checklist:**

- Android: install from the Chrome menu; open in airplane mode; back gesture closes sheets then the reader; share a file from the Files app into Folio; Settings shows storage persistence as granted; rotate while reading.
- Windows: install from Edge or Chrome; open an `.epub` with Folio from Explorer; clip five different news and blog pages and check the preview.
- Both: export a backup on one device and restore it on the other; compare the document counts.

**Continuous integration:** on every push run lint, `tsc --noEmit`, unit tests, end-to-end tests and a production build.

**Definition of done for any phase:** its acceptance checks pass; no console errors or warnings; the feature works offline; new decisions are logged; the person has a short "how to try it" note.

## 11. Build phases and prompts

Save this document as `docs/spec.md` in the repository first. Give the coding tool one phase at a time, ask it for a short plan before it writes code, and commit after each phase passes its checks. Each prompt below assumes the tool can read `docs/spec.md`.

### Phase 0: Scaffold

**Goal:** an empty but correct monorepo that builds, lints, tests and installs as a PWA.

- pnpm workspace with `packages/core`, `apps/web`, `apps/clipper` (empty shell), strict TypeScript, ESLint, Prettier, Vitest, Playwright, GitHub Actions.
- `apps/web`: Vite, React, Router, Tailwind with the theme tokens, `vite-plugin-pwa` with the manifest from section 9, a placeholder Library screen with bottom navigation.
- `OpfsStore` implementing `FileStore`, with unit tests.

```text
Read docs/spec.md fully. Implement Phase 0 only. First reply with a short plan and the exact package list with versions you will install. Then scaffold the monorepo as in section 3, implement the theme tokens and manifest from section 9, the FileStore interface and an OPFS implementation from section 4 with tests, and CI. Stop when `pnpm lint`, `pnpm typecheck`, `pnpm test` and `pnpm build` all pass and the app installs as a PWA on localhost. Report what passed.
```

### Phase 1: Reader

**Goal:** import EPUBs and read them well on a phone and on Windows.

- EPUB importer, DRM detection, manifest and `meta.json` writing, duplicate detection by hash, startup recovery.
- Library screen with cards, sorting, and "Continue reading".
- EPUB engine spike (section 7), then `EpubEngine` behind `ReaderEngine`; typography sheet, themes, table of contents, progress saving, gestures.

**Acceptance:** an English and a Hindi EPUB import, open offline, reopen at the saved position, and keep their typography choices after a reload.

```text
Implement Phase 1 from section 11 of docs/spec.md. Start with the one-day EPUB engine spike described in section 7: build a throwaway page that opens the three test files in epub.js and foliate-js, tell me the comparison results and your recommendation, and wait for my choice. Then implement the EPUB importer (section 6), the data rules (section 4), the Library screen and the Reader with typography, themes, contents, progress and gestures (sections 5, 7, 9). Add unit and Playwright tests for every acceptance check.
```

### Phase 2: Article clipper

**Goal:** clip an article on Windows and read it on both devices.

- `packages/core/clip`: sanitizer, image rewriting, clean HTML template, EPUB builder; Clip inbox screen with preview and Save.
- `apps/clipper`: Manifest V3 extension with toolbar button, context menu, shortcut, Readability, image fetching, storage, and the bridge to the PWA; `.folioclip` fallback.
- HTML and TXT file importers; `HtmlEngine`.

**Acceptance:** five varied article pages clip, preview, save, search-ready, and open offline with their images; a malicious test page with scripts and event handlers produces clean output.

```text
Implement Phase 2 from section 11 of docs/spec.md, following section 6 exactly, including the sanitizer allow-list, the bridge origin and nonce checks, and the EPUB 3 structure. Write a test page containing script tags, inline event handlers, javascript: links and an iframe, and prove with a unit test that none survive. Validate generated EPUBs with EPUBCheck in CI if Java is available, otherwise explain what you checked instead.
```

### Phase 3: Library and search

**Goal:** the library stays useful at hundreds of documents.

- Tags, collections, document sheet, metadata editing.
- Search worker, normaliser, tokeniser, index persistence and rebuild, results screen with snippets; open the reader at a hit.
- Highlights, notes, bookmarks, Notes screen, Markdown export of notes.
- PDF importer and `PdfEngine`.

**Acceptance:** all Hindi and English search tests in section 7 pass; a 500-document generated library searches in under 100 ms; highlights survive reload.

```text
Implement Phase 3 from section 11 of docs/spec.md. Search must follow section 7 precisely, especially the tokeniser regex that keeps Devanagari combining marks and the rule against stripping them. Write the Hindi tests first, show them failing, then make them pass. Add a script that generates a 500-document test library and a benchmark for query time.
```

### Phase 4: Backup and restore

**Goal:** the library can be moved and recovered with no loss.

- Streaming backup ZIP, restore with checksum verification, merge and replace modes, summary screen, last-backup reminder, storage and persistence status in Settings.

**Acceptance:** export, wipe, restore, and every file hash matches; restoring twice changes nothing; a backup made on Windows restores on Android and the reverse.

```text
Implement Phase 4 from section 11 of docs/spec.md, following the restore and merge rules in section 8 exactly. Include tests for corrupted ZIPs, ZIPs with path traversal entries, and idempotent restore. Stream the ZIP; do not build the whole archive in memory.
```

### Phase 5: Native-feel polish

**Goal:** it feels like an Android app, not a website.

- Work through the checklist in section 9 item by item: share target, file handlers, shortcuts, View Transitions, sheets, haptics, update toast, install button, skeletons, safe areas.
- Performance pass against the targets; accessibility pass.

**Acceptance:** every checklist item is verified on a real phone and recorded in `docs/decisions.md` with pass or fail and a note.

```text
Implement Phase 5 from section 11 of docs/spec.md. Produce a table of the ten items in the native-feel checklist with how each is implemented and how to verify it by hand on a phone. Fix anything that fails. Run Lighthouse on mobile and report the PWA, performance and accessibility results.
```

### Phase 6: Drive sync and Windows folder mode

**Goal:** the library mirrors to Google Drive; Windows can use a real folder.

- `SyncProvider` for Drive with the sign-in, state file, algorithm, resumable uploads and progress sheet from section 8; Settings screen for the client ID and Wi-Fi-only option.
- Optional `FsAccessStore` for a chosen Windows folder.

**Acceptance:** two devices converge on the same documents, notes and progress after sync; interrupting an upload and retrying never corrupts the library; nothing is deleted remotely.

```text
Implement Phase 6 from section 11 of docs/spec.md using the Google Identity Services token flow and the drive.file scope only. Mock the Drive API in tests. Show me the exact steps I must take in Google Cloud Console to create the OAuth client ID and what to paste into Settings. Add the Drive hosts to the content security policy only now.
```

### After version one

Wrap the web app with Capacitor if the PWA still lacks background sync or system integration; consider client-side encryption for Drive; add site-specific importers only after reading each site's terms; add reading statistics and bilingual interface strings.
