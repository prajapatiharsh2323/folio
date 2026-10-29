# Current Session Checkpoint

> This file is the recovery anchor. After every meaningful milestone, update:
> date, verified state, what changed, blockers, and the **exact next action**.

- **Last updated:** 2026-10-29 (session M-25 — S1 Phases 0, 1 AND 2 landed)
- **Last verified project state:** HEAD is the Phase 2 commit (built on
  `404e595` = Phase 1, on `bf09d9c` = Phase 0, on user's `9058c8e`). Phases
  1–2 verified in THIS VM: after Phase 1 — **321/321 tests, lintDebug 0
  errors, assembleDebug green** (offline-first, all Google/Work deps gone,
  release sign-gate verified refusing). After Phase 2 — **329/329 tests,
  lintDebug 0 errors, assembleDebug green**. Device journeys NOT verified
  (no device/KVM) — user-side. User-test APKs at repo root:
  `folio-mobile-debug-2026-10-29-sync-free.apk` (Phase 1) and
  `folio-mobile-debug-2026-10-29-p2-settings.apk` (Phase 2,
  sha256 `3848ac6055654c8fd91ec4aa7aec58058732b8f82ef43558070d15234aaf55f2`).
  Same rule for both: uninstall the 2026-10-27 sync APK first (locked
  decision 1), never install over it.

- **This session changed (Phase 2 of `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`
  — Settings rebuild):** see `.docs/results/M25-s1-phase2.md` for the full
  list. Key points:
  1. `core/store/AppAppearanceStore.kt` (NEW) — System/Light/Dark app-UI
     theme in its own `app-appearance.json` (outside `library/` and the
     backup contract), PrefsStore-grade corrupt handling; `loadThemeSync()`
     avoids the first-frame theme flash; wired into MainActivity,
     NotesActivity, SettingsActivity (live re-theme on tap).
  2. `ui/components/ReadingPrefsEditor.kt` (NEW) — the full reading-prefs
     control set extracted from `ReadingAppearanceSheet.kt` (sheet → shared
     editor + header/reset); `ReaderPrefsMapper.themeColors()` made public
     for REAL reader-theme previews (no duplicated hex).
  3. `SettingsActivity.kt` — landing IA: Appearance · Reading defaults
     (editor + real preview + commit-only persistence via CONFLATED ordered
     writes + confirm-guarded reset) · Backup & restore (export now via
     CreateDocument("application/zip"), staged temp + flush + streamed copy;
     restore dialog names the include-list) · Storage & recovery (honest
     rows + "Recovery copies") · About; persistent corrupt banners with the
     recovery route; visible back/up in the TopAppBar.
  4. Tests: `AppAppearanceStoreTest` (+8: defaults, round-trips, invalid-key
     rejection, corrupt typed state, verified byte-identical preservation,
     preservation-failure aborts, isDark matrix).

## Blockers

- No device/emulator: never claim device verification. User must
  **uninstall the 2026-10-27 sync-enabled APK** before installing the new
  sync-free APK (locked decision 1 — never install over the old one).
- `assembleRelease` for a real release needs git-ignored
  `mobile/keystore.properties` (user-side decision 3).
- VM: `/home` only 4.8 GB; keep Gradle caches off it
  (`GRADLE_USER_HOME=/tmp/opencode/gradle-home`) and expect daemon deaths
  from disk-full/OOM on full `--rerun-tasks` executions (documented in
  PROJECT_CONTEXT + results file).

## Exact next action

1. **S1 Phase 3 — reader recovery & comfort** (open the S1 audit §Phase 3):
   reader rebuilds from durable doc id + persisted locator on
   `savedInstanceState != null` (no more finish-on-restore, doc deleted →
   clear recovery message); audit the pinned Readium 3.0.0 API for precise
   locator restore (exact CFI is behind the Kotlin 2.1 upgrade — check the
   font-packaging decision first); chrome discoverability (one-time
   dismissible hint, 48dp slider stays); §4.6 reader regression matrix is
   user-side. Gates + push per phase.
2. Then Phase 4 (library/notes finish + PDF dead-code removal, user
   decision 2).
3. Later: Play-side split (M6), CI workflow restore (`mobile-ci.yml`),
   `auditscratch/` + repo-hygiene items (user's call).
