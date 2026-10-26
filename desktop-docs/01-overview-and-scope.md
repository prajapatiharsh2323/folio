# Folio Desktop — Overview and Scope

> Reference implementation: the shipped native Android app in `mobile/` (Kotlin + Jetpack Compose).
> Everything in this doc set ports that app, 1:1, to Windows using **Tauri 2 + TypeScript/React**.

---

## 1. What Folio is

Folio is a private, local-first reading library for books and articles. The user imports
EPUB / HTML / TXT files (plus web clips), reads them in a calm, distraction-free reader,
highlights and annotates, and can back everything up or mirror it to Google Drive.

Desktop product sentence:

> **Folio Desktop is the same quiet, editorial reading experience as the Android app —
> arranged for a mouse + keyboard window, with the same library structure, annotation
> model, and file formats, so a user can move freely between phone and PC.**

## 2. Goals

1. **Identical reading feel.** Same reader engine philosophy (Readium), same 5 reading
   themes (light / paper / dark / true-black / system), same typography controls and
   ranges, same paginated/scrolled flows, same progress & resume behavior.
2. **Identical library model.** The exact schema-1 JSON data model (see `05-data-model-and-compat.md`),
   byte-compatible with the mobile app's backups and Drive mirror.
3. **Identical UI language.** The "quiet editorial" design system from
   `.docs/design-system.md`, token-for-token (see `03-ui-ux-spec.md`), only re-arranged
   for a windowed desktop layout.
4. **Declarative, reviewable implementation.** Everything browser/JS-runnable lives in the
   TS frontend; the Rust core stays a thin, general file-system + crypto layer.

## 3. Explicit non-goals (parity with mobile v0.2)

Deliberately **excluded**, exactly as the mobile decisions exclude them:

- ❌ Text-to-speech (TTS)
- ❌ PDF reading or PDF import (PDF is detected then refused — see `mobile/.../core/importer/ImportDispatch.kt`)
- ❌ OPDS catalogs
- ❌ Markdown export of notes
- ❌ DRM-protected EPUB (refused via `encryption.xml`/`rights.xml` scan)
- ❌ Cloud sync on by default (Drive sync is opt-in, manual or scheduled)
- ❌ Multi-user accounts, servers, telemetry, network requests beyond Drive sync +
  external-link opening

`ReadingPrefs.pdfZoom` and `ReadingPrefs.maxWidth` are kept in the schema for byte
compatibility but are inert on desktop too (`maxWidth` may be honored later — it
originally meant the PWA's desktop column cap; see §7 in `04-reader-spec.md`).

## 4. Feature parity matrix (v0.2 → desktop v1)

| # | Feature (mobile reference file) | Desktop target | Priority |
|---|---|---|---|
| 1 | Library list with progress bars (`MainActivity.kt`, `LibraryUi.kt`) | Same list in a window w/ hover states | P0 |
| 2 | Import EPUB/HTML/TXT ≤ 50 MB (`ImportDispatch.kt`, importers) | Native file dialog + drag-and-drop onto window / taskbar jump list | P0 |
| 3 | Open-with file association (.epub/.html/.txt) (`AndroidManifest` intent filter) | Windows file association + command-line path args | P1 |
| 4 | Duplicate detection by contentHash → Skip / Keep both / Replace (`LibraryManager.kt`) | Identical dialog | P0 |
| 5 | Everything reads as EPUB 3 (`EpubBuilder.kt`: TXT/HTML → EPUB at import) | Identical pipeline (port to TS; see `02-architecture-and-stack.md` §6) | P0 |
| 6 | Reader: paginated + scrolled flow, spreads auto/none (`ReaderPrefsMapper.kt`) | Readium web navigator, same prefs | P0 |
| 7 | Reader themes: light/paper/dark/true-black/system with exact hexes (`Types.kt` + mapper) | Identical values | P0 |
| 8 | Typography: typeface serif/sans/mono/publisher · size 14–32 · line spacing 1.3–2.4 · margins 12–64 · justify · hyphenate · letter–paragraph spacing (`ReadingAppearanceSheet.kt`) | Identical controls + ranges | P0 |
| 9 | Per-book appearance overrides + reset (`DocPrefsStore.kt`) | Same doc-scoped prefs file `user/doc-settings.json` | P0 |
| 10 | Tap zones: left/right third page turn, middle third chrome toggle (`ReaderActivity`) | ←/→ keys + click zones + hover-guided chevrons (mouse-appropriate mapping in `07-keyboard-and-input.md`) | P0 |
| 11 | Page-turn keys incl. volume keys & Bluetooth page turners (`PageTurnKeys.kt`) | Keyboard: ←/→/PgUp/PgDn/Space; media/page-turner HID keys accepted | P1 |
| 12 | Selection → highlight (4 colors) / note / copy / meaning (`SelectionBridge.kt`, `AnnotationMenu`) | Same floating four-act toolbar, done in the webview | P0 |
| 13 | Notes & highlights with quote+prefix(32)+suffix(32) anchoring (`AnnotationLocators.kt`) | Identical schema + text-quote re-anchor | P0 |
| 14 | Bookmarks = annotations with kind `bookmark` | Same | P0 |
| 15 | Quote-card PNG share (`QuoteCardContent.kt`, FileProvider share) | Render to canvas → save PNG / copy image to clipboard | P1 |
| 16 | Reading pace + time-to-finish ("2 min left") (`ReadingPaceStats.kt`) | Identical forward-only pace accounting | P0 |
| 17 | Library search: MiniSearch port (BM25 k1.2 b0.7, fuzzy 0.2, Devanagari-safe normalization) (`SearchIndex.kt`, `SearchNormalise.kt`) | Port the same algorithms to TS (easier than on Android since the original is JS) | P0 |
| 18 | In-book search (Readium `SearchService`, jump-to-anchor) (`ReaderSearch.kt`) | Readium web search service | P0 |
| 19 | Notes screen: newest-first, filter pills All/Highlights/Notes/Bookmarks, tap→open at location (`NotesActivity.kt`, `AnnotationFilters.kt`) | Same screen as a desktop panel/window | P0 |
| 20 | Collections: two-level gallery → detail, fan covers, add via search+picker (`LibraryCollections.kt`) | Same tour pattern | P0 |
| 21 | Tags + filter chips (All / #tag / collection) | Same chip row | P0 |
| 22 | Sort: recently read / title / author / progress | Same | P0 |
| 23 | Details sheet: edit tags, collection checkboxes, inline create | Same sheet | P0 |
| 24 | Damaged-docs + interrupted-import recovery banners/dialogs (`LibraryManager.kt`) | Same recovery UX | P0 |
| 25 | Settings: reading theme picker, backup, cloud-sync, storage, about (`SettingsActivity.kt`) | Same sections | P0 |
| 26 | Backup ZIP: streaming, SHA-256 manifest, merge/replace restore (`BackupZip.kt`, `BackupRestore.kt`) | Identical ZIP structure and merge semantics | P0 |
| 27 | Google Drive mirror: bidirectional newest-wins, docId remap, exclusions (`SyncProtocol.kt`, `DriveSyncProvider.kt`) | Same protocol; desktop OAuth loopback (see `09-platform-decisions.md`) | P1 |
| 28 | Optional background cloud sync (`CloudSyncWorker.kt`) | Scheduled task / timer while app runs | P1 |
| 29 | Offline eng-hin dictionary (packaged SQLite, D1) (`DictionaryPackInstaller.kt`) | Same pack; query via Rust SQLite bridge or precompiled JSON | P2 |
| 30 | Cover extraction EPUB2/EPUB3; bundled default cover art (`CoverLoader.kt`, `DefaultCoverArt.kt`) | Identical + fallback render | P0 |
| 31 | Keep-screen-on while reading | "Prevent display sleep while reading" switch | P1 |
| 32 | Reader splash with cover + spinner until first locator | Same splash | P0 |

## 5. Platform re-arrangements (mobile → desktop)

| Mobile construct | Desktop equivalent |
|---|---|
| TopAppBar + auto-hiding reader chrome | Menu bar (thin) + reader top bar; larger click targets with hover |
| Bottom sheets (`ModalBottomSheet`) | Side panels (`Panels/Drawer`) or centered dialogs; see `03-ui-ux-spec.md` |
| Bottom "pillar" progress pill | Same pill, docked bottom-center of the reader window |
| Android back gesture | Browser-history-style back: Esc closes sheets/panels; window close quits |
| Haptic feedback | Subtle motion/opacity cues — no sound |
| Volume keys page turns | Keyboard-first; volume keys only if app window has focus (P2) |
| ACTION_VIEW open-with | File associations / drop-open / command line |
| WorkManager periodic sync | Tauri background task while the app runs |
| Android CI | GH Actions building Windows exe (NSIS installer) |

## 6. Success criteria

- Reads the same seconds: open a doc on your phone, resume on Windows within one click
  at the correct chapter + chapter-relative position (progress cascade §8 of `04-reader-spec.md`).
- Restores a mobile backup ZIP with zero diffs (merge or replace).
- Mirrors both directions with the mobile Drive folder without conflicts.
- Keyboard-only usage of the reader is possible end-to-end.
- It feels like Folio: covers flicker smoothly, lists settle quickly, all lists scroll
  stably, everything rendered in the Folio token type/color system.
