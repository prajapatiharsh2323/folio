# D1 — Dictionary (offline English→Hindi) — result summary

**Status:** IN PROGRESS (session D-1, 2026-10-25)
**Plan:** `.docs/plans/dictionary-plan.md` — read that first after memory erase.

## Progress checklist

- [x] Repo inspected; facts verified (Readium 3.0.0, custom selection bar,
      no DB in project, tokens identified).
- [x] Plan + tracking committed.
- [x] FreeDict eng-hin 1.6 source downloaded + licence verified.
- [x] Converter script + assets pack built.
- [x] Core dictionary layer (pure Kotlin + sqlite).
- [x] DictionarySheet UI + ReaderActivity wiring.
- [x] Unit tests green (session D-1b below; 297/297, 0 failures).
- [x] Report `FOLIO_DICTIONARY_IMPLEMENTATION_REPORT.md`.
- [x] Tracking updated (MOBILE-TRACKING.md status line + session log).
- [ ] Device manual round (no emulator/KVM here — user round).

## Outcomes / notes

(filled as work completes)

## Blocked / deferred

- English definitions: FreeDict eng-hin has none → Open English WordNet
  (CC BY 4.0) add-on deferred to a later session (user approved this gap).
- Online fallback: deferred per user decision (interface stub only).
- Device validation: no emulator/SDK in this sandbox → user manual round.
## Phase 2 — data pack (DONE 2026-10-25)

- Verified download `freedict-eng-hin-1.6.dictd.tar.xz`, sha512
  `6fa888f3f88db256b838ba31d78de9d6fc46c8979c5b32b91ccd0a7c89a16041811012470de143619ee88b2e7366932a0d7863e53089848ea3db8e130b4962b0` (matches publish).
- Source inspected: `freedict-eng-hin-1.6.src.tar.xz` → `eng-hin.tei` (12.5 MB,
  25,642 `<entry>`), COPYING = **GPL v2**, README notes data from **Anusaaraka
  English-Hindi Dictionary V2.0 (IIIT Hyderabad)**, quality "low", built by
  school children; maintainer Michael Bunk.
- Coverage measured: pos on 100% of entries (N 13906, Adj 5419, V 2539, VT 1426,
  Adv 1019, VI 259…), 2355 multiword headwords, 61 apostrophe, 836 hyphen,
  ~24.7k entries carry example sentences; **0 pronunciations**; no English
  definitions (as predicted).
- Dataset quirks handled key-side: "well-being" stored as "well being" →
  `headword_relaxed` secondary key (hyphen/space/apostrophe-agnostic);
  enclosing quotes/brackets stripped; `~` connector → space (notation of the
  dataset); "?"-only translations dropped.
- Converter: `mobile/tools/build_dict.py` (stdlib only). Output
  `mobile/app/src/main/assets/dictionary/eng-hin.db` —
  **5.0 MB, 25,450 rows** (192 skipped: unparseable/blank). ⚠️ The sha256
  printed at that session (`f94e84ce…`) was WRONG — it was hashed before the
  `meta` insert (impossible self-digest); repaired in session D-1b →
  file digest `9c477e11…` (see below).
- Licence text shipped as
  `mobile/app/src/main/assets/dictionary/eng-hin-COPYING.txt` (GPL v2 verbatim).
- Spot checks: run → 2 POS rows; well-being → relaxed hit; "give up" → honest
  no-match (phrase absent from dataset).

## Session D-1b — repair + completion (2026-10-25)

The D1 session's HEAD was never green and the dictionary was dead-on-device.
Root causes found by audit + fixes verified with real tests:

1. **Compile:** `DictionaryRuntime.kt` referenced `DictionaryPackSource` /
   `DictionaryPackLocator` without imports → whole project didn't compile at
   HEAD (`6d0a7f9`). Fixed.
2. **Fatal digest bug:** `build_dict.py` hashed the DB *before* writing
   `meta` rows, so `DictionaryRuntime.PACK_SHA256` (`f94e84ce…`) could never
   match the asset (`6c2b2739…`) — installer rejected the pack on-device →
   permanent "Unavailable". Repair: converter now records
   `meta.content_sha256` (sha over dict rows, recomputable) + writes the
   final FILE digest to a `.sha256` sidecar; a file cannot contain its own
   digest, so the runtime constant (= installer verification target) copies
   the sidecar. Pack upgraded via `build_dict.py --upgrade`
   (TEI not needed): stale `sha256` key removed, `content_sha256`
   `72d8769c…` verified reproducible, final asset digest
   **`9c477e11945ed8e183cdd042d7d8391bff1619954270fd53967ab88effc250b1`** →
   `PACK_SHA256` updated; `readMeta` reads `content_sha256`
   (legacy fallback); sidecar checked into assets; new sidecar-agreement test.
3. **Runtime parse bug found by tests:** `Json.decodeFromString<List<…>>`
   resolved the element serializer, not the list — rows silently degraded to
   zero senses. Fixed with an explicit `ListSerializer(DictSense.serializer())`.
   (A repo test fixture also fed a bare object vs the converter's array —
   fixture fixed.)
4. **Normalizer bugs found by their own tests:** trailing Devanagari nukta
   (`दौड़` → `दौड`) was stripped as "punctuation" — combining marks (Mn/Mc)
   now survive; `cleanDisplay` never stripped edge quote characters.
5. **Reading-theme compliance (D1 UX contract):** `ReaderActivity`'s
   `chrome.setContent` has no theme wrap, so the sheet unrolled to M3
   baseline (light, purple-toned) over warm/dark pages. The sheet is now
   wrapped in `FolioMobileTheme(darkTheme = ReaderPrefsMapper.
   resolvedThemeIsDark(prefs.theme, isSystemDark()))`; the same pure helper
   now drives `applySystemBarAppearance` (no drift; JVM-tested).
6. **Verification:** environment bootstrapped (Android SDK 35 + cmdline
   tools at `/tmp/opencode/android-sdk`, `local.properties` git-ignored).
   `./gradlew testDebugUnitTest` → **297 tests, 0 failures**;
   `./gradlew assembleDebug` → **BUILD SUCCESSFUL**.
   No emulator/KVM on this box → device round still pending.

Remaining honest gaps (unchanged, user-approved): no English definitions
(WordNet deferred), no pronunciations (dataset has none), no online fallback,
no lemma fallback ("running" honestly misses "run" — dataset has no
inflection data; pinned by test).
