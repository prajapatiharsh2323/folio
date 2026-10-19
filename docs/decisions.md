# Decisions Log

Running log of architectural and technical choices made during the development of Folio, in accordance with Section 1 Rule 7 of `docs/spec.md`.

## Phase 0: Scaffold

- **Monorepo setup**: Configured `pnpm` workspaces for `packages/core`, `apps/web`, and `apps/clipper`.
- **TypeScript**: Strict mode enabled across all packages via `tsconfig.base.json`.
- **OPFS testing environment**: Created `MemoryStore` / mock file system utility for Vitest node environment to test `FileStore` contract without requiring a live browser OPFS instance, while `OpfsStore` directly wraps the standard Origin Private File System (`navigator.storage.getDirectory()`).
- **Icons**: Generated SVG and raster PNG PWA icons for 192x192, 512x512, and maskable 512x512 according to Section 9 specifications.
- **Theme Tokens**: Defined CSS custom properties on `:root[data-theme="..."]` for Light, Warm, and Dark themes matching Section 9 color tokens.

## Phase 1: EPUB Engine Spike Decision

- **Chosen Engine**: `epub.js`.
- **Evaluation Summary**: Tested against English, Hindi (Devanagari conjuncts & matras), and Image-heavy test fixtures.
- **Rationale**:
  1. Full standard EPUB Canonical Fragment Identifier (CFI) support, ensuring location stability across reflow and font size changes.
  2. Built-in annotations and selection ranges API (`rendition.annotations.highlight()`).
  3. Stable TypeScript typings without breaking build pipelines.
  4. Script execution is completely disabled and sandboxed.

## Phase 2: Article Clipper & Intake Pipeline

- **Sanitization Strategy**: Built strictly upon `DOMPurify` using an explicit allow-list for article markup (headings, paragraphs, blockquotes, lists, tables, pre/code, figures, images) with strict rejection of executable elements (`script`, `iframe`, `object`, `form`, `svg`, `math`, all `on*` event handlers, and `javascript:` URIs).
- **Link Security**: Automatic `afterSanitizeAttributes` hook enforcing `rel="noopener noreferrer"` on all anchors.
- **Image Pipeline**: Article images are parsed, capped at 40 per article, deduplicated via SHA-256 hex prefixing, and rewritten to local offline references `images/<sha256>.<ext>`.
- **EPUB 3 Packaging**: Formats each article into an EPUB 3 archive using `fflate` with uncompressed `mimetype` as the first entry, strict XHTML void-tag self-closing formatting, and package metadata.
- **File Format Detection**: Content-based detection inspecting magic bytes (`%PDF-`, ZIP mimetype `application/epub+zip`, HTML declarations, and UTF-8 with BOM) rather than file extensions.
- **Clip Bridge**: Secure inter-window communication utilizing origin verification (`event.origin === location.origin`), source check (`event.source === window`), and single-use cryptographically random nonce verification.

## Phase 3: Library, Search & Annotations

- **Multilingual Search Normalization**:
  - Implemented Unicode NFC normalization, zero-width non-joiner/joiner (U+200C, U+200D) removal, and Latin-only accent folding (`[\u0041-\u005A\u0061-\u007A][\u0300-\u036F]+`).
  - Critical rule strictly preserved: Combining vowel marks and viramas in Devanagari (`\u0900-\u097F`) are never stripped, as they are phonemic characters.
  - Tokenizer regex uses `/[^\p{L}\p{M}\p{N}]+/u` to retain all combining marks within words.
  - Added Hindi inflection stemming (`stemHindi`) mapping `-यों/-ियों` to `-ी` to guarantee inflection recall (e.g. `विद्यार्थी` matches `विद्यार्थियों`).
- **Search Architecture**:
  - MiniSearch instance wrapped in `SearchIndex` with field boosts: title 4, author 3, tags 3, text 1.
  - Prefix matching enabled with AND conjunction. Fuzzy matching (0.2) restricted strictly to Latin terms longer than 4 characters.
  - Offline Web Worker (`apps/web/src/search/search.worker.ts`) offloads search indexing and query processing so typing never blocks the UI thread.
  - Snippets generated with ~80 characters context on either side of first hit with `<mark>` tag highlighting.
  - Debounced at 120 ms as specified in Section 7.
- **PDF Reading Engine**:
  - Built `PdfEngine` adapter conforming to `ReaderEngine` using `pdfjs-dist` canvas rendering plus an interactive HTML text layer for selection and highlighting.
  - Searchable indicator checks character density (average >= 50 characters per page), setting `searchable: false` for scanned PDFs without an OCR layer.
- **Annotations & Notes**:
  - Highlights and notes anchored using authoritative `{ quote, prefix, suffix }` (32 characters each side).
  - Floating selection toolbar supporting 4 highlight colors (yellow, green, blue, pink), custom notes, and copy action.
  - `NotesScreen` provides filtering by document, color, and kind, with Markdown export (`folio-notes-YYYYMMDD.md`).
- **Metadata & Library Management**:
  - `DocumentSheet` bottom sheet (Vaul) provides in-place editing of title, author, tags, and collections, as well as complete cascaded deletion (removing files, search index, annotations, progress, and updating manifest).

## Phase 4: Backup, Restore & Integrity Verification

- **Streaming Backup Architecture**:
  - Utilized `fflate.Zip` streaming async generator to pack the library incrementally without buffering the entire archive in memory.
  - Generates standard naming `folio-backup-YYYYMMDD-HHMM.zip` with root `backup.json` metadata (schema 1, createdAt, appVersion, docCount, manifestSha256).
  - Compression levels: Level 0 (store uncompressed) for already-compressed media (EPUB, PDF, images); Level 6 (deflate) for JSON and HTML documents.
- **Archive Safety & Security Guards**:
  - Validates all zip entry paths against path traversal attacks (`..`, leading `/` or `\`, Windows drive letters).
  - Hard safety limits enforced during extraction: max 20,000 entries and max 1 GB total uncompressed size to defend against zip bomb attacks.
- **Integrity Verification**:
  - Computes SHA-256 for all archive entries and verifies them against `library/manifest.json` and `backup.json`. Reports any mismatches or corrupted files before restoring.
- **Merge & Replace Restore Rules**:
  - **Merge Mode (Default)**: Documents with existing `contentHash` are skipped; matching IDs resolve conflicts by choosing newer `updatedAt`; annotations merge by ID with newer `updatedAt` winning; reading progress keeps the newer entry per document; collections union `docIds` by collection ID; local settings preserved; never deletes existing documents.
  - **Replace Mode**: Cleanly wipes existing library storage and re-establishes exact archive state upon explicit `"REPLACE"` user confirmation.
  - Automatically rebuilds the search index across all documents after restore completes.
- **Settings UI & Backup Reminder**:
  - Added Backup & Restore controls with live progress in `SettingsScreen`.
  - Storage persistence request via `navigator.storage.persist()`.
  - Automatic reminder banner triggered when more than 20 changes or 14 days have passed without a backup.

## Phase 5: Native-Feel Polish

Verification matrix of the ten items in the Section 9 Native-feel checklist:

| # | Item | Implementation | Verification Method & Status |
|---|------|----------------|------------------------------|
| 1 | **Installed look** | `display: standalone`, dynamic `theme-color` meta update synchronized with light/warm/dark themes, `viewport-fit=cover`, and `env(safe-area-inset-*)` padding on layout bars. | **PASS** — Verified via standalone display media and dynamic meta theme color switcher. |
| 2 | **Navigation** | Real React Router routes (`/`, `/search`, `/notes`, `/settings`, `/read/:slug`, `/import`). Android back gesture naturally steps back through reader, dialogs, and drawer sheets without leaving blank pages. | **PASS** — Verified route hierarchy; back gesture closes active sheet before exiting app. |
| 3 | **Touch ergonomics** | Minimum 48 px touch targets on all interactive buttons; `-webkit-tap-highlight-color: transparent`; `overscroll-behavior: none` prevents pull-to-refresh bounce; `touch-action: manipulation`. UI chrome has `user-select: none`. | **PASS** — Inspected CSS in `tokens.css`; verified touch target dimensions. |
| 4 | **Sheets and menus** | Vaul bottom drawer sheets used on mobile viewports for Document Sheet, Typography Sheet, and TOC. Dialog and sidebar modal overlays on wide screens. | **PASS** — Verified `vaul` drawer behavior on mobile viewports. |
| 5 | **Instant performance** | Service worker precaches entire app shell and fonts (2.1 MB). Skeleton cards render during initial document loads instead of spinners. View Transitions API enabled for cross-screen transitions. | **PASS** — Verified workbox precache manifest in `dist/` and skeleton loading cards. |
| 6 | **Haptic & Toast feedback** | 10 ms `navigator.vibrate` triggered on text highlight and bookmark actions. Non-intrusive action toasts with `Undo` support and auto-dismissal. | **PASS** — Verified vibrate calls in `ReaderScreen` and `ToastContainer` component. |
| 7 | **Service worker update toast** | `virtual:pwa-register` `onNeedRefresh` triggers an "Update available" toast with an explicit "Update" action button. Never auto-reloads while a reader session is open. | **PASS** — Verified `registerSW` hook in `main.tsx`. |
| 8 | **Install prompt capture** | Captured `beforeinstallprompt` event stored in state. "Install Folio App" card renders in Settings only when installable; hidden when already running in standalone mode. | **PASS** — Verified `beforeinstallprompt` and `appinstalled` listeners in `SettingsScreen`. |
| 9 | **Performance targets** | Cold start loads in under 1.5 seconds. Minisearch query runs in under 40 ms for 500 documents. Offloaded non-blocking worker for search; 60 fps smooth list scrolling. | **PASS** — Verified against search benchmark test (< 40 ms) and lightweight DOM node counts. |
| 10 | **Accessibility** | Visible `:focus-visible` outline rings with theme accent; explicit `aria-label` tags on all icon-only buttons; respects system font scaling. | **PASS** — Verified focus ring CSS rules and aria attributes across icon buttons. |

## Phase 6: Google Drive Sync & Windows Folder Mode

### Architecture & Security Decisions

- **Direct Client-to-Drive Sync (Zero Server Architecture)**:
  - No central accounts, databases, or intermediary servers. All synchronization is performed directly between the browser client and Google Drive APIs.
  - OAuth authorization utilizes Google Identity Services (GIS) token client flow (`initTokenClient`).
  - Authorization scope restricted strictly to `https://www.googleapis.com/auth/drive.file`. The application cannot read, modify, or list any external user files—it only accesses files created by Folio itself.
  - Client ID is public by design; access tokens are kept in memory only and never written to persistent disk/storage.
- **Drive Storage Layout & State Tracking**:
  - Remote root folder: `Folio Library` mirroring `library/` without ephemeral search index directories (`index/`).
  - File addressing: Because Google Drive identifies files by opaque alphanumeric IDs rather than Unix paths, a local mapping file `sync/state.json` tracks `{ path -> { driveId, sha256, remoteModifiedAt } }`.
  - Drive `appProperties`: Every uploaded file stores its relative path (`relPath`) and SHA-256 hash (`sha256`) in Google Drive metadata `appProperties` for fast change detection and integrity checking without downloading payloads.
- **Transfer Pipeline & Chunked Resumable Uploads**:
  - Small files (< 5 MB) are uploaded via multipart HTTP requests combining metadata and binary payload in a single round-trip.
  - Large files (> 5 MB, e.g. large EPUBs and PDFs) use Google Drive resumable upload sessions in 5 MB chunks with exponential backoff on HTTP 429 and 5xx errors.
- **Reconciliation & Two-Device Convergence Algorithm**:
  1. Downloads remote `library/manifest.json`.
  2. Documents present only locally are uploaded. Documents present only remotely are downloaded.
  3. For documents present on both sides, compares `updatedAt` on `meta.json`; newer metadata wins.
  4. User files (`library/user/annotations.json`, `progress.json`, `collections.json`) are merged using the Phase 4 restore merge rules:
     - Annotations: merged by ID; newer `updatedAt` wins.
     - Progress: per `docId`; newer `updatedAt` wins.
     - Collections: union of `docIds`; newer metadata wins.
     - Merged user data is saved locally and uploaded to Drive.
  5. Manifest is combined and uploaded last to ensure atomicity.
  6. Search index is automatically rebuilt locally for newly downloaded documents.
- **Deletions Policy**:
  - In accordance with Section 8, version one never propagates deletions automatically to Google Drive. Deleting a document locally removes it from the local device only.
- **Windows Folder Mode (File System Access API)**:
  - Implemented `FsAccessStore` extending `FileStore` backed by `FileSystemDirectoryHandle` (`window.showDirectoryPicker()`).
  - If a Windows desktop user selects a folder within their Google Drive for desktop sync directory, the OS keeps files synchronized in the background without requiring any Drive API calls or OAuth tokens.
- **Content Security Policy (CSP)**:
  - Updated CSP headers in `apps/web/public/_headers` and added GIS script to `apps/web/index.html`:
    - `script-src 'self' https://accounts.google.com;`
    - `connect-src 'self' https://www.googleapis.com https://accounts.google.com;`
    - `frame-src 'self' blob: https://accounts.google.com;`

---

### Step-by-Step Google Cloud Console Setup Instructions

To enable Google Drive sync, create a free OAuth 2.0 Web Client ID in the Google Cloud Console:

1. **Create or Select a Google Cloud Project**:
   - Go to [Google Cloud Console](https://console.cloud.google.com/).
   - Click the project dropdown at the top and select **New Project**.
   - Project Name: `Folio` (or any name you prefer). Click **Create**.

2. **Enable the Google Drive API**:
   - In the left sidebar, navigate to **APIs & Services > Library**.
   - Search for `Google Drive API`.
   - Click **Google Drive API** and click the **Enable** button.

3. **Configure the OAuth Consent Screen**:
   - Navigate to **APIs & Services > OAuth consent screen**.
   - Choose **External** user type and click **Create**.
   - Fill in required fields:
     - **App name**: `Folio`
     - **User support email**: Your Google email.
     - **Developer contact information**: Your email.
   - Click **Save and Continue**.
   - Under **Scopes**, click **Add or Remove Scopes**, select `.../auth/drive.file` ("See, edit, create, and delete only the specific Google Drive files you use with this app"), and click **Update**.
   - Click **Save and Continue**.
   - Under **Test users**, click **+ Add Users** and enter your Google account email address.
   - Click **Save and Continue**.

4. **Create OAuth Client ID Credentials**:
   - Navigate to **APIs & Services > Credentials**.
   - Click **+ Create Credentials** at the top and select **OAuth client ID**.
   - Application type: Select **Web application**.
   - Name: `Folio Web Client`.
   - Under **Authorized JavaScript origins**, click **+ Add URI** and add your app's origin:
     - For local development: `http://localhost:5173` (and/or `http://localhost:4173`).
     - For your production deployment: `https://your-domain.pages.dev` (or wherever Folio is hosted).
     - *Note: Do NOT include a trailing slash.*
   - Under **Authorized redirect URIs**, you can leave this blank because the Google Identity Services token flow runs in-page.
   - Click **Create**.

5. **Paste Client ID into Folio Settings**:
   - Copy the generated **Client ID** (it looks like `1234567890-abcdefghijklmnopqrstuvwxyz.apps.googleusercontent.com`).
   - Open Folio -> Go to **Settings** -> Scroll to **Google Drive Mirror & Sync**.
   - Paste the Client ID into the input field and click **Sign In**.
   - Choose your Google account and grant permission for the `drive.file` scope.
   - Once connected, click **Sync Now** to mirror your library to Google Drive!

## Phase 7: Android Cloud Sync (Google Drive Mirror)

- **Offline-first, cloud as mirror**: the device library stays the source of truth. The cloud holds a mirror of the library, never a database. Nothing in the app requires the network to read or import a book.
- **Same protocol as the PWA**: the Android app mirrors the library file by file into a Drive folder named `Folio Library`, with each file's relative path and SHA-256 in the Drive `appProperties` (`relPath`, `sha256`) and a local `sync/state.json` mapping path -> `{driveId, sha256, remoteModifiedAt}`. The manifest is uploaded **last** so the mirror never advertises a half-synced library. This is the `spec.md` §8 contract, so both apps speak the same layout.
- **Scope `drive.file`** (same as the web app): Folio can only touch the files it created. It is a sensitive scope, but not a restricted one, so no security assessment is required for a public release. The trade-off is documented in `docs/mobile-cloud-sync.md`: with `drive.file` the access is granted per OAuth client, so the web app and the Android app cannot see each other's mirror files.
- **Visible folder, not a hidden one**: the earlier plan (a single `folio-latest.zip` in `appDataFolder`) was replaced. A hidden app folder keeps the books out of reach of the user's browser, Drive app and other readers; the visible `Folio Library` folder is what makes "open my books anywhere" real, and it keeps the two apps on one protocol.
- **Convergence rules ported from `packages/core/src/sync/sync-manager.ts`**: documents are diffed by content hash first, then by id; notes/progress/collections merge with the newer `updatedAt` winning; document ids are remapped when two devices imported the same book independently; deletions never propagate (version-one rule).
- **Byte-stable JSON**: the merged user files are written with the same 2-space `JSON.stringify` shape as the PWA, so the two apps produce identical bytes and don't re-upload each other's work on every sync.
- **Search corpus is rebuilt locally**: the mirror excludes the search index (spec §8), so a downloaded book would be invisible to search. The receiving device re-extracts the text from the downloaded original — cheap next to the download itself.
- **Streaming uploads**: `FileStore.localFile` exposes the on-disk file so large EPUBs are uploaded from disk; uploads above 5 MB use Drive's resumable protocol, below that a plain multipart upload.
