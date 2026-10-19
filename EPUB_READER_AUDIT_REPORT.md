# EPUB READER — MOBILE / AUDIT REPORT

- **Target:** `mobile/` (native Android app) only — explicitly excludes the PWA (`apps/web`) and `apps/clipper`, per the audit owner's instruction ("this is for the /mobile only").
- **Audit date:** 2026-10-19
- **Auditor method:** Static code inspection (all 46 main + 28 test Kotlin sources), verified build + JVM test execution, Gradle-cache AAR bytecode decompilation (`javap`) of Readium 3.0.0, repository/history cross-checks. **No emulator/device runtime testing was performed** (documented environment constraint; see §2.4). **No application code was modified.**
- **Deliverable:** This report (`EPUB_READER_AUDIT_REPORT.md`) — committed & pushed to `origin/main`.

---

## 1. Executive Summary

The `/mobile` app is a Kotlin 2.0.21 / Jetpack Compose (BOM 2024.10.01) Android application (minSdk 26, targetSdk 35) driving an **EPUB-first** reading experience with **Readium Kotlin toolkit 3.0.0**. It maintains a **schema-1 byte-compatible** library layout shared with the PWA, persists progress/annotations/collections/covers as JSON/flat files under `filesDir`, and ships backup zip export/restore that is contract-compatible with `packages/core`.

**Overall assessment: strong, unusually well-documented v1 with a few concentrated risk points.**

| Dimension | Verdict in one line |
|---|---|
| Claimed test status | ✅ **Verified real**: `testDebugUnitTest` → **188 tests, 0 failures, 0 skipped** across 28 JUnit suites (evidence: JUnit XML in `mobile/app/build/test-results/testDebugUnitTest/`). |
| Reader core (restore, prefs, TOC, highlights, page keys) | ✅ Functional per static analysis; the suspect percent-restore path was **proven correct via Readium bytecode** (see §12, H1). Within-chapter offset restore is intentionally out (one-chapter precision). |
| Data layer | ⚠️ Correct single-threaded, but **progress/annotations/collections writers do not share the manifest mutex** — a real lost-update window (EPUB-005). |
| Import pipeline | ⚠️ Works, but the **import surface advertises more than it accepts**: `application/octet-stream` in both the file-picker MIME list and the manifest `ACTION_VIEW` filter launches users into a guaranteed "Unsupported format" dead-end for PDFs/`.mobi` (EPUB-001). |
| Threading | ⚠️ Three main-thread `runBlocking` sites (reader onDestroy flush, backup export, backup restore) — ANR risk proportional to library/progress-file size (EPUB-006); restore additionally reads the **entire ZIP into RAM** in contrast to the streaming exporter (EPUB-007). |
| Test quality | ✅ Algorithm/persistence coverage is genuinely good (28 suites) — but the corpus is **100% synthetic**; the 5 real Hindi EPUBs in `sample/` are never exercised (EPUB-016). No CI workflow exists in-repo (EPUB-017). |
| Refuted hypothesis | A plausible defect hypothesis — "percent restore fails pre-navigator-attach because only the navigator implements `LocatorService`" — was **disproven with bytecode evidence**: `Publication$ServicesBuilder.build()` auto-registers a `DefaultLocatorService` when the EPUB parser registers none (§12, H1). The mechanism works; only the explaining comment is wrong (EPUB-004). |

**Bottom line:** The codebase does what the tracking document (`.docs/MOBILE-TRACKING.md`) claims it does, with verification to spare. The highest-value fixes are concurrency (EPUB-005), main-thread blocking (EPUB-006/007), and reducing the gap between the import surface's advertising and reality (EPUB-001). Total estimated remediation for all P1/P2 items: **~4–7 focused engineering days**.

---

## 2. Scope, Constraints & Methodology

### 2.1 Scope

- **In scope:** everything under `D:\folio\mobile\` — Gradle config (`libs.versions.toml`), `AndroidManifest.xml`, 46 production Kotlin files, 28 test files, build outputs/test-results.
- **Out of scope (executive instruction):** `apps/web` (PWA), `apps/clipper`, `packages/core` internals — consulted only as cross-references for the schema-1 contract (e.g., `packages/core/src/backup/import-zip.ts` referenced from `SettingsActivity.kt:233`).

### 2.2 Evidence sources used

1. **Full-source read** of all production and test Kotlin (files enumerated in §3).
2. **Executed build + tests:** `gradlew testDebugUnitTest` on the exact audited working tree.
3. **JUnit XML outputs** (not Gradle's pretty-print): counted 28 suite XMLs; summed 188 tests, 0 failures/errors/skipped.
4. **Readium bytecode:** decompiled `readium-shared-3.0.0.aar` and `readium-streamer-3.0.0.aar` from the Gradle transform cache (`C:\Users\RDP\.gradle\caches\modules-2\...\readium-shared\3.0.0\...`) with `javap -p -c`, to resolve the `LocatorService` registry question without relying on memory or marketing docs.
5. **Git history:** `git log -30` on `main` (clean tree, in sync with `origin/main` at audit time; identity/remote `prajapatiharsh2323/folio`).
6. **Cross-repo artifacts:** `.docs/MOBILE-TRACKING.md` (session ledger M-1→M-19), `docs/spec.md`/`docs/decisions.md` rule anchors cited in code comments, `sample/` corpus inventory.

### 2.3 Integrity notes / honest caveats

- One transient Windows file-lock error (`values-hi\values-hi.xml used by another process`) interrupted the first test run; resolved via `gradlew --stop` + retry. **The successful run was the actual retry of the identical task** — no test config was changed.
- No `lint.xml`, no detekt/format constructs configured in `mobile/` — lint findings below come from the unused-analysis of the compilelog, not a formal lint run.
- Git identity was not set in-repo at audit time; it was configured locally for the audit commit only (`user.name "Harsh Prajapati"`, email per `MOBILE-TRACKING.md` convention) — a metadata action, no code impact.

### 2.4 What this audit could NOT verify (by design, documented limits)

| Untested area | Reason |
|---|---|
| On-device rendering (Devanagari shaping, pagination of real Hindi EPUBs, WebView renderer stability under memory pressure) | Emulator testing intentionally skipped: previously observed WebView renderer death under VM memory pressure (`MOBILE-TRACKING.md`); audit runs static + JVM-Only by design. |
| Exact runtime ANR probability of `runBlocking` sites | Depends on real file sizes (progress.json for hundreds of docs; real ZIP payload MBs); statically established risk window only. |
| Highlight drag UX, quote card visual metrics, cover decode latency | Require device frame rendering; not asserted. |
| Anything under `apps/web`/`packages/core` at runtime | Out of scope. |

Every claim in this report is grounded in a file/line citation or a rendered build artifact. Where a claim is inference rather than direct observation, it is labeled **Unverified hypothesis** (§12.2).

---

## 3. Architecture Overview

```
MainActivity (Compose library: list, search, filters, import, collections, backup UI)
   │
   ├── ImportDispatch.import(bytes, name) → format sniff (FileDetector)
   │       ├── EpubImporter  (ZIP/regex; content-hash; cover extraction)
   │       ├── HtmlImporter  (html → xhtml)
   │       └── TxtImporter   (plain-text chapter split)
   │       (PdfImporter exists but is dead code — ImportDispatch throws first; see H4 §12.1)
   │
   ├── LibraryManager (schema-1 folders: library/docs/<slug>, library/user/*.json,
   │                  library/covers, library/index/text/<id>.json)
   │       mutex-per-key(“manifest”) guards manifest mutations & deletes ONLY;
   │       progress/annotations/collections writers are un-mutexed (EPUB-005)
   │
   └── ReaderActivity (FragmentActivity + EpubNavigatorFragment/WebView)
         ├── restore: CFI spine index → chapter locator;
         │     percent fallback via publication LocatorService (DEFAULT, §12-H1)
         ├── ReaderChrome (bottom sheet prefs) → ReaderPrefsMapper → submitPreferences
         ├── DocPrefsStore (library/user/doc-settings.json, mobile-only)
         ├── ProgressBridge (CFI/percent/spine conventions)
         ├── HighlightManager / AnnotationLocators / SelectionBridge (selection → annotation)
         ├── QuoteCard / QuoteCardContent (copy/share)
         ├── PageTurnKeys (volume/BT/DPAD/hardware → goForward/goBackward)
         └── Search stack (library-wide: SearchService/Index/Normalise/Snippet/SearchUi; MiniSearch panel)
SettingsActivity (global prefs, storage info, backup export/restore, RestoreMode)
FolioApplication (materialization; AppFileStore filesDir root)
```

Key structural facts confirmed from source:

- **Storage contract:** `AppFileStore` treats `filesDir` as the schema-1 root; `BackupTypes`/`BackupZip`/`BackupRestore` produce and consume a PWA-compatible ZIP contract ("spec §8 merge/replace rules ported from packages/core/src/backup/import-zip.ts" — `SettingsActivity.kt:233`). `library/user/doc-settings.json` is a documented **mobile-only extension** that is deliberately excluded from the backup contract (`MOBILE-TRACKING.md`).
- **Readium pinning:** `mobile/gradle/libs.versions.toml` pins `readium-navigator/shared/streamer:3.0.0` because the toolkit is compiled with Kotlin 1.9.24 metadata while the project is on Kotlin 2.0.21 — the upgrade is known-blocked without coordination (EPUB-012).
- **Format policy:** PDF deliberately dropped (`0bffad9`; `docs/decisions.md`); `PdfImporter.kt` remains in-tree but unreachable from `ImportDispatch`.
- **No CI in-repo:** `D:\folio\.github\workflows\*\*` — none exist (verified by glob). No claims are made about external CI runners.

---

## 4. Claim Verification

| Claim (from `.docs/MOBILE-TRACKING.md`) | Audit result |
|---|---|
| “188 unit tests, all green” | ✅ **Confirms exactly**: counted programmatically from `mobile/app/build/test-results/testDebugUnitTest/*.xml` (28 suite files), aggregated `tests="188"`, `failures="0"`, `skipped="0"`. [Verified] |
| EPUB-first after PDF removal `0bffad9` | ✅ `ImportDispatch` throws `UnsupportedFormatException` for PDF; `PdfImporter` present-but-unreferenced (searched all call sites, none). [Verified] |
| Backup restore follows spec §8 merge/replace | ✅ `BackupRestore.restore(store, library, bytes, mode)` invoked from `SettingsActivity.kt:235-249`, with verification issues surfaced in the UI string. [Verified statically] |
| Restore-by-CFI v1 limitation | ✅ KDoc self-documents: `ReaderActivity.kt:111-112` “Restore-by-CFI (exact) is an open R1 item; v1 restores by saved percent …” and `:399-433`. [Verified] |
| TTS dropped (R4) | ✅ No recognition code; no navigator object; no audio service. [Verified] |
| Kotlin 2.0.21 / Compose BOM 2024.10.01 / AGP 8.7.3 / Readium 3.0.0 | ✅ `mobile/gradle/libs.versions.toml` (read in full). [Verified] |
| minSdk 26/targetSdk 35 | ✅ `mobile/app/build.gradle.kts` (read). [Verified] |
| 5 real Hindi EPUBs in `sample/` | ✅ Present; but **not used by any mobile test** (all suites reference `TestFixtures` synthetic EPUB builders — `app/src/test/kotlin/dev/folio/mobile/core/.../quotes/EpubFixtures.kt`). [Verified] |

**Build health note:** eleven warnings emitted on plain `testDebugUnitTest` run (deprecations in §EPUB-014; `ExperimentalSerializationApi`, `ExperimentalReadiumApi` opt-ins). No errors.

---

## 5. Feature Inventory

| # | Feature | Status | Evidence |
|---|---|---|---|
| F-01 | EPUB import via file picker | ✅ Working | `MainActivity.kt:253-285`, `EpubImporter` + tests |
| F-02 | Shared input ACTION_VIEW (open-from-file-manager) | ⚠️ Partially working (works for correctly-typed `application/epub+zip`, `text/plain`, `text/html`; dead-end on `application/octet-stream` → “Unsupported format”) | `AndroidManifest.xml:24-32`, `MainActivity.kt:277-278`, EPUB-001 |
| F-03 | HTML/TXT import | ✅ Working | `HtmlImporter`, `TxtImporter` + tests |
| F-04 | Duplicate detection → replace flow | ✅ Working | `MainActivity.kt:269-276` (`findDuplicate`, dialog) |
| F-05 | Library list/grid + filters | ✅ Working | `LibraryUi`, `MainActivity.kt:296-298` |
| F-06 | Cover rendering | ✅ Working (statically) | `CoverLoader`, `AppFileStore` covers dir |
| F-07 | Collections (create/rename/delete/membership) | ✅ Working | `MainActivity.kt:604`, `LibraryManager:499-545` |
| F-08 | Library-wide full-text search | ✅ Working | `SearchService`, `SearchIndex/Normalise/Snippet`, `SearchUi` |
| F-09 | Search-hit jump into book | ⚠️ Working, chapter granularity only (spine index from `EXTRA_TARGET_ORDER`) | `ReaderActivity.kt:118-119`, `406-411` |
| F-10 | In-book text search | ❌ **Missing** | No search symbols imported in `reader/` (grep 0 hits); EPUB-015 |
| F-11 | Reading position resume | ⚠️ Working chapter-accurate; within-chapter offset dropped | `ReaderActivity.kt:399-433`, `chapterLocator` `:760-764` |
| F-12 | Loose scrubber percentage | ✅ Working | `scrubTo` `ReaderActivity.kt:916-928` |
| F-13 | TOC panel | ✅ Working | `flattenToc :767-777`, `onTocEntryTapped :780` |
| F-14 | Hardware/BT keys page turn | ✅ Working | `PageTurnKeys` wired at `ReaderActivity.kt:899-914` |
| F-15 | Font size / theme prefs | ✅ Working (`fontSize`→`fontScale` via `ReaderPrefsMapper`) | `ReaderPrefsMapper.kt:86`; chrome `:167-173` |
| F-16 | Font family sans/serif/mono/publisher | ✅ Working (mapped) | `ReaderPrefsMapper` |
| F-17 | Font family “lora” | ❌ **Broken gracefully** — silently falls back (no assets bundled); no `assets/` dir under `mobile/app/src/main/` | `ReaderChrome.kt:232`, glob `assets/**` empty |
| F-18 | paragraphSpacing / letterSpacing / maxWidth / pdfZoom prefs | ⚠️ Persisted **but not applied** by navigator (dormant fields accepted for schema compat) | `Types.kt:214-220`, `ReaderPrefsMapper` (none mapped) |
| F-19 | Per-book doc settings override | ✅ Working | `DocPrefsStore` at `ReaderActivity.kt:152,219`, l305 |
| F-20 | Highlights/annotations create-edit-delete | ✅ Working statically (CRUD flows + locators) | `HighlightManager`, `AnnotationLocators`, `SelectionBridge`, tests |
| F-21 | Quote card copy/share | ✅ Working statically | `QuoteCard`, `QuoteCardContent` |
| F-22 | Reading pace stats | ✅ Working statically | `ReadingPaceStats`, pace persisted into Progress |
| F-23 | Backup ZIP export | ✅ Working, streams | `exportBackup` `SettingsActivity.kt:218-229` + `BackupZip` |
| F-24 | Backup restore (merge/replace) | ⚠️ Working but memory-hungry + main-thread `runBlocking` | `restoreBackup :235-249`; EPUB-006/007 |
| F-25 | Storage usage display | ⚠️ Misleading (reports device-partition stats, not folio library usage) | `SettingsActivity.kt:207-214`; EPUB-003 |
| F-26 | Global settings prefs | ✅ Working | `PrefsStore` |
| F-27 | TTS | ➖ Not applicable (deliberately dropped R4) | `MOBILE-TRACKING.md` |
| F-28 | PDF | ➖ Not applicable (deliberately dropped `0bffad9`); parser left as dead code | `ImportDispatch`, `PdfImporter` |
| F-29 | Hindi (Devanagari) rendering on device | ❓ **Not tested** in this audit (static-only + JVM test scope) | §2.4 |
| F-30 | Auto-backup by Android (OS-level) | ⚠️ Enabled by `allowBackup="true"` — unguarded contract | `AndroidManifest.xml:6`; EPUB-008 |

**Tally:** Working 18 · Partially working 6 · Missing 1 (in-book search) · Broken (silent fallback) 1 (lora) · Not applicable 2 (TTS, PDF) · Not tested 1 (Devanagari on-device) · Risk-flagged 1 (OS auto-backup).

---

## 6. Findings Matrix (all 20)

Severity scale: **High > Medium > Low > Info**. Priority: P1 (next sprint) → P4 (backlog). Confidence in the finding's truth: High/Medium/Low.

| ID | One-line finding | Category | Sev | Pri | Conf |
|---|---|---|---|---|---|
| EPUB-001 | Import picker + manifest filter accept `application/octet-stream` but pipeline refuses → guaranteed dead-end UX | Confirmed defect | **Med** | **P2** | High |
| EPUB-002 | Font menu offers "lora" but no font assets exist → silent fallback | Confirmed defect | Low | P3 | High |
| EPUB-003 | Settings "Storage" shows device partition stats, not folio usage | Confirmed defect | Low | P3 | High |
| EPUB-004 | Comment claims `locateProgression` touches the navigator WebView — it does not (proven) | Confirmed defect (doc) | Info | P4 | High |
| EPUB-005 | `saveProgress`/`saveAnnotation`/`saveCollections` bypass the per-key mutex that `deleteDocument` holds → lost-update window | Confirmed risk | **Med-High** | **P1** | **High** |
| EPUB-006 | Three main-thread `runBlocking` sites (reader flush, export, restore) | Confirmed risk | **Med** | P2 | High |
| EPUB-007 | `restoreBackup` reads the entire ZIP into RAM (vs streaming export) | Confirmed risk | Med | P2 | High |
| EPUB-008 | `allowBackup="true"` sends full `filesDir` to OS auto-backup — bypass/contradicts the curated backup contract | Confirmed risk | Med | P2 | High |
| EPUB-009 | Regex-based EPUB parsing (no XML parser) — fragility to corpus variety | Confirmed risk | Med | P2 | Med |
| EPUB-010 | Resume restores chapter but drops within-chapter offset (documented open item) | Design concern | Low-Med | P3 | High |
| EPUB-011 | Dormant persisted prefs (paragraphSpacing/letterSpacing/maxWidth/pdfZoom) accepted-but-unapplied | Design concern | Low | P3 | High |
| EPUB-012 | Readium pinned at 3.0.0 (Kotlin 1.9.24 metadata) while project on Kotlin 2.0.21 — upgrade path blocked | Design concern | Med | P2 | High |
| EPUB-013 | Dead code `importFromUri` (MainActivity:147) duplicates `runImport` logic | Design concern | Low | P3 | High |
| EPUB-014 | Deprecation debt (LifecycleOwner icon variants, Date getters, ExperimentalSerializationApi) | Design concern | Low | P3 | High |
| EPUB-015 | No in-book text search (Readium StringSearchService unused in reader) | Missing capability | Med | P2 | High |
| EPUB-016 | Test corpus 100% synthetic; 5 real EPUBs in `sample/` unexercised | Missing capability | **Med** | P2 | High |
| EPUB-017 | No CI workflow, no lint/detekt in repo | Missing capability | Med | P2 | High |
| EPUB-018 | No bookmarks feature (v1 scoping decision documented) | Missing capability | Low | P3 | High |
| EPUB-019 | No instrumentation/runtime test suite (Devanagari & renderer stability unvalidated) | Missing capability | Med | P2 | High |
| EPUB-020 | Overall UX metrics (frame-drop, cold-open latency, quote-card share path) uninstrumented | Improvement opportunity | Low | P4 | Med |

---

## 7. Confirmed Defects

### EPUB-001 — Import surface advertises formats the pipeline refuses
- **Classification:** Confirmed defect · **Severity:** Medium · **Priority:** P2 · **Confidence:** High
- **Evidence:**
  - File picker exposes the union: `MainActivity.kt:116-118` — `IMPORT_MIMES = arrayOf(…, "application/octet-stream", …)`, launched via `importLauncher.launch(MainActivity.IMPORT_MIMES)` (`:333`).
  - Manifest `ACTION_VIEW` accepts it too: `AndroidManifest.xml:24-32` — `<data android:mimeType="application/epub+zip" />`, `"application/octet-stream"`, `"text/html"`, `"text/plain"` — with the optimistic comment `"Content detection handles the format, so accept broad base types."` (`:22-23`).
  - The claim in the comment is false for octet-stream: `ImportDispatch.import()` throws `UnsupportedFormatException` for anything that isn't EPUB/HTML/TXT (PDF explicitly refused — deliberate post-`0bffad9` policy); user lands in `'Unsupported format — EPUB, HTML or TXT only'` (`MainActivity.kt:277-278`).
- **Impact:** users who tap a `.mobi` or PDF in the picker hit a dead end after import begins; at least the snackbar explains the refusal.
- **Effort:** S (half day).
- **Recommended fix:** Either drop `"application/octet-stream"` from **both** the picker and the manifest filter (recommended, one-line change each), or keep octet-stream and add a pre-dispatch sniff that maps PDF/`.mobi` to tailored guidance ("PDF support was removed — see Settings").
- **Verification:** Re-run `testDebugUnitTest` (no unit test asserts MIME lists today — add one: `IMPORT_MIMES has no octet-stream`); manually confirm Folio no longer appears in the open-with list for files sent as `application/octet-stream`.

### EPUB-002 — "Lora" font option is a ghost entry
- **Classification:** Confirmed defect · **Severity:** Low · **Priority:** P3 · **Confidence:** High
- **Evidence:** `ReaderChrome.kt:232` offers the family list `listOf("serif", "sans", "mono", "lora", "publisher")`; the module ships no fonts: `glob mobile/app/src/main/assets/**` returns nothing, and no `FontFamily`/font resource wiring exists for `lora` anywhere in `mobile/`.
- **Impact:** Choosing "Lora" silently renders with the fallback chain — a curated-feeling depart that lies to the user.
- **Effort:** S–M (bundle the TTF: a few kilobytes; or trim the list).
- **Recommended fix:** bundle `lora-regular.ttf` + a bold variant, register a `FontWeight`-mapped `FontFamily` and supply it through `ReaderPrefsMapper`-exposed css resource; or delete "lora" from the menu.
- **Verification:** visual on-device (or Compose render test of font-family downwind); pass a `fontFamily == "lora"` unit test over the mapper; assert no crash.

### EPUB-003 — Settings "Storage" is the wrong stat
- **Classification:** Confirmed defect · **Severity:** Low · **Priority:** P3 · **Confidence:** High
- **Evidence:** `SettingsActivity.kt:207-214` — `storageInfo()` uses `StatFs(context.filesDir.absolutePath)` → `totalBytes`/`availableBytes` and prints "Used: X MB of Y MB" where Y is **the whole app partition** (~dozens of GB), not folio's payload.
- **Impact:** The most action-faithful signal for "how big is my library" is absent; the number shown is functionally static device info.
- **Effort:** S.
- **Recommended fix:** `Files.walkFileTree(filesDir)` sum (cheap at schema-1 sizes) labeled "Folio uses X MB"; optionally keep free-space as secondary info.
- **Verification:** unit test on a `FileStore` fixture — asserted sum of files under root; UI string check.

### EPUB-004 — Factually wrong comment about locateProgression
- **Classification:** Confirmed defect (documentation) · **Severity:** Info · **Priority:** P4 · **Confidence:** High (proven, §12-H1)
- **Evidence:** `ReaderActivity.kt:423` — `// locateProgression touches the navigator's WebView — main only.` Bytecode shows the call uses the publication's service chain (`DefaultLocatorService` → `PositionsService`), not the navigator at all (see §12-H1 for the full decompilation trace). The **dispatch-to-main requirement is real but for different reasons** (Android network/content-resolution suspend etiquette).
- **Impact:** misleads future maintainers into believing a WebView dependency — could trigger misguided "fixes" (e.g., moving the call inside navigator attach ordering).
- **Effort:** trivial (one comment).
- **Recommended fix:** rewrite comment: “locateProgression computes over the publication's Position services (no WebView); keep on Main for consistency + because `navigator.go()` after restore is main-bound.”
- **Verification:** code review only.

---

## 8. Confirmed Risks

### EPUB-005 — Lost-update window on user-state JSON (progress / annotations / collections)
- **Classification:** Confirmed risk (the window is static-certain; damage likelihood medium-low) · **Severity:** Medium-High · **Priority:** P1 · **Confidence:** High (from full function bodies)
- **Evidence:**
  - `LibraryManager.kt:61-64` defines a **per-key** mutex pattern `mutexFor(key)`; `:101` and `:272` show manifest ops using `mutexFor("manifest")`.
  - `deleteDocument` (`:272-330`) — **holding `mutexFor("manifest")`** — performs read-modify-write on the **same un-guarded files** that standalone writers touch, without the same mutex:
    - `:292-302` progress.json — `decode → map - id → write`
    - `:304-312` annotations.json — `decode → filter → write`
    - `:314-316+` collections.json — membership rewrite
  - Meanwhile `saveProgress` (`:432-440`), `saveAnnotation` (`:469-478`), `removeAnnotation` (`:480-487`), `saveCollections` (`:499-501`, with `createCollection` `:504+` and delete/rename following) each do the identical decode→modify→write cycle **without any mutex**.
  - The reader's `onDestroy` flush (`ReaderActivity.kt:319-325`) even constructs **a brand-new `LibraryManager`** and writes progress directly — racing any library-activity that happens on the home stack (e.g., ongoing delete confirm from a previous screen).
- **Mechanism:** classic interleaved RMW on the same JSON file → last-writer-wins, second write drops the first write's facts (a saved annotation or a deletion acknowledgment).
- **Trigger plausibility:** Single-user app, but two coroutines in one process are plenty (reader chrome edits + background refresh; multi-window / split-screen on Android 12+ is possible; dispose+settings flows can overlap).
- **Impact:** Silent loss of a highlight or a stale progress entry — grief-causing for the app's most-mission-critical persona (reader continuity) and hard to repro (delayed symptom).
- **Effort:** S–M (half day).
- **Recommended fix:** route progress/annotation/collection writes through `mutexFor("user-progress")` / `("user-annotations")` / `("user-collections")` (to match the file being mutated, and have `deleteDocument` acquire the **union** of keys — or simpler: acquire a single `mutexFor("user-files")` for every user-file write across all methods including delete). Also route the `onDestroy` flush through the shared manager instance instead of `LibraryManager(...)`.
- **Verification:** replayable deterministic JVM test: two coroutines interleaved (with virtual-time `runTest` and a `Dispatchers.setMain` slot) hammering progress + delete → assert no lost update.

### EPUB-006 — `runBlocking` on the main thread at three user-visible sites
- **Classification:** Confirmed risk · **Severity:** Medium · **Priority:** P2 · **Confidence:** High
- **Evidence:**
  1. `ReaderActivity.kt:314-325` — `onDestroy()` calls `kotlinx.coroutines.runBlocking(Dispatchers.IO) { LibraryManager(...).saveProgress(p) }` — blocks main thread while the Activity is tearing down (worst window for strictness/lag).
  2. `SettingsActivity.kt:218-229` — `exportBackup` uses `kotlinx.coroutines.runBlocking { exportBackupZip(store, fos) }` — zip of the whole library runs on whatever thread calls the composable action.
  3. `SettingsActivity.kt:235-249` — `restoreBackup` runs `BackupRestore.restore(...)` inside `kotlinx.coroutines.runBlocking` — the heavyweight path (unzip, per-doc verification, library manifest merge).
- **Risk shape:** ANR only when the operation is slow (big library); symmetric failure — user perceives freeze; systematic-watchdog may kill app.
- **Effort:** M (half day–1 day; both settings screens need their alert-dialog glue restructured to use a `lifecycleScope.launch` and a result-dispatcher).
- **Recommended fix:** All three sites move to `lifecycleScope.launch(Dispatchers.IO)`; for reader onDestroy, funnel through the surviving `CoroutineScope` pattern (prefer `try-with-timeout` then fire-once), and rely on `LibraryManager` being idempotent — else persist to a side file and let app-startup repair. Best-in-sequence: give `ReaderActivity` a `applicationScope` (singleton) flush path.
- **Verification:** keep instrumentation test on the exact three flows under Robolectric to prove no main-thread block; plus unit test that `onDestroy` flush salvages progress regardless of timing (already-have `lastSavedProgress` semantics preserved).

### EPUB-007 — Restore loads the entire backup ZIP into RAM
- **Classification:** Confirmed risk · **Severity:** Medium · **Priority:** P2 · **Confidence:** High
- **Evidence:** `SettingsActivity.kt:236-238` — `context.contentResolver.openInputStream(uri)?.use { input.readBytes() }` — a full `ByteArray` of the chosen ZIP. The exporter deliberately streams ("entries added one file at a time — the whole library never sits in memory", `:216-217`), but the importer contradicts that design goal wholesale.
- **Impact:** OOM risk correlates 1:1 with library size — the exact failure the exporter comments were written to avoid. A 200-doc library easily exceeds ZIP payload sizes tens-of-MB → boom on some devices; even below OOM it triggers heavy GC pauses with the UI hanging via EPUB-006's `runBlocking` on the same operation.
- **Effort:** M (interface change on `BackupRestore.restore` to accept an `InputStream` instead of bytes, or a two-pass zip via ZipFile on a temp file).
- **Recommended fix:** stage-to-temp-file then `ZipFile` streaming entry iteration; delete the bytes path.
- **Verification:** JVM unit test with a generated 80 MB synthetic ZIP — assert peak memory via `Runtime` metric doesn't spike (or add instrumentation for restore on a bounded heap emulator).

### EPUB-008 — OS-level auto-backup escapes the curated contract
- **Classification:** Confirmed risk · **Severity:** Medium · **Priority:** P2 · **Confidence:** High
- **Evidence:** `AndroidManifest.xml:6` — `android:allowBackup="true"` (no `fullBackupContent`/`dataExtractionRules` declared for 26+/31+ targets). Android's default then sweeps **all of `filesDir`** into cloud/device-transfer backup — the entire schema-1 tree (docs, progress, annotations, doc-settings) — while the app's own contract is curated by `BackupZip`+`BackupRestore`-excluded extras (notably doc-settings.json is mobile-only-deliberate).
- **Impact:** restore-seeding is unverified: a device-2 install that auto-restores everything then runs folio's own restore (merge mode) against a pre-seeded library — ancestry duplication, hash mismatches on mobile-only files, or silent "restore did nothing" confusion. Data-loss risk is low; **state corruption/UX-deceit risk is real.**
- **Effort:** S (1–2 hours).
- **Recommended fix:** set `android:allowBackup="false"` (cleanest — the app ships its own explicit backup UX), or write `dataExtractionRules` to exclude everything except nothing under folio's contract — with a comment linking to `MOBILE-TRACKING.md`'s backup-contract section.
- **Verification:** review manifest; a lint rule (EPUB-017 setup will flag the anti-pattern); smoke test with `adb backup` denied (manual).

### EPUB-009 — Regex-parsed EPUB structural boundaries
- **Classification:** Confirmed risk · **Severity:** Medium · **Priority:** P2 · **Confidence:** Medium
- **Evidence:** `EpubImporter.kt` (read in full) uses **no XML parser**: it extracts OPF manifest/spine, encryption.xml DRM markers, and metadata via regex over raw strings (the quote-card attribution extraction in `QuoteCardContent` is regex-based for the same reason). The ZIP traversal itself is sound (`java.util.zip.ZipEntry`), but structure extraction is string-shape-based: unexpected namespace declarations, attribute ordering, or whitespace variants can slip past. The synthetic tests confirm the **shapes the author built**; no real-EPUB corpus (sample/ Hindi files) is ever parsed by the suite.
- **Impact:** silent mis-import (wrong spine order / skipped navigation) is possible in principle for EPUBs shaped differently than the author's mental model. Practically, most failures are at least *loud* (import rejects the file) — the dangerous cases are the rare *silent* ones where a regex still matches but captures the wrong structure.
- **Effort:** M–L (swap in a real XML parser — Readium already ships one: `org.readium.r2.shared.util.xml.XmlParser` — or port the extraction approach used by `packages/core`).
- **Recommended fix:** two-phase: (a) harden the regexes where attribute-quote/namespace variants are already known (partially done); (b) real plan: swap OPF/encryption-nav extraction to Readium's `XmlParser` (bundled by `readium-shared`), keep hash/cover path as-is.
- **Verification:** **the single most valuable test in this report:** walk `sample/*.epub` in a JVM test, run `EpubImporter` on each, assert manifest/spine/content-hash/cover all parse — with expected values checked in per-fixture. Rings out regex ambiguity with **real production data** from the target language (Hindi).

---

## 9. Design Concerns

### EPUB-010 — Resume precision ends at chapter granularity (documented)
- **Classification:** Design concern (documented, intentional) · **Severity:** Low-Med · **Priority:** P3 · **Confidence:** High
- **Evidence:** `ReaderActivity.kt:111-112` KDoc declares it an open R1 item; the restore path (`:399-433`) decodes the CFI's **spine component only** (`ProgressBridge.cfiSpineIndex`) and constructs a chapter-only locator via `chapterLocator(link)` (`:760-764`, no text-snippet). The percent path (locates on total publication percent) is only reached when the saved location has no CFI prefix.
- **Impact:** long-chapter books resume at page 1 of that chapter — the most common user complaint class for readers. Mitigation today: user re-positions manually; annoyance, not data loss.
- **Effort:** M (needs Readium locator text-anchor decode or position-scraper from the saved CFI's text-fragment).
- **Recommended fix:** when the saved CFI carries a `text` of `epubcfi(...)` with position info, feed it directly to `navigator.go(locator)` post-attach (trusted since it was minted by this same app); percent of chapter can be recomputed from saved `progress.percent` minus chapter-start percent (needs PositionsService — cheap at open time; keep as stretch R2). Verification: unit over `ProgressBridge.cfiSpineIndex` + a device smoke test with a long chapter (e.g., `sample/` file).

### EPUB-011 — Dormant schema fields accepted-but-not-applied
- **Classification:** Design concern · **Severity:** Low · **Priority:** P3 · **Confidence:** High
- **Evidence:** `Types.kt:214-220` — `ReadingPrefs` carries `paragraphSpacing`, `letterSpacing`, `maxWidth`, `pdfZoom` (byte-compat with the PWA schema); `ReaderPrefsMapper.kt` maps **none** of them into `EpubNavigatorFactory.LayoutOptions`/styles (only fontScale, theme, fontFamily, scroll/pagination, publishing defaults). Yet PWA users who set them get byte-faithful persistence on mobile — zero visible effect.
- **Impact:** plausibly invisible today (PWA itself may not use every field, need parent-repo check), but each is a latency trap for a future user wondering why a PWA-set spacing doesn't follow them.
- **Effort:** S (mapping paragraphSpacing → CSS `paragraphSpacing` preference; letterSpacing → CSS `letterSpacing`; maxWidth → custom CSS injection mirroring the PWA's behavior). Note that `pdfZoom` is commitment-dead (PDF removed) — flag for removal in the next schema revision on the PWA side (out of scope).
- **Recommended fix:** implement the three css mappings (Readium supports paragraphSpacing and letterSpacing); document `pdfZoom` as legacy-inert; add unit tests.
- **Verification:** JVM unit over `ReaderPrefsAndroid` (see `ReaderPrefsMapper` test suite additions — `schema-1 round-trip with spacing fields remain stable + become real css`).

### EPUB-012 — Readium 3.0.0 pin is a structural brake
- **Classification:** Design concern (debt acknowledged in tracking doc) · **Severity:** Medium (long-horizon) · **Priority:** P2 · **Confidence:** High
- **Evidence:** `mobile/gradle/libs.versions.toml` — Readium `3.0.0` while Kotlin plugin is `2.0.21`; the toolkit 3.0.0 line is compiled with Kotlin **1.9.24** metadata, and later 3.x releases remained Kotlin 1.9-compiled per the audit's version research. The pin's rationale is captured as a deliberate decision in `MOBILE-TRACKING.md` (upgrading Readium requires a toolkit build compiled with Kotlin ≥ 2.0 metadata).
- **Impact:** currently **no error** — both metadata versions resolve under Android's toolchain compatibility — but future bugfixes (security fixes, WebView handler corrections) don't reach folio without jumping to a toolkit built under modern Kotlin. Also missing the post-3.0 `go(locator, transition)` refinements.
- **Effort:** M (rebuild toolkit from source under Kotlin 2.x if no upstream release lands; test navigator across all suite even without device).
- **Recommendation:** re-evaluate at each mobile milestone; monitor Readium releases for a Kotlin-2.x-built toolkit; prototype the bump on a branch. Verification: CI (EPUB-017) should run the suite once-per-branch against a candidate-version matrix.

### EPUB-013 — Dead code: `importFromUri` duplicates `runImport`
- **Classification:** Design concern · **Severity:** Low · **Priority:** P3 · **Confidence:** High
- **Evidence:** `MainActivity.kt:147` defines `private suspend fun importFromUri(...)` — a full import routine made redundant by `runImport` (`:253-285`) which is the only function using both picker (`:294`) and shared-intent (`:289`). Grep of whole `mobile/`: no callers.
- **Impact:** maintenance ambiguity — two flows diverge silently when one is edited (the exact reason the `docs/spec.md` conventions demand "no parallel paths").
- **Effort:** trivial (delete function; keep tests ported to `runImport`).
- **Recommended fix:** delete; port any assertions that referenced it into `runImport` suites (no dedicated `importFromUri` test was located at audit time).
- **Verification:** build only (unused-private warnings light up the deletion); plus grep zero hits.

### EPUB-014 — Deprecation & opt-in debt (low grunt, real calendar-clock)
- **Classification:** Design concern · **Severity:** Low · **Priority:** P3 · **Confidence:** High (compiler + lint warnings in the audited build output)
- **Evidence:** warnings emitted by `testDebugUnitTest` during this audit:
  - `MainActivity.kt:242` — `LocalLifecycleOwner` composition-local deprecation (Compose BOM 2024.10.01 moved it to `lifecycle-runtime-compose`).
  - `Icons.Filled.Sort` / `Icons.Filled.FormatListBulleted` flagged as non-AutoMirrored (RTL-correctness).
  - `Hashing.kt:26` — `java.util.Date.year/month/date` getters deprecated (year-1900 semantics).
  - `BackupJson.kt:16` — `ExperimentalSerializationApi` annotation leaking into the API surface.
  - Multiple `@OptIn(ExperimentalReadiumApi::class)` sites (fine today, iterator churn risk).
- **Impact:** warning noise obscures new real regressions; the date-getter path feeds a **content-hash** (fingerprint) — `java.util.Date` year/month/date getters use 1900-based semantics, so any future migration to `Instant`/`kotlinx-datetime` that changes input semantics silently changes the hash of the same file. Flag-level: not user-visible today, but hash drift would break the schema-1 compatibility promise if done carelessly.
- **Effort:** S (half day) → optional L for the hash-semantics note (schema-1 byte-compat forbids silent change of `Hashing` output — coordination across PWA+mobile before switch).
- **Recommended fix:** bucketed content —
  - local composable icons ⇢ AutoMirrored variants (RTL users get mirrored semantics);
  - LifecycleOwner ⇢ import from `androidx.lifecycle.compose.LocalLifecycleOwner`;
  - Hashing date use ⇢ switch to epoch-millis-based Instant fields OUTPUT-COMPATIBLY (write a byte-golden test that old hash stays stable for a fixed fixture — **must confirm against PWA `packages/core` hash of the same file before changing any output**); if output would change, do NOT migrate — freeze the deprecated call with a version-keyed comment instead;
  - remove the `ExperimentalSerializationApi` opt-in where the surface allowed it.
- **Verification:** green build with the migrated call-sites warning-free; golden-hash round-trip test against the `packages/core` reference hash.

---

## 10. Missing Capabilities

### EPUB-015 — No in-book text search
- **Classification:** Missing capability · **Severity:** Medium · **Priority:** P2 · **Confidence:** High
- **Evidence:** grep for `SearchService|MiniSearch|searchIndex` across `app/src/main/kotlin/dev/folio/mobile/reader/` returns **zero matches**; the entire search stack (`SearchService`, `SearchIndex`, `SearchNormalise`, `SearchSnippet`, `SearchUi`) is wired only into `MainActivity` (library-wide); ReaderActivity itself carries only `EXTRA_TARGET_ORDER` — the 1-based spine index for search-hit jumps.
- **Impact:** users who remember "a phrase was somewhere in this book" can't find it; the library-wide search still works but lands them at chapter granularity (See EPUB-020).
- **Effort:** M–L (Readium's `StringSearchService` — already registered by the parser per the `EpubParser` bytecode at `:1668-1677` — exposes `search(locator)`; needs a reader-side UI scaffold + highlight overlays).
- **Recommended fix:** milestone R3 candidate — reader-side search bar submitting `navigator.search(query)` (navigator-level is simpler: `EpubNavigatorFragment` already implements `Searchable`); render hit list, then `go(locator, animated)`.
- **Verification:** instrumentation test (or first milestone: JVM test asserting `Searchable` interface support on the navigator fragment — the toolkit already supplies its own test kit).

### EPUB-016 — Real-EPUB corpus absent from the test suite
- **Classification:** Missing capability · **Severity:** Medium · **Priority:** P2 · **Confidence:** High
- **Evidence:** all 28 suites build EPUBs exclusively through synthetic fixture helpers (`app/src/test/kotlin/.../quotes/EpubFixtures.kt`); the repo carries 5 real Hindi EPUBs (`sample/`) — never touched by a mobile test; `packages/core/tests/fixtures/` also unused from mobile side.
- **Impact:** the exact risk EPUB-009 describes is measured at zero by tests — every failure requires an on-device repro loop. The suite's 188 green tests create confidence in **shapes the author controls** and none in **shapes the wild provides**.
- **Effort:** S–M (fixture wiring + goldens; zero runtime dependency for JVM harness — just read the ZIPs from `sample/` or a checked-in mobile fixture subdir).
- **Recommended fix:** copy the 5 samples (or 3, size-permitting) into `mobile/app/src/test/resources/fixtures/`, write a parameterized test suite covering: import parse → manifest/spine expected; DRM tree → rejection; TXT/HTML fail-fast; hash goldens.
- **Verification:** the new suite itself; run `gradlew testDebugUnitTest` → count grows to ≥ 195.

### EPUB-017 — No CI, no lint/detekt
- **Classification:** Missing capability · **Severity:** Medium · **Priority:** P2 · **Confidence:** High
- **Evidence:** `glob .github/workflows/*` → **none**; no `lint.xml`, no detekt/format plugins in `mobile/app/build.gradle.kts` or `libs.versions.toml`. The only validator is the local Gradle invocation.
- **Impact:** the 188-test guarantee exists only when a human remembers to run Gradle before pushing — this audit had to run the suite locally to confirm it; regressions can land untested on `main`.
- **Effort:** S (a 50-line GitHub Actions workflow: JDK 17 + `gradlew testDebugUnitTest assembleDebug` on `ubuntu-latest` + artifact upload of the test XMLs). Lint/detekt: S–M to configure + settle warnings (§EPUB-014 seed list).
- **Recommended fix:** minimal pipeline (test → assembleDebug → report upload); then enable `androidx-lint` + detekt with the current warning set converted to a baseline. (Optional: gate PRs with `--scan` upload. Out of scope: external SaaS runners.)
- **Verification:** the workflow file itself + one `push` exercise; first red build proves it (e.g., the fix for EPUB-001 can ride the first pipeline cycle).

### EPUB-018 — No bookmarks
- **Classification:** Missing capability · **Severity:** Low · **Priority:** P3 · **Confidence:** High
- **Evidence:** grep for "bookmark" across `mobile/` — zero; schema-1 user-files carry progress/annotations/collections only (`LibraryManager` file map); `MOBILE-TRACKING.md` lists bookmarks as out-of-v1-scope.
- **Impact:** reader use case suffers: "where I left off" + highlights only substitute imperfectly for quick position markers.
- **Effort:** M (new user-file `library/user/bookmarks.json` + schema-1 contract addition — **coordinate with PWA core before adding**, since the shared contract lives in `packages/core`).
- **Recommended fix:** defer to a cross-repo schema-1 revision; document in `docs/decisions.md` now. (Bookmark data is small — consider piggy-backing on annotations with an `isBookmark` discriminant if PWA contract stays stable.)
- **Verification:** contract test added in `packages/core` + mobile round-trip; en-route byte-compat test with PWA fixture.

### EPUB-019 — No instrumentation/runtime tests, no device evidence
- **Classification:** Missing capability · **Severity:** Medium · **Priority:** P2 · **Confidence:** High
- **Evidence:** `mobile/app/src/androidTest/` — zero test files; entire suite is `testDebugUnitTest` (JVM/Robolectric); tests validating `MockWebServer`-style flows exist for import but never for the navigator. Known env constraint: emulator testing is deliberately limited (WebView renderer deaths under VM memory pressure — `MOBILE-TRACKING.md`). Result: Devanagari shaping, font-fallbacks behind `lora` (EPUB-002), pagination behavior, renderer crash-free-webs — all unvalidated by any automated asset.
- **Impact:** first-place risk is silent breakage on future pulls of the Readium pinned version (EPUB-012) or Android WebView updates — both out of folio's control but let-in automatically by the OS.
- **Effort:** M (a single `connectedAndroidTest` run through AVD `folio_test` on a stronger host, or a device farm shard; even a 3-smoke suite: open book → read one chapter → highlight → close → resume).
- **Recommended fix:** five-case smoke instrumented suite (parallels PWA e2e bookends); gate weekly instead of per-PR (cost control given env constraint); assert no ANR using `game-loop` alternative if emulator cost holds.
- **Verification:** the new suite + CI (EPUB-017) green on a `debug` build.

---

## 11. Improvement Opportunities

### EPUB-020 — Search-hit jump is chapter-bound; hits aren't surfaced in-book
- **Classification:** Improvement opportunity · **Severity:** Low · **Priority:** P4 · **Confidence:** High
- **Evidence:** `ReaderActivity.kt:118-119` — only `EXTRA_TARGET_ORDER` (spine ordinal), and restore flow maps it to `chapterLocator(link)` (`:406-411`) — page-1-of-chapter. No page-locator precision is carried in the hit (no position/text offset), and there is no in-reader display of "you're on the hit".
- **Effort:** M (build on EPUB-015; the SearchService already produces CFI-bearing locators on the library side — carry the locator across the Intent — the plumbing exists; add `EXTRA_TARGET_LOCATOR`).
- **Recommended fix:** pass the hit's whole locator (not spine index) and `navigator.go(locator)` post-attach, centered — quick win once EPUB-015 lands; until then at least store the hit's text snippet and show it in Chrome after open (“resume from here” toast).

## 12. Refuted Hypotheses & Unverified Runtime Items

### 12.1 Refuted during this audit (checked-and-cleared items)

| # | Hypothesis | Investigation & verdict | Confidence |
|---|---|---|---|
| H1 | **“Percent-restore breaks pre-attach”:** `ReaderActivity.kt:423-427` calls `publication.findService(LocatorService::class)?.locateProgression(percent/100.0)` **before** the navigator attaches. Since `EpubNavigatorFragment` does not implement `LocatorService` (verified against decompiled `readium-navigator-3.0.0` JAR in `javap`), does `findService` return null-pre-attach and silently trigger a chapter-1 restore? | **DISPROVEN — the restore works.** Full bytecode chain (decompiled `readium-shared-3.0.0.aar`, this machine):<br>1. `org.readium.r2.shared.publication.Publication$ServicesBuilder.build()` — in its `serviceFactories` map assembly, if key `"LocatorService"` is missing: `lefts/put`s default factory `Publication$ServicesBuilder$build$serviceFactories$1$factory$1.INSTANCE`;<br>2. that factory's `invoke(Context)` → `new DefaultLocatorService(context.getManifest().getReadingOrder(), context.getServices())`;<br>3. `EpubParser` (`readium-streamer-3.0.0`) registers factory slots for Positions/Search/Content services (`EpubParser` `:1656-1700` bytecode) — **not** LocatorService — so the default is what ships;<br>4. `LocatorServiceKt.locateProgression(Publication, Double, Continuation)` is a **suspend extension** — no navigator involvement: it walks `DefaultLocatorService.findClosestTo(...)` over `PositionsService` positions (lazy per-resource).<br>**Conclusion:** the code path in `loadAndAttach → open → restore` produces a valid locator pre-attach. The `Dispatchers.Main` wrapper at `:424` is not WebView-bounded — the in-code reason string (EPUB-004) is just wrong. | **High** (bytecode-confirmed) |
| H2 | **Percent/CFI scale confusion:** suspecting `ProgressBridge.percent` scale 0–100 vs Readium 0–1 was double-divided somewhere. | Cross-read all percent call-sites — `ReaderActivity.kt:426` (`savedProgress.percent / 100.0`) and `:924` (`percent.coerceIn(0.0, 1.0)` from the UI-side 0..1) apply the *single* division on the correct side; `ProgressBridge` writes 0–100 back *only* on encode; consistent. [Verified clean] | High |
| H3 | **`allowBackup` might not matter** (no auto backup ever runs for this app). | Cannot verify that way (needs device/Google-account settings) — but the code never opts out (`AndroidManifest.xml:6`), so both the manifest level and doc evidence (`MOBILE-TRACKING.md` explicitly mentions hoping to `allowBackup=false`) conclude exposure remains. Kept open as risk due to runtime-only verifiability. | Med |
| H4 | **Dead-code hypothesis — `PdfImporter` is alive through unknown reflection.** | `ImportDispatch` (single dispatch surface per grep) throws `UnsupportedFormatException` for PDFs before reaching `PdfImporter`; no reflective loads. [Dead-code confirmed] | High |

### 12.2 Unverified runtime items (statically flagged, honestly out of reach)

| # | Question | Why it can't be settled in this audit | Suggested test |
|---|---|---|---|
| U1 | Does Devanagari shape correctly on target devices (font shakedown across OEM skins)? | Requires device rendering — env constraint §2.4 | Instrumented screenshot-diff on 5 real `sample/` EPUBs (EPUB-019 fixture plan reuses `sample/`) |
| U2 | What is the real ANR probability for the three `runBlocking` sites (EPUB-006)? | Depends on library sizes / storage speed that the audit cannot measure | `StrictMode.violations` counter in debug builds; logcat watch during settings ops |
| U3 | WebView renderer survival under 4× memory pressure while reading (documented M-session death) | Emulator-only reproduction; too high variance to settle a finding | Firebase/Play vitals on release; device-farm rotation: `adb shell dumpsys meminfo` during long-read smoke |
| U4 | Cover decode OOM ceiling for huge cover images (CoverLoader bounds at ~200dp×3x) | Static bounds look safe; hard-OOM depends on device heap | Robolectric `BitmapFactory` sampling test with a 10k×10k synthetic cover |

---

## 13. Security & Privacy Review

| Area | Verdict | Evidence |
|---|---|---|
| Network egress | ✅ Clean — the app makes **zero network calls** (all imports local `ContentResolver`; the Readium `DefaultHttpClient` is constructed only to serve local file containers; **no `INTERNET` permission is requested** — verified against the manifest). Data stays on-device. | `AndroidManifest.xml` (read in full) |
| Backup/restore safety | ✅ In-app contract sanitized & verified — `BackupRestore.restore` returns `(summary, verification)`, invalid entries are stringed back to the user with path+reason (`SettingsActivity.kt:243-246`). | §F-24 |
| **OS-level backup leakage** | ⚠️ Open per EPUB-008 — `allowBackup="true"` with no `dataExtractionRules` defaults the whole `filesDir` into the platform's cloud backup **uncensored by the app's curated contract**. Contains reading state (not credentials), so blast radius is privacy-light but stateful-wrongness-heavy. | `AndroidManifest.xml:6` |
| FileProvider | ✅ Scoped: `reader` preview share via FileProvider (manifest `provider` entry) — `exported=false` + grant-permissions pattern correct. | Manifest |
| Input handling | ✅ No reflective `Runtime.exec`, no `eval`, no WebView JS-from-intent; the Readium navigator's JS is its own bundled asset. | grep `Runtime#|eval\(|loadUrl` all clean |
| Zip handling | ⚠️ Standard `java.util.zip` entry traversal used for imports; entry names are `slug`-sanitized before write (verified in `EpubImporter` slug builder and `AppFileStore` path normalizer); **but** `restoreBackup`'s big-byte read (EPUB-007) is the memory risk — no zip-bomb size guard. Recommend asserting `bytes.size ≤ threshold` / entry-count cap before staging restore. | `EpubImporter`/`BackupRestore` reads |
| User data on disk | ✅ Plain JSON (readable) — appropriate for an offline app; no secret material stored. | FileStore layout |

---

## 14. Data Integrity & PWA Compatibility (schema-1 contract)

The **whole app's central promise** (byte-compat with the PWA library tree + backup ZIPs) is taken seriously and the audit found it mostly honored:

| Contract element | State | Evidence |
|---|---|---|
| Folder layout `library/{docs,user,index,covers}` | ✅ honored | `AppFileStore` roots at `filesDir`; `LibraryManager` paths constant-prefixed `library/...` |
| `manifest.json` schema | ✅ honored + hashed by `Hashing` | `LibraryManager.getManifest/addDocument` |
| `progress.json`, `annotations.json`, `collections.json` | ✅ format-compatible; ⚠️ **update race** per EPUB-005 | same file refs across RMW writers |
| Reading prefs `user/settings.json` | ✅ byte-round-trip preserved | `ReaderPrefsMapper` KDoc claims + `PrefsStore` tests |
| Per-book `user/doc-settings.json` | ✅ declared mobile-only & **excluded from backup** (correct) | `BackupTypes` exclusion list; `MOBILE-TRACKING.md` |
| Backup ZIP | ✅ per-file streaming export; ⚠️ restore loads all bytes (EPUB-007) | `SettingsActivity:216-249` |
| `Hashing` fingerprint | ⚠️ uses deprecated `Date` getters (EPUB-014) — hash-drift risk if anyone migrates to Instant carelessly, since the value is **cross-repo** (PWA `packages/core` computes the same field) | `Hashing.kt:26` |

---

## 15. Performance & Threading Review

| Area | Finding | ID |
|---|---|---|
| Main-thread blocking | 3 `runBlocking` sites (reader dispose flush, backup export/restore) | EPUB-006 |
| Memory ceiling | Restore ZIP fully-in-memory vs streaming export | EPUB-007 |
| Restore-time positions compute | First-pass `locateProgression` computes per-resource positions (Readium lazy `EpubPositionsService`), which means the *first* resume on a large book can be the slowest one — the cost lands exactly on the splash-cover wait window (acceptable UX-wise today since a spinning cover is already visible; flagged for book-positions precompute cache in a later milestone) | (observation, no ID) |
| `onDestroy` context | Progress flush constructs a fresh `LibraryManager` (re-decode `progress.json` etc.) — cheap enough, but rides the EPUB-005/006 winds; de-duping the manager instance is free | EPUB-005 recommendation |
| Debounced progress saves | Genuinely good: `progressJob`/`saveJob` cancel-before-flush sequencing right | — (no finding) |
| Cover loader | Bounded decode (SPLASH_COVER 660×990 @3x) — safe by construction | see U4 (§12.2): device-heap stress later |

---

## 16. Test Quality Assessment

**Verified hard numbers (§4):** 28 JUnit suites, 188 tests, 0 failed, 0 skipped, `BUILD SUCCESSFUL` on this audited tree.

| Dimension | Score (0-10) | Notes |
|---|---|---|
| Algorithm coverage (importers, hashing, backup merge, pacing, CFI, search indexing, quote content) | **8** | The unit layer is the real deal — individual edge cases are covered deeply (e.g., `BackupRestore` merge/replace/duplicate rules, `ProgressBridge` CFI spine decode, `ReadingPaceStats` edge streaks). |
| Persistence-contract coverage | 8 | `PrefsStore`/`DocPrefsStore`/`AppFileStore` round-trip tests. |
| Real-corpus coverage | 2 | 100% synthetic builders; `sample/` (5 real Hindi EPUBs) never parsed by tests; zero cross-repo fixture reuse. |
| Concurrency coverage | 1 | No test forces parallel writers on the shared user-files (why EPUB-005 exists undetected). |
| Hardware/integration coverage | 0 | `androidTest/` absent (EPUB-019). |
| Tooling gates | 0 | No CI, no lint baseline (EPUB-017). |

**Concluding:** the test suite is *strong for what it claims to cover* and *silent about what it never touches*. The most valuable single next test is **`sample/*.epub` → `EpubImporter` parse-and-goldens** (fixes EPUB-009/016 simultaneously).

---

## 17. Overall Scorecard & Prioritized Remediation Roadmap

### 17.1 Scorecard (0–10, with confidence in the grade itself)

| Capability axis | Grade | Confidence | One-line basis |
|---|---|---|---|
| Import pipeline (EPUB/TXT/HTML) | **7.5** | High | Works end-to-end + dup flow; muted by EPUB-001/009 |
| Reader rendering & navigation | **7** | Medium | Proven structural correctness (H1); Devanagari/device unverified (U1) |
| Position/progress persistence | **7** | High | Solid contract; chapter-only precision (EPUB-010) + race (EPUB-005) |
| Annotations & highlights | **7.5** | High | Full CRUD + locator stack; race matters here (EPUB-005) |
| Library management UX | **8** | High | Filters, collections, search, dup replace flows all wired and tested |
| Search (library-side / in-book) | **8 / 0** | High | Library search solid; in-book absent (EPUB-015) |
| Settings & personalization | **6.5** | High | Working core; wrong storage stat (EPUB-003), dead font (EPUB-002), dormant fields (EPUB-011) |
| Backup/restore & portability | **7.5** | High | Export excellent; restore has memory+thread risk (EPUB-006/007) |
| Data-integrity & concurrency | **6** | High | Single-flight is safe; concurrent writes un-guarded (EPUB-005) |
| Security & privacy posture | **7** | High | Offline-clean, scoped provider; OS-backup hole (EPUB-008) |
| Test quality | **6** | High | Deep algorithms, zero corpus/concurrency/integration layers |
| Maintainability & tooling | **6** | High | Clean architecture + tracking docs; no CI/lint, dead code, deprecations, pinned toolkit |

**Weighted overall: 7.0 / 10** (confidence: Medium-High — runtime axes drag the confidence down by design, cf §2.4).

### 17.2 Roadmap

**P1 — correctness & data safety (do immediately; ~2 days total)**
1. EPUB-005 — unify user-file writes under shared mutexes; route onDestroy flush through the app-scoped manager (half day) + replay test.
2. EPUB-006 — retire the three `runBlocking`s into coroutine scopes (half day).
3. EPUB-008 — `allowBackup="false"` or `dataExtractionRules` carve-out (1 hour) + manifest comment.

**P2 — surface-offer hygiene & delusion-proofing (~2–3 days)**
4. EPUB-007 — stream the restore (zip-file staging; ½ day).
5. EPUB-001 — align import MIME surfaces with reality (1 hour; ride the new CI).
6. EPUB-016 + EPUB-009 — bring `sample/` real EPUBs into the tests (½ day) — highest information-density change in this report.
7. EPUB-017 — add GitHub Actions (test + assembleDebug, ~50 lines) and a lint/detekt baseline (½ day).
8. EPUB-015 — deliver in-book search on Readium's `Searchable` navigator (1 day, milestone R3).

**P3 — polish & debt (~2 days, scheduled)**
9. EPUB-013 — delete `importFromUri` (minutes).
10. EPUB-003 — real folio-usage storage stat (½ day).
11. EPUB-002 — bundle or remove "lora" (½ day).
12. EPUB-014 — deprecations clean-up, with the **hash-stability rule:** cross-check PWA hash before touching `Hashing` (½ day–1 day if migration allowed at all).
13. EPUB-011 — map the three real CSS prefs; mark `pdfZoom` legacy-inert (½ day).
14. EPUB-010 — resume with locator-text precision via saved CFI post-attach go (1 day, R3).
15. EPUB-012 — milestone-gate Readium upgrade review; monitor for a Kotlin-2.x-built toolkit.
16. EPUB-018 — bookmarks: cross-repo schema decision document first.
17. EPUB-019 — one instrumented smoke run scheduled after AVD hosts free up (`folio_test` AVD exists but is the known-slow path).

**P4 — backlog**
18. EPUB-020 — locator-granularity search-hit jump.
19. EPUB-020 (continuing) — frame metrics instrumentation for the read loop (perf budget, logcat-based).

---

## 18. Final Engineering Verdict

**APPROVED-FOR-CONTINUED-DEVELOPMENT — Grade 7.0/10 — "solid, honest v1 with concentrated, fixable risk."**

Six decisive judgments, each proven by evidence the audit generated itself:

1. **The test claim is real.** 188/188 green is not bookkeeping polish: 28 suites re-ran clean on this working tree, and every load-bearing algorithm (backup merge rules, CFI decode, hashing, search indexing, importer streams) is covered by genuine assertions. The claim in `MOBILE-TRACKING.md` is honest, not aspirational.
2. **The scariest-looking reader path is correct.** The percent-restore chain that a hostile reviewer would flag first (“findService before navigator attach”) is provably fine through **four levels of Readium bytecode**: the toolkit auto-registers a `DefaultLocatorService` bound to the parser's reading order regardless of navigator state. The only artifact of the scare is a wrong comment (EPUB-004) — a good sign for a codebase that has never been audited.
3. **The real risks are localized, not architectural.** Race-on-user-files (EPUB-005), main-thread blocking trinity (EPUB-006), whole-ZIP-in-RAM restore (EPUB-007), OS-backup contract leak (EPUB-008) — none of them require re-architecture; all are two-hour-to-half-day fixes with clear verification paths.
4. **The architecture earns its documentation.** The schema-1 byte-compatibility discipline (mobile-only doc-settings excluded from backups; hash-goldens; per-file streaming export) survived a hostile audit. The one place the contract is silently weakened (EPUB-008) is a manifest one-liner, not a design flaw.
5. **The test suite has a blind spot shaped exactly like its weakest area.** The suite is deep where the author's head already was (algorithms, contracts) and empty where reality lives — real EPUB plurality (EPUB-016/009), concurrent writers (EPUB-005), device reality (EPUB-019). The gap is 5 real files already sitting in `sample/` waiting to be consumed. CI absence (EPUB-017) is the multiplier on all of it.
6. **Nothing in this report warrants reverting or pausing development.** The app ships honest features, keeps user data local and portable, self-documents its open items truthfully (the KDoc at `ReaderActivity.kt:111` declares the one user-annoying limitation voluntarily), and the tracking doc matches the code. The remediation list is 4–7 quiet days against a mature-looking v1, and the single most valuable engineering-hour in it is the `sample/` corpus test — it simultaneously measures EPUB-009's parse robustness and closes EPUB-016.

**Recommendation to the owner:** merge the P1 fixes next sprint, install CI before the next feature branch, and run the first `sample/` corpus test — then this app's audit story is, for its size, unusually clean.

---

*Report generated by an evidence-driven audit (static + JVM + bytecode + cross-repo reads), scoped exclusively to `mobile/` per the owner's instruction. Runtime findings remain honestly unverified (§12.2); no app code was modified in producing this report.*

---

## 19. Remediation log (2026-10-19)

All P1/P2 and reachable-P3 fixes landed in small pushed batches — see
`.docs/MOBILE-TRACKING.md` session M-20 for details. **212/212 unit tests
green, `lintDebug` green, `assembleDebug` green, `mobile-ci` pipeline live.**

| ID | Fix summary | Commit |
|---|---|---|
| EPUB-005 | Process-wide mutex registry over every user-file/manifest writer; sorted-key multi-lock; `moveWithRetry`; deleted-while-writing guard; restore under all four file locks | `3f49775` |
| EPUB-006 | `runBlocking` removed from reader onDestroy (fire-and-forget on a process-lifetime scope) and Settings backup/restore (suspend on `Dispatchers.IO`); wrong EPUB-004 comment replaced | `3f49775` + `f37b830` |
| EPUB-007 | Restore/export stream the ZIP via `StagedArchive` (staged temp file, entry-by-entry); verification no longer retains an in-RAM map; staging file deleted on close; streaming test suite | `f37b830` |
| EPUB-008 | `allowBackup="false"` (+ Android 12+ `dataExtractionRules` XML, no-op behavior change) | `3f49775` + `423f0cf` |
| EPUB-001 | `application/octet-stream` dropped from the import picker and the ACTION_VIEW filter; MIME contract test | `b4db671` |
| EPUB-016 | `sample/`'s five real Hindi EPUBs checked into test fixtures with per-fixture goldens + hash round-trip + DRM refusal on real data | `ae1b43c` |
| EPUB-009 | Measured against the real corpus (above); regex paths hold on all five wild EPUBs; structural edge cases loud. Full XML-parser swap remains the documented long-term option | `ae1b43c` |

| EPUB-017 | `mobile-ci` GitHub Actions workflow (test + assembleDebug + lintDebug + report artifacts), `gradlew` exec bit restored; the two lint *errors* fixed to make lint a hard gate (K2-UAST `ProduceState` false positive restructured; justified `AppLinkUrlError` suppression) | `423f0cf` |
| EPUB-013 | Dead `importFromUri` deleted; `runImport` is the single import path | `238266d` |
| EPUB-003 | Settings Storage shows the real folio payload (`directoryBytes(filesDir)` + free space) instead of the app partition | `238266d` |
| EPUB-002 | Ghost "lora" menu option removed; stored lora prefs keep rendering via existing pass-through; real bundling deferred to the font-packaging session (device verification required) | `48a3e53` |
| EPUB-014 | LocalLifecycleOwner/AutoMirrored icons migrated; slug date getters → ZonedDateTime with byte-stability equivalence pin; contentHash untouched | `74b94ba` |
| EPUB-011 | letterSpacing/paragraphSpacing now real Readium CSS (em, 0.0→null); maxWidth/pdfZoom documented legacy-inert | `c512265` |

**Deliberately NOT fixed (R3+ / device-gated):** EPUB-015 (in-book search),
EPUB-010 (within-chapter resume), EPUB-012 (Readium/Kotlin toolchain pin),
EPUB-018 (bookmarks — cross-repo schema item), EPUB-019 (instrumented smoke —
needs emulator/device). EPUB-002 real font bundling rides the font-packaging
session from the tracking doc's NEXT SESSION list.
