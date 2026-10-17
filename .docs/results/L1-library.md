# L1 — Library UI + import phase (mobile)

Session M-9 (Oct 17, 2026). Commit `01f5dfb`. 109/109 tests green (100 → 109).

## What ships

1. **Real library screen** (MainActivity rewrite, no more harness `ListItem`s):
   - Cards: tinted spine placeholder (stable FNV-1a of the title → one of 8
     palette-adjacent tints), title, author-or-kind subtitle, reading-progress
     bar + % label for previously opened docs.
   - Three-dot menu per card: Open / Delete — delete asks
     "…Highlights and reading progress removed too" confirm, then
     `LibraryManager.deleteDocument` (schema-1 cascade rule 5, never touches
     other docs).
   - Ordering: in-progress first (A→Z), then untouched, then finished — pure
     `LibraryUi.sort` (bucket + alpha comparator, tested).
   - Empty state: import CTA + the retained debug "Import sample text" button
     (kept intact — it is the only working device-smoke entry until AVDs land).
2. **SAF import** — `ActivityResultContracts.OpenDocument` with mimes
   `epub+zip/pdf/octet-stream/html/plain`; bytes are read once and routed
   through `ImportDispatch` (content detection — lying extensions don't matter
   per spec §6). Duplicates by `contentHash` are skipped with an "Already in
   library — skipped" snackbar instead of prompting (prompting skip/replace/
   keep-both is deferred — same as the PWA import screen flow).
   `UnsupportedFormatException` gets a friendly snackbar.
3. **ACTION_VIEW "open with"** — manifest intent-filter (DEFAULT + OPENABLE,
   broad base mime types) → fresh MainActivity instance imports the shared file
   exactly once inside `LaunchedEffect(sharedFileUri)` (dedupe-protected by
   contentHash). No launchMode changes needed.
4. **Pure cores** (all JVM-tested): `LibraryUi` (6 tests incl. sort order), 
   `ImportDispatch` (4 tests: txt routing, content-vs-extension html, valid-PDF
   fixture routing through the real PdfImporter, unknown-bytes error).

## Notes / gotchas hit

- `0x811C9DC5` overflows Int → FNV hash loop had to be Long-typed, `.toInt()`
  at the boundary (compile errors caught it).
- Minimal hand-written PDF fixtures (`"%PDF-1.4\n…"`) route correctly but the
  importer can't parse them — route tests must use real fixtures
  (`PdfFixtures.titledPdf`).
- PowerShell `-m` multiline commits with escaped quotes get split into bogus
  pathspecs — use `git commit -F <file>` on this VM.

## Open for next

- Reader returns to library via Back (predictive back now enabled); deep
  polish (covers of real EPUBs, tags/collections UI, damaged-doc recovery
  banner, icon via folio-logo.png) is next-phase material.
- M4 search (SQLDelight FTS5) unchanged; R2b remainder unchanged.
