# Folio Mobile — Android Rewrite Tracking (AI context file)

> **Read this file FIRST at the start of every session working on `mobile/`.**
> Sessions are short-lived (~30–40 min, AI memory erased). Commit + push to
> `origin/main` (prajapatiharsh2323/folio) after **every** meaningful change, and
> update this file before the session ends. Companion to `.docs/SESSION-TRACKING.md`
> (which covers the PWA side). Result summaries for major changes go in
> `.docs/results/` with `M*` prefixes.

## Status: M1 scaffold COMPLETE + M2 started (model port green)

## The locked plan (agreed with user, Oct 17 2026)

- **Route B chosen**: full native Kotlin/Compose rewrite (NOT Capacitor, NOT TWA).
- Search: SQLite/FTS5 via SQLDelight + pre-normalized shadow text (Devanagari-safe).
- Distribution: **Play Store** (AAB, signing, Play App Signing, target API current).
- PWA stays a **living companion** (Windows/web + clipper host): schema-1 JSON,
  backup ZIP format, annotation anchors, Drive `sync/state.json` protocol are
  byte-compatibility contracts. Any change to them must update fixture literals in
  BOTH test suites in the same commit.
- PDF stack: pdfium viewer (render) + PDFBox-Android (text extraction). Apache-2.0.
  No MuPDF (AGPL).
- **EPUB-first focus** (user priority): R1–R4 reader workstream, see below.
- v1 EPUB scope: R1 core + R2 comfort + R3 annotations (incl. shareable quote-card
  images) + R4 = TTS read-aloud + footnote popups + media overlays.
- Deferred post-v1: fixed-layout EPUB, custom user CSS, auto-scroll, repair-import,
  landmarks/page-list.

## Phase ladder

| Phase | Deliverable | Status |
|---|---|---|
| M1 | Scaffold: Gradle project, Compose shell, theme tokens, adaptive icon, wrapper, first successful `assembleDebug` | **DONE** (build green, APK 16.2 MB) |
| M0-verify | (pre-M2 gate) `.\gradlew assembleDebug` green in `mobile/` | **DONE** |
| M2 | Core data layer port: schema-1 serialization, FileStore (app-private, atomic), LibraryManager, conformance fixture suite ported from `packages/core/tests` | **storage layer DONE** (24/24 tests); import pipeline (M3) next |
| M3 | Import pipeline: EPUB (DRM detect), PDF, HTML+sanitizer, TXT; manifest commit-point + write-ahead journal; recovery scan; hash dedupe | **phase 1 DONE** — detect+EPUB+TXT+builder, 43/43 tests; HTML+PDF next |
| R1 | EPUB rendering: **Readium Kotlin Toolkit**; paginated/scrolled/dual-spread; 5 themes; full typography; Devanagari fonts; publisher-default honored; locator↔CFI compat tests | pending |
| R2 | Reading comfort: volume-key + BT page-turner, predictive back, tap-zone config, chapter-aware scrubber + time-left, brightness overlay, page-turn anims, paragraph-snap, per-book typography overrides, image zoom | pending |
| R3 | Annotations: cross-node anchoring, dictionary/translate (Hindi↔English), per-chapter highlight list, color edits, **quote-card share images** | pending |
| R4 | TTS read-aloud (+ MediaSession audio-focus), footnote popups, media overlays | pending |
| M4 | Search: SQLDelight FTS5 + normaliser port; manifest+index single transaction; Hindi fixture suite | pending |
| M5 | Notes/collections/backup/Drive: streaming ZIP backup, merge/replace (typed REPLACE), WorkManager auto-backup, AppAuth + Drive REST mirror | pending |
| M6 | Play hardening: signing, AAB, R8 rules, baseline profile, Data Safety, in-app updates | pending |
| M7 | CI: lint/detekt on PR, emulator instrumented job, AAB artifact, release-on-tag | pending |

## Key decisions & rationale (do not re-litigate without user)

1. **Native rewrite, not Capacitor** — user explicitly chose Route B over recommendation.
2. **Readium Kotlin Toolkit** for EPUB (replaces epub.js): native rendering, Locator model,
   no iframe/CSS-war, TTS + media-overlays support built in.
3. **SQLDelight + FTS5** (not Room — Room only supports FTS4).
4. Kotlin 2.0.21 + AGP 8.7.3 + Gradle 8.10.2 — battle-tested trio (installed Gradle is
   9.8.0; wrapper pins 8.10.2 so builds are reproducible anywhere).
5. minSdk 26 / targetSdk 35 / compileSdk 35 (android-35 platform installed locally).
6. Package `dev.folio.mobile`; icon = vector adaptive (charcoal bg #23242F + violet
   bookmark #715CFC brand mark), no bitmaps committed.
7. Material You dynamic color disabled for now — Folio palette is the identity.

## Environment facts (fresh sessions: re-verify, don't assume)

- Windows host; repo `D:\folio`; branch `main`; remote origin = prajapatiharsh2323/folio.
- JDK 17 (Temurin 17.0.20.1) on PATH; `ANDROID_HOME=C:\Android\android-sdk`
  (platforms 34–37.2, build-tools 34–37, emulator, NDK, licenses OK).
- `gradle` 9.8.0 on PATH (only needed to regenerate wrapper; builds use `.\gradlew`).
- Git identity NOT persistent on fresh VMs: run
  `git config user.name "Harsh Prajapati"; git config user.email "harshprajapaticoding@gmail.com"`.
- Build: `cd mobile; .\gradlew assembleDebug` (first run downloads distribution+deps).
- Read-only parts of this repo: `apps/clipper` (never touch); `apps/web`/`packages/core`
  are reference for contracts, not edit targets (unless explicitly asked).

## Contract reference (from PWA source — do not re-derive)

- Schema-1 types: `packages/core/src/types.ts` (DocMeta/Progress/Annotation/Collection/Manifest).
- Library layout: `library/manifest.json` commit point; docs/<slug>/ meta.json +
  original|clean|reading + cover; user/*.json; slug = `YYYY-MM-DD-title-words-xxxx`
  + 4 hex; write order: content → meta.json → manifest LAST.
- Merge rules (backup restore & Drive): never deletes; annotations newer-wins by id;
  progress newer-wins per doc; collections union docIds; replace = typed "REPLACE".
- Search normalise: NFC, strip U+200C/200D, NEVER strip Devanagari combining marks;
  tokenizer `[^\p{L}\p{M}\p{N}]+`; boosts title4/author3/tags3/text1; Hindi stems
  `-यों/-ियों→-ी`; fuzzy Latin >4 chars only. Golden fixtures: `packages/core/tests/fixtures/hindi/`.
- Themes (tokens.css): light bg #FAFAF7 / surface #FFF / text #1C1B19 / accent #2F6F5E;
  warm #F4ECD8/#FBF5E6/#3B3226/#8A5A2B; dark #121214/#1C1C20/#E6E3DC/#7FC8B2;
  true-black #000/#0B0B0E/#C8C4BC. Highlights: #FFE58A #B7E4B0 #A9D1F5 #F5B7CC.
- Reading prefs defaults: 18px/1.6/margin 20, serif, paginated, justify off.
- PWA reader quirks native must FIX (evidence: apps/web/src/reader/epub-engine.ts):
  truncated prefix/suffix (per-node only), ~300 lines of !important CSS overrides,
  iframe coordinate gymnastics, lazy locations generation (percent flicker),
  rendition rebuild on flow switch.

8. kotlinx.serialization gotcha (hit once, don't repeat): enum classes serialize by
   ENTRY NAME, not by constructor properties — always use `@SerialName("lowercase")`
   per entry. And `kotlinx-serialization-json` must be `implementation`, not
   `testImplementation`, if main-source models use it.

## Session log

- **Session M-4 (Oct 17, 2026)** — M3 phase 1: import pipeline core.
  - `FileDetector`: magic bytes (%PDF-, PK zip+`mimetype`), HTML markers, strict
    UTF-8 fatal decode (TextDecoder parity — plain zips → unknown, matches TS).
  - `EpubBuilder`: escapeXhtml, toXhtml (void-tag self-closing + bare-amp escape +
    named entities), clean/index.html template (deterministic, no volatile stamps),
    buildArticleEpub (mimetype STORED first, container.xml, style, nav, chapter_xhtml,
    content.opf, stored images).
  - `EpubImporter`: unzip, DRM detection (rights.xml; encryption.xml non-font algos;
    CipherData w/o algo), OPF locate (exact/decoded/case-insensitive), title/creator/
    language regex parse (prefix-tolerant), manifest id→href map, spine order,
    href resolution with dot-segments, per-chapter plain text + word count.
    `extractPlainText` ported with tag→space rule so words never concatenate (TS parity).
  - `TxtImporter`: BOM strip, blank-line paragraph split, escaping, Devanagari >30%
    letters → hi, deterministic template → stable contentHash, EPUB 3 reading edition.
  - `EpubFixtures` (test helper): synthetic EPUBs built in-test (2-chapter novel, DRM
    variants, article epub round-trip) — no copyrighted files committed.
  - 43/43 tests green (19 new). Commit fb6be02 pushed. Summary: `.docs/results/M3-import-phase1.md`.
  - Next session: M3 phase 2 — HTML importer (jsoup sanitizer allow-list port +
    Readability-independent single-file path) + PDF importer (PDFBox dependency,
    ≥50 chars/page searchable heuristic).

- **Session M-3 (Oct 17, 2026)** — M2 storage layer complete.
  - `FileStore` interface port (suspend fns; blocking IO → Dispatchers.IO by caller).
  - `AppFileStore`: app-private root (injectable File for tests), path-traversal
    guards ("..", absolute, drive letters), atomic writes = sibling temp + fsync +
    ATOMIC_MOVE, non-recursive dir remove rejected.
  - `Hashing.kt`: sha256Hex (vector-tested) + createDocSlug exact port (60-char cap,
    "doc" fallback for Hindi, 4 hex suffix).
  - `ImportResult.kt`: importer output contract (pure bytes→result, save separate).
  - `LibraryManager` port: getManifest (corrupt→reinit), findDuplicate, addDocument
    (exact §4 write order, manifest LAST), listDocuments, damagedDocuments (never
    silently dropped), updateDocMeta, deleteDocument cascade, recoverInterruptedImports,
    progress/annotations/collections ops (mutex-serialized).
  - 24/24 tests green. Commit b5d2a4a pushed. Summary: `.docs/results/M2-storage.md`.
  - Next session: M3 import pipeline (EPUB DRM detect → pdf → html+sanitizer → txt).

- **Session M-2 (Oct 17, 2026)** — M1 verified green + M2 model port.
  - `.\gradlew assembleDebug` GREEN: app-debug.apk 16.2 MB produced.
  - Added kotlinx.serialization plugin 1.7.3 (catalog) + schema-1 model port
    `dev.folio.mobile.core.model.Types.kt` (DocMeta/Manifest/Annotation/Progress/
    Collection/ReadingPrefs — field names identical to packages/core/src/types.ts).
  - Added `TypesContractTest` (5 tests): PWA JSON literals parse, enum strings match,
    compact-JSON tolerance, unknown-enum fails loudly.
  - Fixes en route: XML comment `--` ban (colors.xml), dropped compileOptions restored
    (JVM-target 1.8 vs 17 mismatch), serialization-json moved testImplementation→
    implementation, enum @SerialName rewrite.
  - Commits: 27b1155 (pushed). Session log M-1 below. Result: `.docs/results/M1-scaffold.md`.

- **Session M-1 (Oct 17, 2026)** — M1 scaffold.
  - Created `mobile/` Gradle project (separate from PWA code, user instruction):
    version catalog, settings/gradle.properties, app module, MainActivity (Compose
    placeholder), theme (Folio token palette), XML adaptive icon, wrapper 8.10.2.
  - Fix: `buildFeatures` must live inside `android{}` (config-phase failure; wrapper
    gen aborted the first time).
  - Commits: e88d3f7 (scaffold), b94b547 (fix + wrapper). Both pushed.
  - `.\gradlew assembleDebug` running at session time — check status on resume;
    if red, read output and fix before anything else.
  - Result summary: `.docs/results/M1-scaffold.md`.
  - Next: verify green build → M2 core data layer port (types + FileStore + fixtures).
