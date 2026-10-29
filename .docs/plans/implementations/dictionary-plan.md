# D1 — Folio Dictionary (English → Hindi, offline-first) Implementation Plan

> Written 2026-10-25 (session D-1). Restore-point for short-lived sessions:
> if memory is erased, read this + `.docs/MOBILE-TRACKING.md` + `.docs/results/D1-dictionary.md`
> and resume from the checklist. Commit + push to `origin main` after every step.

## Decisions locked with user (2026-10-25)

- **Dataset: FreeDict `eng-hin` v1.6 bundle.** GPL-licensed, quality labelled
  "low" upstream — do NOT oversell coverage in UI copy or reports. Licence text
  must ship with the pack; attribution `FreeDict eng-hin 1.6 · GPL` in the sheet.
- **No online fallback this build.** `OnlineDictionaryDataSource` interface may
  exist as a stub but is never invoked.
- **English definitions are a known gap** (FreeDict eng-hin carries no English
  gloses). Sheet shows Hindi meanings only; report states a WordNet (CC BY 4.0)
  add-on is a later session.

## Verified repo facts (inspect-first results, 2026-10-25)

- Real paths: `mobile/app/src/main/kotlin/...` (NOT `app/` at repo root).
- Readium **3.0.0** (`mobile/gradle/libs.versions.toml`). Selection via
  `EpubNavigatorFragment.currentSelection()` (suspend); locator `.text` has
  `before/highlight/after` (`core/annotation/SelectionBridge.kt`).
- Custom selection stack already exists: ActionMode bar items
  `SEL_ITEM_HIGHLIGHT=1`, `SEL_ITEM_COPY=2` in `reader/ReaderActivity.kt`
  (inside `EpubNavigatorFactory` config) → `SelectionToolbarSheet` ModalBottomSheet
  in `reader/AnnotationMenu.kt`. Meaning = new `SEL_ITEM_MEANING=3` + a
  `Meaning` action inside the sheet.
- **No Room / no DB anywhere.** Persistence is JSON files (`core/store/*`).
  Dictionary DB = Android framework `SQLiteDatabase` (no new deps), pack in
  `assets/dictionary/eng-hin.db`, first-use copy → temp file → checksum →
  atomic promote in `filesDir`.
- Theme/UI tokens to reuse: `FolioSheetHeader`, `FolioSheetBody`, `FolioIcons`,
  `FolioSpacing`, `FolioDimensions`, `MaterialTheme.colorScheme.surface`
  (opaque — same as annotation sheets, no bleed-through).
- INTERNET permission already present; CI pipeline `mobile-ci` live; NO local
  Android SDK in this sandbox — APK builds only on GitHub Actions. `gh` CLI not
  authenticated locally; push works via stored git credentials.
- 253/253 tests green baseline (do not regress).

## Phases / work items

1. [x] Inspect repo, confirm facts above.
2. [x] This plan + tracking + result stub committed and pushed.
3. [ ] Download FreeDict eng-hin 1.6 TEI into `/tmp/opencode`, verify licence
       from archive; note SHA-256 of source.
4. [ ] `mobile/tools/build_dict.py` (python3 stdlib only): TEI → SQLite
       `dict(headword, headword_norm, pos, hindi TEXT, pron?)` + `meta` table
       (schema_version, dataset, dataset_version, sha256, attribution, licence).
       Output to `mobile/app/src/main/assets/dictionary/`.
5. [ ] `core/dictionary/`: `DictionaryEntry`, `DictionaryNormalizer`
       (Locale.ROOT + NFKC, keep internal `'`/`-`), `OfflineDictionaryDataSource`,
       `DictionaryDataInstaller`, `DictionaryRepository` (local-only this build).
6. [ ] `reader/DictionarySheet.kt` + `DictionaryUiState`; wire SEL_ITEM_MEANING
       + sheet Meaning action in ReaderActivity/AnnotationMenu. No annotation
       writes; dismiss via existing clearSelection() pattern.
7. [ ] Unit tests under `mobile/app/src/test/.../core/dictionary/` (normalizer,
       lookup, installer contract, no-fabrication, local-hit isolation).
8. [ ] Push → mobile-ci verdict; `.docs/plans/implementations/FOLIO_DICTIONARY_IMPLEMENTATION_REPORT.md`;
       tracking + result summary updates.

## Schema (locked)

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE dict(
  id INTEGER PRIMARY KEY,
  headword TEXT NOT NULL,        -- original casing as in TEI
  headword_norm TEXT NOT NULL,   -- DictionaryNormalizer.normalized
  pos TEXT,                      -- gramGrp when present, else NULL
  pron TEXT,                     -- only when TEI supplies it (this dataset: usually NULL)
  hindi TEXT NOT NULL            -- newline-separated meanings, source order
);
CREATE INDEX dict_norm ON dict(headword_norm);
```

Lookup order: exact phrase (norm) → per-word lemma NOT used unless the data
itself provides inflections (TEI eng-hin does not — document as unsupported).
No substring search as primary matching. Multi-word selection: exact phrase
entry only, else explicit "select a smaller expression" smaller-result state.
