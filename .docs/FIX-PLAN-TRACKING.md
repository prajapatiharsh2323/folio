# Folio — UX/UI & Reading-Experience Fix Plan Tracking

Source plan: UX/UI & Reading-Experience Audit, 26 October 2026.
Audited checkout: `mobile/` (native Android/Kotlin + Compose). MainActivity.kt and
ReaderActivity.kt ARE present in this checkout and were inspected directly.

Status legend
- **DONE** — implemented and verified in source (tests added where practical).
- **PARTIAL** — core fixed, UX/wiring gap remains.
- **OPEN** — not implemented in this checkout.
- **DEVICE** — implementation exists but needs a real-device/gesture/TalkBack pass.

Evidence rule: each row names the file (and test) that proves the current state.
Update this file in the same commit as the fix.

Last updated: 26 Oct 2026 (session FIX-D).

---

## Phase A — data safety (P0)

| ID | Status | Evidence / notes |
|---|---|---|
| P0-01 Corrupt manifest overwritten | **DONE** | `LibraryManager.readOrCreateManifest()` throws `LibraryFileCorruptException` for a corrupt index; typed `readManifestState()`; `repairManifest()` preserves bytes under `library/recovery`; MainActivity renders `Library index needs repair` and disables import. Tests: `LibraryCorruptionTest` (`corrupt manifest blocks addDocument…`, `repairManifest rebuilds…`). |
| P0-02 Corrupt user JSON shown as empty / overwritten | **DONE** | Typed `ReadResult` + strict RMW (`saveProgress`, `saveAnnotation`, collection mutations throw); `discardCorruptUserState` preserves bytes; Notes shows an unreadable-file state. Tests: `LibraryCorruptionTest`. Library shelf now reads progress/collections through the typed API and shows a `Saved … can't be read` review banner instead of a fake empty state (FIX-B). |
| P0-03 Replace restore wipes before write | **DONE** | `RestoreSwap` stages the whole tree, verifies digests, atomic swap + rollback snapshot; process-death rollback via `rollbackPending`. Tests: `BackupRestoreAtomicityTest` (staging failure, rename failure, interrupted swap). |
| P0-04 Restore silently skips malformed user JSON | **DONE** | `parseUserState()` refuses malformed annotations/progress/collections before any mutation; summary carries `warnings`. Tests: `BackupRestoreAtomicityTest`. Settings now merges `summary.warnings` + verification issues into the result and labels it “Restore completed with warnings”. |

## Phase B — reader controls (P1)

| ID | Status | Evidence / notes |
|---|---|---|
| P1-01 Delete reports success after cleanup failures | **DONE** | `deleteDocument(): DeleteResult(manifestRemoved, cleanupWarnings)`; folder-removal failure aborts before manifest rewrite. MainActivity now reports “Not deleted…”, “Deleted, but … couldn't be cleaned up”, or success; damaged-doc removal does the same. |
| P1-02 Interrupted import “finishable” on meta only | **DONE** | `scanInterruptedImports()` now calls `orphanPayloadProblem()`: meta.json must parse, the original/clean file must exist and be non-empty, `contentHash` must match SHA-256, and non-PDF formats must have a non-empty reading file. `finishInterruptedImport()` enforces the same check before writing the manifest; delete-only rows carry the reason. Tests: `scanInterruptedImports requires a real reading payload and matching checksum` (also asserts the finish API refuses). |
| P1-03 Single-file import only | **DONE** | `MainActivity` uses `ActivityResultContracts.OpenMultipleDocuments()`; files import sequentially with “Importing N of M · name” progress, duplicate prompt per file (Skip/Replace/Keep both), per-file failures isolated from the rest of the batch, and an end-of-batch failure report. ACTION_VIEW reuses the same one-item path. Verified by `:app:compileDebugKotlin` + `:app:testDebugUnitTest` + `:app:assembleDebug`. |
| P1-04 Duplicate auto-skip | **DONE** | Duplicate dialog offers Skip / Replace / Keep both and states the consequence. `replaceDocument()` migrates highlights/notes, progress and collection membership to the new copy before removing the old folder; test `replaceDocument migrates notes progress and collections to the new copy`. |
| P1-05 Debug sample button in empty library | **DONE** | Sample import is wrapped in `if (!importing && BuildConfig.DEBUG)`; release empty state has only “Import a book”. |
| P1-06 Progress slider 28dp / weak semantics | **DONE (DEVICE)** | `ReaderChrome.Pillar` slider now has a 48dp interactive height with `contentDescription = "Reading progress"` + `stateDescription = "N% read"`; the visual track stays slim. Device scrub/TalkBack pass still required. |
| P1-07 Appearance sliders persist only on drag end | **DONE (DEVICE)** | `ReadingAppearanceSheet(onPreview=…)`; text size, line spacing, margins and dimming call `onPreview` on every drag change (applies to the navigator via `ReaderActivity.previewPrefs`) and persist once via `onPrefsChange` on drag end. A live “Aa” preview sample was added. Dismiss behaviour: live changes are kept (same as persisted value). |
| P1-08 Theme row clips at narrow width / large text | **DONE (DEVICE)** | `ThemePicker` now lays out options in rows of three (`chunked(3)`) with `maxLines = 2`, centered labels, padded trailing cells, and keeps `selectableGroup`/selected semantics. Needs 320dp + 1.3×/1.5× screenshot pass. |
| P1-09 Bookmark always says “Add bookmark” | **DONE (DEVICE)** | `ReaderChrome(bookmarked=…)` shows “Add bookmark”/“Remove bookmark” with a primary-tinted icon; `ReaderActivity` tracks `currentLocation` + `bookmarkLocations` and `onBookmarkTapped()` toggles the existing bookmark (also removes its decoration) with “Bookmark added/removed” toast. Persistence across reopen still needs device confirmation. |
| P1-10 Busy backup/restore rows look tappable | **DONE** | `FolioSettingsRow(enabled=…)` disables clicks/dims text; Settings shows “Exporting backup…” / “Restoring backup…” with the progress line and blocks repeat taps. |
| P1-11 Notes shows raw document ID | **DONE** | `NotesActivity` renders “Book unavailable” + “No longer in your library” for an annotation whose doc is gone; tapping shows an explanatory snackbar instead of launching ReaderActivity. The raw doc ID is never displayed. |
| P1-12 Notes blames free space for any error | **DONE** | Notes load/mutation copy no longer mentions free space; corrupt files get a specific preserved-file message. |
| P1-13 Search result loses exact match boundary | **DONE** | `ReaderSearch.buildSnippet()` centers the excerpt on the match and returns `Snippet(text, matchStart, matchEnd)`; `Hit` carries `matchStart/matchEnd/hasMatch`; `sectionTitle()` no longer falls back to a raw file name; the search sheet bolds the exact span and shows “Section” when unnamed. Tests: `ReaderSearchTest` (match-aware snippet keeps the query…, reports the exact span, is null without a match, missing title never falls back…). |
| P1-14 Recovery banners hidden in search mode | **DONE** | `SearchPanel` receives `recoveryCount` and renders a compact “Needs attention · N / Review” row; Review opens the matching repair, damaged-doc, interrupted-import or corrupt-user-state flow. Full banners stay in the shelf. |
| P1-15 Delete collection leaves stale doc IDs | **DONE** | `deleteCollection()` now holds the collections+manifest locks, removes the collection and clears its id from every affected `meta.json`; returns `CollectionDeleteResult` for partial-cleanup reporting. Test: `LibraryManagerTest` (`deleteCollection clears doc metadata but keeps the books`). |

## Phase C/D/E — P2 quality

| ID | Status | Notes |
|---|---|---|
| P2-01 Reader back label says “Back to library” | **DONE** | Back action description is now “Go back”, correct from Library, Notes and bookmarks. |
| P2-02 Search field has no accessible label | **DONE** | `FolioSearchField(label = placeholder)` adds a `contentDescription` semantics label to the text field, so TalkBack announces its purpose instead of an unlabeled edit box. |
| P2-03 Long-press multi-select undiscoverable | **DONE** | Library overflow menu now has a visible “Select books” entry (when the shelf is non-empty) that enters selection mode; long-press remains a shortcut. |
| P2-04 Filtered Notes empty state is bare text | **DONE** | Filtered-empty branch uses `FolioEmptyState` with a “Show all” CTA that resets `AnnotationFilter.All`; copy clarifies other saved items remain available. |
| P2-05 Dictionary “No match” guidance | **DONE** | Loading state now says “Looking up…”; no-match copy explains the offline coverage and suggests the base form (“running” → “run”) or a shorter phrase. |
| P2-06 Backup scope unclear | **DONE** | Export row now says “Books, notes, progress and collections. Per-book display settings stay on this device.” |
| P2-07 Restore dialog / pickers at large text | **DONE (DEVICE)** | Restore dialog actions stack vertically at full width (FIX-B) and the theme selector is a wrap-friendly 3-column grid (P1-08/FIX-C). Remaining evidence is the 320dp/landscape/1.3×/1.5× screenshot pass. |
| P2-08 Storage stays “…” on failure | **DONE** | States are “Calculating…”, “Unavailable” and numeric; failures show a Retry storage check row. |
| P2-09 ReaderChrome comments/params drift | **DONE** | Removed unused `onHighlight`, `onPrefsChange`, `perBookOverride`, `onResetPerBook` parameters; header comment now matches the real menu (contents/bookmark) and states highlights come from the selection action mode. All call sites updated. |
| P2-10 “Reader brightness” is an overlay | **DONE** | Section renamed “Page dimming”, slider “Dim the reading page”, with copy: “Dims this reading screen only; it does not change your device brightness.” |
| P2-11 Consequence-specific copy | **DONE** | Collection delete says books stay; book delete names files, highlights, notes, bookmarks and progress; delete warnings are surfaced. Interrupted-import dialog now states that Finish needs the reading file and Delete removes only that unfinished import's leftovers (books, notes and progress unaffected). |

---

## Fix log

### FIX-A — 26 Oct 2026 — collection deletion consistency (P1-15)
- Replaced the broken uncommitted `deleteCollection` with a lock-safe implementation
  (`withLocks(K_COLLECTIONS, K_MANIFEST)` + `readOrCreateManifest` +
  `updateDocMetaLocked`), so deleting a collection removes its ID from every
  affected `meta.json` and rewrites the manifest consistently.
- Added `CollectionDeleteResult`; the collections screen now reports books whose
  metadata cleanup could not be completed instead of a silent success.
- Added regression test: deleting a populated collection clears membership in doc
  metadata and never deletes books.
- Verified: `./gradlew :app:testDebugUnitTest --tests "dev.folio.mobile.core.library.*"` green.

### FIX-B — 26 Oct 2026 — honest data states + settings clarity (P0-02/P0-04/P1-01/P1-04/P1-10/P2-06/P2-07/P2-08)
- Library: typed progress/collections reads; corrupt files now raise a review banner instead of
  rendering as an empty shelf or “no collections”.
- Duplicate Replace: new `LibraryManager.replaceDocument()` migrates annotations, progress and
  collection membership to the replacement copy; tested.
- Delete: MainActivity + damaged-doc removal surface `DeleteResult` warnings/failure.
- Settings: backup scope copy, disabled rows + active-operation label, stacked restore actions,
  honest restore warnings, storage “Calculating…/Unavailable/Retry”.
- Verified: `:app:compileDebugKotlin` + library unit tests green.

### FIX-C — 26 Oct 2026 — reader controls + search match fidelity (P1-06/07/08/09/13, P2-01/07/09/10)
- Progress slider: 48dp hit area, “Reading progress” semantics + percent state.
- Appearance sheet: live preview during drag (`onPreview`), one persisted write at drag end,
  live “Aa” sample; theme picker moved to a 3-column grid with wrap-friendly labels.
- Bookmarks: label/icon reflect the current location; tapping toggles (add or remove) and
  updates the painted decoration set.
- In-book search: match-centered snippet with an explicit span; the sheet bolds the query and
  never shows a raw chapter file name as the section label.
- Cleanup: back button says “Go back”; ReaderChrome unused params/comments removed; dimming
  labelled honestly.
- Verified: `:app:testDebugUnitTest` (full suite) + `:app:assembleDebug` green.

### FIX-D — 26 Oct 2026 — recovery integrity + discoverability (P1-02/03/11/14, P2-02/03/04/05/11)
- Interrupted imports: `finishable` now requires a readable meta.json, an existing non-empty
  original/checksummed file, a matching SHA-256, and a non-empty reading file for every
  non-PDF format. `finishInterruptedImport()` refuses the same invalid payloads, so Finish can
  no longer create a manifest entry that cannot open. Delete-only rows explain why.
- Batch import: picker is multi-select; imports run one file at a time with “Importing N of M”
  progress, a per-file duplicate prompt (Skip/Replace/Keep both), isolated per-file failures,
  and an end-of-batch failures dialog. ACTION_VIEW uses the same one-item path.
- Notes: missing books show “Book unavailable / No longer in your library” and a snackbar
  instead of showing a raw doc ID or opening a dead reader.
- Search mode: compact “Needs attention · N” recovery row opens repair/damaged/orphan/
  corrupt-user-state review instead of hiding recovery entirely.
- Accessibility/discoverability: search field has a TalkBack label; “Select books” is a visible
  overflow action; filtered Notes use the designed empty state with “Show all”; dictionary
  loading/no-match copy was clarified; interrupted-import deletion explains its scope.
- Tests: `LibraryManagerTest` payload/checksum regression + updated recovery fixtures;
  `LibraryUiTest` carries the delete-only reason.
- Verified: `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (full suite),
  `:app:assembleDebug` green.

### FIX-E — 27 Oct 2026 — release hardening: timestamps, corrupt-state preservation, honest reader exits (P0/P1/P2)
- **Timestamps:** new `IsoTime` is the single writer/parser for schema-1 timestamps.
  `LibraryManager.nowIso` previously produced `…947ZZ` on millisecond clocks, which made
  every newer-wins comparison evaluate to epoch 0. `IsoTime` writes fixed milliseconds and
  tolerates legacy `ZZ`; used by `LibraryManager`, `SyncManager`, `BackupRestore` and
  `CollectionRows`.
- **Global reader settings:** `PrefsStore` now exposes typed `readReadingPrefs()`; a corrupt
  `settings.json` throws instead of silently becoming defaults, and `saveReadingPrefs`
  preserves the unreadable bytes under `library/recovery/` before rebuilding. Reader and
  Settings both show an explicit message and continue.
- **Per-book settings:** `DocPrefsStore` strict-reads for writes and preserves a corrupt
  `doc-settings.json` before repairing; reads still fall back to global prefs so the reader
  always opens.
- **Backup merge:** corrupt local manifest/annotations/progress/collections are copied
  byte-for-byte under `library/recovery/…-corrupt-…` before the backup replaces them, and the
  restore summary warns with the recovery path. Restored books now rebuild their search
  corpus (`library/index/text`) because backup archives exclude it.
- **Sync:** an unreadable remote manifest or corrupt local/remote annotations, progress or
  collections aborts before overwrite (typed `SyncDataUnreadableException` → non-retryable
  `DataUnreadable` UI copy; auto-sync disabled until repaired). `SyncManagerTest` covers
  remote manifest, remote notes, local notes/progress/collections.
- **Reader exits:** missing/unreadable book, damaged publication and no-readable-page paths
  now toast a reason before closing instead of vanishing silently.
- **Import cap:** the 50 MB limit is checked from the SAF descriptor and enforced while
  streaming, so an oversized/unknown-length file cannot OOM the process before rejection.
- Verified: `:app:testDebugUnitTest` → 340 tests, 0 failures; `:app:lintDebug` → 0 errors;
  `:app:assembleDebug` → success. Device/TalkBack journeys remain blocked (no device/KVM).

