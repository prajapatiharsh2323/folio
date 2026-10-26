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

Last updated: 26 Oct 2026 (session FIX-A).

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
| P1-02 Interrupted import “finishable” on meta only | **OPEN** | `scanInterruptedImports()` still sets `finishable = meta != null`; no reading-payload/checksum validation. |
| P1-03 Single-file import only | **OPEN** | `ActivityResultContracts.OpenDocument()` in MainActivity; PRD batch import not implemented. |
| P1-04 Duplicate auto-skip | **DONE** | Duplicate dialog offers Skip / Replace / Keep both and states the consequence. `replaceDocument()` migrates highlights/notes, progress and collection membership to the new copy before removing the old folder; test `replaceDocument migrates notes progress and collections to the new copy`. |
| P1-05 Debug sample button in empty library | **DONE** | Sample import is wrapped in `if (!importing && BuildConfig.DEBUG)`; release empty state has only “Import a book”. |
| P1-06 Progress slider 28dp / weak semantics | **OPEN** | `ReaderChrome.Pillar` still uses `.height(28.dp)` and no semantics. |
| P1-07 Appearance sliders persist only on drag end | **OPEN** | `LabeledSlider` calls `onCommit` only from `onValueChangeFinished`; no live preview path. |
| P1-08 Theme row clips at narrow width / large text | **OPEN** | `ThemePicker` is a fixed 5-column `Row`, `weight(1f)`, `maxLines = 1`, no overflow strategy. |
| P1-09 Bookmark always says “Add bookmark” | **OPEN** | `ReaderChrome` menu label is unconditional; bookmark state not passed in; tap always creates a new bookmark. |
| P1-10 Busy backup/restore rows look tappable | **DONE** | `FolioSettingsRow(enabled=…)` disables clicks/dims text; Settings shows “Exporting backup…” / “Restoring backup…” with the progress line and blocks repeat taps. |
| P1-11 Notes shows raw document ID | **OPEN** | `bookTitle = docs[a.docId] ?: a.docId`, and the row still opens the reader. |
| P1-12 Notes blames free space for any error | **DONE** | Notes load/mutation copy no longer mentions free space; corrupt files get a specific preserved-file message. |
| P1-13 Search result loses exact match boundary | **OPEN** | `ReaderSearch.Hit` is plain `snippet`; `snippet()` caps from the start (can cut the match); `sectionTitle()` falls back to file basename. |
| P1-14 Recovery banners hidden in search mode | **OPEN** | `MainActivity` renders SearchPanel in a separate branch; banners live only in the shelf branch. |
| P1-15 Delete collection leaves stale doc IDs | **DONE** | `deleteCollection()` now holds the collections+manifest locks, removes the collection and clears its id from every affected `meta.json`; returns `CollectionDeleteResult` for partial-cleanup reporting. Test: `LibraryManagerTest` (`deleteCollection clears doc metadata but keeps the books`). |

## Phase C/D/E — P2 quality

| ID | Status | Notes |
|---|---|---|
| P2-01 Reader back label says “Back to library” | **OPEN** | `ReaderChrome` hard-codes the description; Notes can open the reader. |
| P2-02 Search field has no accessible label | **OPEN** | `FolioSearchField` draws placeholder as sibling text; no semantics label/hint. |
| P2-03 Long-press multi-select undiscoverable | **OPEN** | No visible “Select” entry point; long-press only. |
| P2-04 Filtered Notes empty state is bare text | **OPEN** | `NotesActivity` filtered-empty branch is a `Text`, not `FolioEmptyState` + Clear filter. |
| P2-05 Dictionary “No match” guidance | **OPEN** | Copy says “try selecting a different word”; no base-form hint, no textual “Looking up…”. |
| P2-06 Backup scope unclear | **DONE** | Export row now says “Books, notes, progress and collections. Per-book display settings stay on this device.” |
| P2-07 Restore dialog / pickers at large text | **PARTIAL** | Restore actions now stack vertically with full-width labels (FIX-B). Reading-sheet theme row still tracked by P1-08; dynamic-type screenshots still DEVICE. |
| P2-08 Storage stays “…” on failure | **DONE** | States are “Calculating…”, “Unavailable” and numeric; failures show a Retry storage check row. |
| P2-09 ReaderChrome comments/params drift | **OPEN** | Comment mentions overflow highlight, `onHighlight`/`onPrefsChange`/per-book params unused. |
| P2-10 “Reader brightness” is an overlay | **OPEN** | Label says screen brightness; implementation is `BrightnessOverlay` scrim. |
| P2-11 Consequence-specific copy | **PARTIAL** | Collection delete says books stay; book delete now names files, highlights, notes, bookmarks and progress; delete warnings are surfaced. Incomplete-import removal copy still needs its scope sentence. |

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
