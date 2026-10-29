# Folio Desktop — Features Functional Spec

Per-feature, always-cite-the-mobile-source specs. UI shells/uniform shells are in
`03-ui-ux-spec.md`; this doc is the *behavioral* baseline.

---

## 1. Library

Ref: `MainActivity.kt`, `core/library/{LibraryManager,LibraryUi,CoverLoader,CoverFallback}.kt`

1. **Listing**: derive rows from manifest + progress; every row shows cover → title →
   author (or "Unknown") → kind (Book/Article) → percent (or "New"). Progress =
   `progress.json[docId].percent`; rows without progress show "New".
2. **Sorting** (menu, persisted in `user/window.json` desktop store):
   - Recently read (default): `progress.updatedAt` desc, nulls last,
   - Title: case-insensitive alpha,
   - Author: case-insensitive alpha, nulls last,
   - Progress: percent desc.
3. **Filter chips**: `[All]`, one chip per tag `#tag`, one chip per collection. Selected
   chip filters the list; counts always reflect the current selection.
4. **Search-in-library panel** (Search icon): full spec in §6 below (MiniSearch).
5. **Import**:
   - "+" button → native dialog with filter {.epub;.html;.txt} + 50 MB cap.
   - Drag-and-drop on the whole window → same pipeline, works on library and empty state.
   - Errors: file > 50 MB ("This file is too large"), unsupported ("PDF is not
     supported" — mobile parity though desktop MAY revisit later; for v1 refuse),
     DRM ("This book is protected and can't be imported").
6. **Duplicate handling**: contentHash exists → dialog with the 3 options Skip / Keep
   both / Replace; "Keep both" makes a new id, slug-suffixed copy, re-runs import as
   usual; "Replace" keeps position/notes of the *old* doc (delete + reimport — note:
   mobile deletes annotations? NO: mobile keeps annotations and progress of the old
   doc when you Replace the FILES, preserving user data — port `replaceDoc` precisely).
7. **Details sheet** (row ⋯ → Details): cover, title, author, kind, source URL
   (clickable), imported date, tags edit (chip input + suggest), collection checkboxes
   + "New collection…" inline, word count, content hash preview (truncated, copyable).
   Delete button (danger) with confirm; deleting also deletes progress/annotations
   unless the same contentHash is reimported later (docIds differ — that's expected).
8. **Multi-select**: enter via checkbox hover or Ctrl+click; actions: Add to collection
   (picker), Remove from collection (context-sensitive), Clear.
9. **Recovery**:
   - Damaged docs: scanning manifest vs. `docs/` — a folder without parsable meta.json
     and manifest entry → banner "N documents are damaged" → dialog lists them with
     Remove (delete folder intact).
   - Interrupted imports: a doc folder that has meta.json missing but files present →
     dialog allows "Finish registration" (re-derive meta from files) or "Delete
     leftover" (rm folder). Exactly mobile semantics.
10. **Empty state**: import CTA. Coverless docs use seeded tint + initials
    (`CoverFallback.kt` behavior).

## 2. Collections

Ref: `LibraryCollections.kt`, `ui/components/{CollectionSheets,CollectionRows}.kt`

1. Gallery view: card per collection = 3-book fan (covers or tint tiles) + name +
   count + "Updated <relative date>" (`updatedAt` or row-order fallback).
2. Empty collection card = dashed outline "New collection" tint card.
3. Detail: member rows (same FolioBookRow with a "Remove" action), Add books →
   searchable multi-select picker listing all docs not yet in the collection,
   Rename dialog (validate non-empty), Delete with confirm ("Remove collection only;
   books are NOT deleted").
4. Sidebar shortcut + toolbar button both enter Collections.

## 3. Notes screen

Ref: `NotesActivity.kt`, `core/annotation/AnnotationFilters.kt`

1. Data: all annotations join docs (title only if doc exists; if deleted, header
   reading "[missing document]" and rows are non-clickable but still editable/removable).
2. Filter pills: All / Highlights / Notes / Bookmarks. Sort newest-first by `updatedAt`.
3. Row (see 03 §5.6). Meta badges: "Has note" whenever `note != null && != ""`;
   "Bookmark" for kind bookmark rows.
4. Tap → open reader with locatorOverride (per 04 §8 step 1). Chapter resolution uses
   `Chapter.href` and the stored location string; second jump = text-quote anchor.
5. Edit note: dialog with multiline field and two buttons — Save (creates note where
   empty → on a highlight creates/updates `note`) / Remove note (for existing note
   rows). Deleting a highlight from its ⋯ also deletes its note (one annotation).

## 4. Settings

Ref: `SettingsActivity.kt`, `ui/sync/CloudSyncSection.kt`, sections in 03 §5.7

1. Reading: default ReadingPrefs theme picker. Subtext explains per-book overrides.
   Desktop-only extra: App theme (Light/Dark/System).
2. Backup: sections list + Export / Restore buttons. During export show progress
   (count of docs processed); during import show phase + the summary dialog at the
   end (see `05` §5).
3. Cloud sync: account row (email + avatar), "Sync now" button with inline spinner →
   success ✓ / failure tag; "Auto-sync" toggle; "Turn sync off" disconnect (revokes
   local token only). First sync shows a consent card explaining the `Folio Library`
   folder + deletions-never-propagate policy (mobile's consent text).
4. Storage: rows Library size / Folio app data / Drive occupied free space.
   Desktop adds "Open library folder" (explorer).
5. About: version + licenses block; the dictionary credits (FreeDict eng-hin, GPL-2.0)
   and third-party notices (fflate, Readium, React, Tauri…).
6. Keyboard shortcuts reference row (static list from `07-keyboard-and-input.md`).

## 5. Dictionary (P2 phase, D1 parity)

Ref: `core/dictionary/*`, `.docs/plans/implementations/dictionary-plan.md`,
`.docs/plans/implementations/FOLIO_DICTIONARY_IMPLEMENTATION_REPORT.md`

- Pack: FreeDict eng-hin SQLite (25,450 entries, ~5 MB) → **build once** with mobile's
  `mobile/tools/build_dict.py` (reuse verbatim — do not fork the tool).
- Install at first use: copy bundled pack into app dir and verify its sha256 checksum
  (mobile parity); report mismatch → refuse + error state.
- Lookup: normalize headword (lowercase; common inflection pass like mobile's
  DictionaryNormalizer: -s, -es, -ed, -ing, -er, -est stripping in the same ORDER).
- Result card: headword + POS tag + senses (with Hindi meanings + optional English
  example). Empty result → "No meaning found".
- Card is re-themed to the READING theme (canvas-dark/dark) and sits above the
  selection without stealing webview selection.
- Attribution MUST always render in Settings → About and on the card footer tiny text.

## 6. Library search (MiniSearch parity)

Ref: `core/search/{MiniSearch,SearchIndex,SearchNormalise,SearchService,SearchSnippet,SearchUi}.kt`

- **Engine**: faithful port of MiniSearch 7.2.0 fields+BM25 (k1=1.2, b=0.7),
  fuzzy 0.2 for latin terms > 4 chars, prefix=true, combine=AND. If the vibe-coding
  agent prefers the real `minisearch` npm package, ALLOW it (the port only exists
  because Android can't import npm); must use the same defaults.
- **Index** built on panel open (not persistent): fields title / author / tags (typed
  per-field) + chapter body from `index/text/<docId>.json` chunks; chunk per chapter
  (unit value: chapter.id with order).
- **Normalization** (`SearchNormalise.kt` exact order):
  1. Unicode NFC,
  2. lowercase,
  3. strip ZWJ/\u200D & ZWNJ/\u200C,
  4. Latin accent folding ONLY for Latin letters (combining-mark stripping never
     applied to Devanagari — Hindi combining marks preserved),
  5. split on non-letter boundaries.
- **Results grouping**: doc → rows per chapter hit; each row shows chapter title +
  snippet with the matched words bolded (mobile snippet builder: context positions,
  ellipses on excess, NEVER cut a Devanagari cluster mid-mark).
- Tap a hit → open reader with `chapterOverride` (spine order) → then anchored text
  jump (04 §8 cascade #1).
- Empty query → show recent docs (same as mobile showing hint rows).
- Debounce 250 ms; index build ≤ 250 ms for a 200-doc library on a mid-range laptop.

## 7. Backup & Cloud sync UX flows

Ref: `core/backup/*`, `core/sync/*` — contracts in `05` §5–§6. Behavior highlights:

- Backup progress state must be cancellable.
- Restore requires a modal confirm even in Replace mode (mobile-parity copy).
- Drive section behavior on errors (mobile `SyncFailure.kt` copy):
  - AUTH → "Sign-in expired. Tap to sign in again."
  - NETWORK → "Check your connection and try again."
  - CONFLICT → "Some files couldn't be reconciled." + list + "Keep mine / Use Drive"
    per file (rare; only after manual retry) — v1 can collapse into retry-only.
  - DISK → "Not enough space to complete sync."
- Background auto-sync timer (15 min) only while app open; skip if one is already
  running.
