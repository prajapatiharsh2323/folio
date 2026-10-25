# Folio Dictionary — Implementation Report

**Date:** 2026-10-25 · **Branch:** `main` · App module: `mobile/app`

Customary honesty marker up front: this report records only what was actually
verified. Every number below (row counts, digests, test totals, exit codes)
comes from running real commands against the real repo; the sections at the
end list what remains genuinely unverified (device behaviour).

---

## 1. Source files inspected & architecture decisions

Files inspected in detail:

- `mobile/app/src/main/kotlin/dev/folio/mobile/reader/ReaderActivity.kt`
  (selection capture via Readium `selectionActionModeCallback`,
  `SEL_ITEM_MEANING`, sheet composition + lookup job)
- `mobile/app/src/main/kotlin/dev/folio/mobile/reader/AnnotationMenu.kt`
  (`SelectionToolbarSheet` — chrome-triggered selection surface, `Meaning`
  button beside `Copy`)
- `mobile/app/src/main/kotlin/dev/folio/mobile/reader/{HighlightManager,ReaderChrome,ReadingAppearanceSheet}.kt`
  (decoration activation, chrome colour rules, appearance keys)
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/annotation/SelectionBridge.kt`,
  `core/model/Types.kt` (selection → `Annotation`/Locator contracts)
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/dictionary/*` (all five files)
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/reader/ReaderPrefsMapper.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/ui/theme/{Theme,Color,FolioTokens,Type}.kt`,
  `ui/components/FolioSheetHeader.kt`
- `mobile/tools/build_dict.py`, `gradle/libs.versions.toml`, `app/build.gradle.kts`,
  `AndroidManifest.xml`
- All dictionary unit tests under `app/src/test/kotlin/dev/folio/mobile/core/dictionary/`

**What already existed (session D-1, commit `6d0a7f9`) and was kept:**
FreeDict eng-hin 1.6 data choice with GPL licence hygiene; the offline pipeline
(models → normalizer → repository → installer → SQLite source); the reader
wiring (`Meaning` in both selection entry points, immutable query captured
before the sheet opens, lookup as a replaceable job, no annotation writes);
the `DictionarySheet` UI (opaque M3 bottom sheet, Hindi-first, honest states).

**Confirmed facts preserved by this approach:** Readium is `3.0.0`
(`gradle/libs.versions.toml`); selection is the toolkit's own long-press +
Folio's `selectionActionModeCallback`; both entry points route through
`openDictionaryFor()`; `currentSelection()` reads the toolkit's selection —
no second selection engine, no WebView JS. No DB abstraction existed in the
project, so the plain framework `SQLiteDatabase` (no Room) stays.

**Architecture decisions:**
- Dictionary core stays in `core/dictionary/` — pure JVM Kotlin, no Android
  imports except the SQLite source (deliberately untested on JVM, documented).
- ReaderActivity only passes the captured raw selection; the repository owns
  normalization/lookup/typed outcomes. The dictionary never knows which EPUB
  is open.
- Lookup order: exact normalized key → relaxed key (hyphen/space/apostrophe-
  agnostic, needed because the dataset stores "well-being" as "well being").
  Nolemma fallback: the dataset has no inflection data, so a lookup of
  "running" honestly misses instead of guessing "run" (pinned by a test).

## 2. Dataset, version, size, coverage, licence, attribution

**Chosen source:** FreeDict `eng-hin` release **1.6** — data originally from
the Anusaaraka English-Hindi Dictionary V2.0 (IIIT Hyderabad), maintained by
Michael Bunk. Licence: **GPL-2.0** (dictionary-specific; text shipped verbatim
in `mobile/app/src/main/assets/dictionary/eng-hin-COPYING.txt`, "Version 2,
June 1991" asserted by test).

**Recorded facts (from session D-1, re-verified this session):**
- Shipped pack: `mobile/app/src/main/assets/dictionary/eng-hin.db` —
  **5.0 MB, 25,450 rows** (25,642 source entries; 192 skipped as
  unparseable/blank).
- Coverage measured on the source: POS on 100% of entries (N 13906, Adj 5419,
  V 2539, VT 1426, Adv 1019, VI 259…); 2,355 multiword headwords; 61
  apostrophe forms; 836 hyphen forms; ~24.7k entries carry example sentences
  (source-provided); **0 pronunciations**; **no English definitions**.
- The published dataset is explicitly labelled **low quality** upstream
  (built by school children); Folio treats it as a vocabulary aid, not an
  authority — the UI's no-match copy states the ~25k-word scope.
- Attribution recorded in the pack's `meta` table:
  `FreeDict eng-hin; data: Anusaaraka English-Hindi Dictionary V2.0 (IIIT
  Hyderabad); maintainer: Michael Bunk`, `licence: GPL-2.0`,
  `dataset_version: 1.6`.
- Open English WordNet (CC BY 4.0) stays **deferred** (user-approved gap) —
  it would add English definitions, not Hindi, and is a later session.

**Digest contract (repaired this session — see §4):**
`meta.content_sha256` = `72d8769c08a6802d8090a5577363a99b496a3f98dad0218dcec2de43a8025208`
(sha256 over the dict rows in id order, re-verified reproducible).
Asset file digest (installer verification target, also shipped as
`eng-hin.db.sha256` next to the pack):
`9c477e11945ed8e183cdd042d7d8391bff1619954270fd53967ab88effc250b1`.
The runtime constant `DictionaryRuntime.PACK_SHA256` holds exactly this value
and `DictionaryPackIntegrityTest` asserts asset-bytes == constant, sidecar ==
constant.

## 3. User flow & UI details implemented

Flow (unchanged from session D-1, re-verified at code level):

1. Long-press a word → Readium native selection + Folio's own action mode
   bar (`Meaning`, `Copy`, `Highlight`) or the chrome brush →
   `SelectionToolbarSheet` (Highlight colour/Note/Copy/`Meaning`/Highlight).
2. `Meaning` → selection read via `navigator.currentSelection()`, immutable
   query captured, selection cleared, sheet opens. No annotation is written.
3. Bundle-first lookup on `Dispatchers.IO`; works offline; no sign-in; no
   network permission added (manifest unchanged: only the app's existing
   INTERNET / ACCESS_NETWORK_STATE / GET_ACCOUNTS permissions for sync).
4. Dismiss (close button / outside tap / sheet swipe) → back to the identical
   reading position (no locator interaction, no scroll, no persistence).
5. Rapid taps replace the single in-flight job — no stacked sheets, no
   duplicate requests. Retry always re-runs the **captured** query.

Sheet (`DictionarySheet.kt`): opaque `MaterialTheme` surface over a scrim, no
book bleed-through; header shows the looked-up term with a single close
affordance; Hindi meanings first (`H 18sp medium`), senses numbered when >1;
POS group labels mapped from dataset codes; English definitions are **not
shown** (the dataset has none — no fabricated sections), example sentences
shown only when the source supplied one; discreet source line
`FreeDict eng-hin · v1.6 · GPL-2.0`; explicit honest states for Loading /
Ready / NoMatch / Unavailable-with-Retry / Invalid. Content scrolls under a
persistently hittable header.

**New this session:** the sheet is now wrapped in
`FolioMobileTheme(darkTheme = ReaderPrefsMapper.resolvedThemeIsDark(prefs.theme,
isSystemDark()))` so it follows the active reading appearance — dark /
true-black render on the app dark palette, warm keeps warm paper, `system`
resolves the actual OS setting. `light`/`warm` stay light. The same pure helper
now drives `applySystemBarAppearance` (single source of truth, JVM-tested).

## 4. Files created / changed & why

Changed:
- `mobile/app/src/main/kotlin/dev/folio/mobile/reader/DictionaryRuntime.kt`
  — **fix:** missing imports (`DictionaryPackSource`, `DictionaryPackLocator`)
  that left HEAD non-compiling; **fix:** `PACK_SHA256` updated to the true
  shipped-asset digest (old value was the impossible self-digest, rejected
  every on-device install); contract comments rewritten.
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/dictionary/SqliteOfflineDictionaryDataSource.kt`
  — `readMeta` now reads `content_sha256` (legacy `sha256` fallback).
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/dictionary/DictionaryModels.kt`
  — **fix:** `Json.decodeFromString<List<DictSense>>` bound the element
  serializer, so every row silently degraded to zero senses (found by the
  repository test; re-proved with a scratch JVM test that rethrew). Replaced
  with an explicit `ListSerializer(DictSense.serializer())`.
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/dictionary/DictionaryNormalizer.kt`
  — **fixes found by its own tests:** trailing combining marks (Devanagari
  nukta in `दौड़`) were stripped as "punctuation" → marks (Mn/Mc) now survive;
  `cleanDisplay` never stripped edge quote characters → fixed.
- `mobile/app/src/main/kotlin/dev/folio/mobile/reader/ReaderActivity.kt`
  — dictionary sheet now composed inside the resolved `FolioMobileTheme`;
  `applySystemBarAppearance` refactored onto `ReaderPrefsMapper.resolvedThemeIsDark`.
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/reader/ReaderPrefsMapper.kt`
  — added pure `resolvedThemeIsDark`.
- `mobile/app/src/main/assets/dictionary/eng-hin.db` — meta repaired via
  `build_dict.py --upgrade` (stale circular `sha256` key removed,
  `content_sha256` added; row data byte-validated equal, 25,450 rows).
- `mobile/tools/build_dict.py` — finalization reworked: rows digest (non-
  circular) + final file digest printed/written to a `.sha256` sidecar; new
  `--upgrade` mode (repairs meta of an existing pack without the TEI, which
  is deliberately not kept in-repo); usage docs updated.
- `mobile/app/src/main/assets/dictionary/eng-hin.db.sha256` — **new** sidecar
  (digest of the shipped asset bytes).
- `mobile/app/src/test/.../DictionaryPackIntegrityTest.kt` — added a sidecar ==
  `PACK_SHA256` pin; renamed the pre-existing licence test (its name
  contained `.`/illegal chars and never compiled, as nothing compiled).
- `mobile/app/src/test/.../DictionaryRepositoryTest.kt` — **fixture fix:** a
  senses JSON was a bare object while the converter emits an array — the
  test had (wrongly) only ever passed by never running.
- `mobile/app/src/test/.../ReaderPrefsMapperTest.kt` — added the
  `resolvedThemeIsDark` contract test.
- `.docs/results/D1-dictionary.md`, `.docs/MOBILE-TRACKING.md` — status truthing.

Not changed on purpose: sync/backup/import, annotation persistence,
`SelectionBridge`, Readium interaction, `HighlightManager`, manifest.

## 5. Tests added & actual results

Added/changed tests:
- `DictionaryPackIntegrityTest.sidecar digest agrees with the runtime constant`
- `ReaderPrefsMapperTest.resolvedThemeIsDark matches the app-chrome dark palette contract`
- (fixture fixes enabling previously-dead assertions to actually run)

All 16 dictionary-side cases plus the reader mapper pin are part of the
repository suite.

**Actual result:** `./gradlew testDebugUnitTest` → exit 0 ·
**297 tests, 0 failures, 0 skipped** (breaks: annotation, backup, **dictionary
— 16 green**, importer, library, model, reader, search, store, sync, util).
`./gradlew assembleDebug` → **BUILD SUCCESSFUL** (exit 0).

At the start of this session the same suite state was: compile error in main
sources + 3 dictionary test failures + 1 illegal test name — all resolved.

## 6. Build / lint commands executed (actual)

| Command | Result |
|---|---|
| `./gradlew testDebugUnitTest` | **BUILD SUCCESSFUL** · 297 tests, 0 failures |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL in 8m 24s** |
| `python3 tools/build_dict.py --upgrade app/src/main/assets/dictionary/eng-hin.db` | meta repaired; `content_sha256 72d8769c…` recomputed and matched; file digest `9c477e11…` |
| `sha256sum app/src/main/assets/dictionary/eng-hin.db` | matches `PACK_SHA256` **and** sidecar (checked) |
| `./gradlew lintDebug` | not run — deferred (see §7); compile+unit+assemble are the green gates for this module's CI |

Environment note: this machine had no Android SDK; one was bootstrapped
(platform 35, build-tools 35, cmdline tools) at `/tmp/opencode/android-sdk`,
with `mobile/local.properties` (git-ignored) pointing at it. The CI-relevant
outcome (unit + assemble green) is reproduced on this box.

## 7. Manual device scenarios — performed or blocked

**Blocked: no emulator and no KVM is available on this box** (`/dev/kvm`
absent), so NO runtime/device validation was performed — and none is claimed.
Everything above is source inspection + JVM/Gradle verification. The manual
round from the plan's §7 (paginated + scrolled, themes, airplane mode,
inflected/apostrophe/hyphen/phrase selections, highlight-note regression)
still requires a device or hardware-accelerated emulator.

Hints from the code path analysis for whoever runs it: the digest fix makes
first-use install actually promote the pack (previously impossible); the
tokenizer fix means `दौड़`-style and `don't`-style displays render intact.
Lint (`lintDebug`) was deliberately not run and remains a suggested gate.

## 8. Coverage limitations & unresolved issues

- **English definitions:** FreeDict eng-hin has none; WordNet (CC BY 4.0)
  integration deferred by user decision. The sheet shows only Hindi senses +
  source examples — never invented text.
- **Pronunciations/IPA:** the source has zero; nothing rendered.
- **Coverage is ~25k headwords, and the dataset is published upstream as
  low quality**; common
  contractions (`don't`) and some phrases (`give up`) genuinely miss — the
  no-match state explains the scope rather than pretending.
- **Lemma/inflection fallback:** intentionally absent (dataset has no
  inflection data). "running" honestly misses "run"; pinned by test.
- **No online fallback shipped** (user decision; interface seam exists).
- Sheet-theming for the reader's other sheets (TOC/search/appearance) still
  unroll on M3 baseline; fixing all of them is a wider chrome-theme pass,
  out of the dictionary's scope (the dictionary sheet itself fully complies).
- GPL-2.0 redistribution is satisfied by shipping the verbatim COPYING text
  + attribution in the pack metadata; legal review is recommended once for
  distribution-grade signoff.

## 9. Online provider usage

**None.** No online provider was wired into this build (user decision from
session D-1; plan permits deferral when no suitable legal/reliable provider
is ready). The `OfflineDictionaryDataSource` seam accepts a second source in
future without UI/reader changes. No secret keys, URLs, or network calls
exist in the dictionary code path.
