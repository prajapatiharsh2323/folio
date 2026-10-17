# M4 — Search (mobile)

Session M-10 (Oct 17, 2026). Commits `da75560` + `ecff505`. 137/137 tests green
(109 → 137). Design: **in-memory MiniSearch port, NOT SQLDelight/FTS5** (decision
reversal approved 2026-10-17 — see below).

## What ships

1. **`core/search/` — the engine** (all JVM-pure, no Android imports):
   - `MiniSearch.kt` — Kotlin port of MiniSearch 7.2.0's engine surface the PWA
     uses: add/discard/re-add, field boosts, prefix search, BM25 scoring
     (`k=1.2, b=0.7, d=0.5`), fuzzy weight 0.45 / prefix weight 0.375, quality
     multiplier (matched source terms × raw score), AND combination, stable
     score-descending output. Divergences (documented in the file): flat
     HashMaps instead of the radix tree (same results; scan-based prefix/fuzzy
     candidate lookup), stale postings pruned on encounter, plain Levenshtein.
   - `SearchNormalise.kt` — 1:1 port of `packages/core/src/search/normalise.ts`:
     NFC → lowercase → strip ZWJ/ZWNJ → collapse whitespace → Latin-only accent
     fold (NFD round-trip; **never touches Devanagari marks**), tokenizer
     `[^\p{L}\p{M}\p{N}]+`, Hindi stems (`-ियों/-यों → -ी`, `-ों/-ें → ∅`)
     indexed **alongside** the base token.
   - `SearchIndex.kt` — PWA `SearchIndex` contract: boosts title 4 / author 3 /
     tags 3 / text 1, prefix everywhere, Latin-only fuzzy 0.2 for terms > 4
     chars, grouped-by-doc results, 50-document cap, snippets from raw chapter
     text. `add` is add-or-replace; `addAll` dedupes (last wins).
   - `SearchSnippet.kt` — port of `buildSnippet`: ~80 chars either side of the
     first hit, `<mark>` wrapping, five-entity HTML escaping (XSS-safe), `…`
     prefix/suffix, no-hit head-160 fallback.
   - `SearchUi.kt` — pure snippet-HTML → display parts splitter (Compose layer
     maps parts to an `AnnotatedString`; no WebView/browser involvement).
   - `SearchService.kt` — facade: `build()` reads `LibraryManager.listDocuments`
     + per-doc `getSearchText` off the IO dispatcher, returns a `Session`;
     `search(query)` runs in memory. Rebuilt per search-screen open (screen-open
     scale: reading files only) and re-indexed when the library version bumps.
2. **UI** (`MainActivity.kt`): search icon in the library top bar →
   `SearchPanel` — auto-focused field, 250 ms debounce, back/× closes, empty /
   loading / no-results states, results grouped by document, chapter rows with
   highlighted snippets (`SearchUi.parts` → `AnnotatedString` spans).
3. **Reader jump** (`ReaderActivity.kt`): hit tap passes
   `EXTRA_TARGET_ORDER` (1-based spine order); the target chapter **beats**
   progress restore (chapter-locator jump; in-chapter CFI restore when
   available via `ProgressBridge.cfiSpineIndex` stays for normal opens).
4. **Storage** (`LibraryManager.kt`): `getSearchText(docId)` reads the
   PWA-identical `library/index/text/<id>.json` (written at import since M3 by
   every importer — EPUB/PDF/HTML/TXT). Path verified against
   `packages/core/src/library/library-manager.ts` (line 150).

## Decision: SQLDelight + FTS5 → in-memory MiniSearch port (user-approved)

- Android *platform* SQLite ships without FTS5 (only FTS3/FTS4), so SQLDelight
  FTS5 would require bundling a custom SQLite engine (native .so, size, CMake
  surface) — real complexity for a screen-open-scale rebuild.
- The PWA reference engine **is** MiniSearch in-memory (`search.json` is a
  serialization detail, drive-synced). Porting the engine keeps search
  *behavior* byte-comparable (same boosts, fuzzy/prefix rules, BM25 constants,
  snippet markup) rather than merely simiilar.
- `library/index/text/<id>.json` stays the only search artifact — schema-1 and
  the backup/Drive contract are untouched. `search.json` is NOT written by
  mobile (it is a derived, safe-to-delete artifact on the PWA side).

## Test coverage (28 new; 137 total green)

- `SearchNormaliseTest` (8) — ZWJ/ZWNJ, nukta NFC, Latin fold vs Devanagari,
  whitespace, all four stemHindi cases, tokenise base+stem, marks kept.
- `SearchIndexTest` (13) — every golden case of `packages/core/tests/search.test.ts`
  Section 7 ported (Hindi word hit, inflected prefix, ZWJ query, decomposed
  nukta query, mixed hi/en, accented Latin, 50-doc cap, fuzzy rules, remove,
  duplicate-ID parity, external rawTexts reload path) + **500-doc <100 ms
  benchmark** (warmup search then timed).
- `SearchSnippetTest` (4) — XSS escaping, multi-term merge, head-160
  no-hit, bracket ellipses.
- `SearchServiceTest` (4) — entriesFor mapping, 1-based chapterOrders,
  snippet-parts split, entity decode (`&#039;x&quot;` → `'x"` — the draft's
  expectation was wrong, fixed).
- `MiniSearch.kt` itself is covered through `SearchIndexTest`.

## Notes / gotchas hit

- The interrupted session left the whole search tree **untracked** (and one
  failing test). Committed as-is (da75560), then fixed + UI-wired (ecff505).
- `packages/core/tests/fixtures/` contains **EPUBs only** (english-novel,
  hindi-book, image-book) — there is no `fixtures/hindi/` directory; the
  tracking-doc pointer was wrong. PWA search tests use inline Hindi literals,
  and the mobile port mirrors that (they don't need the EPUBs).
- `decodeEntities` must replace `&amp;` **first**; the rest are order-safe
  because the encoder emits `&#039;`/`&quot;` (no bare quote entities that
  could collide).
- Test counts: read `app/build/test-results/testDebugUnitTest/*.xml`
  (`tests`/`failures` sums) — 137/0.

## Open for next

- Device smoke still blocked (no AVDs on this VM) — search panel is in the
  smoke checklist in `MOBILE-TRACKING.md`.
- Search is title/author/tags/chapter-text only (no notes/annotations search —
  PWA parity; not in the PWA's index either).
- L1 residue / font packaging / R4 unchanged.
