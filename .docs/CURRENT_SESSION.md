# Current Session Checkpoint

> This file is the recovery anchor. After every meaningful milestone, update:
> date, verified state, what changed, blockers, and the **exact next action**.

- **Last updated:** 2026-10-29 (session M-25 — S1 audit CLOSED: Phases 0–4 all landed)
- **Last verified project state:** HEAD = Phase 4 commit (on Phase 3, Phase 2
  `db3e9d8`, Phase 1 `404e595`, Phase 0 `bf09d9c`, user's `9058c8e`). Phase 4
  verified in THIS VM: **336/336 unit tests, lintDebug 0 errors (57 warns),
  assembleDebug green** (32.8 MB APK — 42.4 MB before Phase 4's pdfbox
  removal). Suite math: 334 − 6 (PdfImporterTest) + 8 (DisplayNameFallback 5
  + NotesHeader 3) = 336 ✔. Device journeys NOT verified (no device/KVM) —
  user-side. Latest user-test APK at repo root:
  `folio-mobile-debug-2026-10-29-p4-import-notes.apk`
  (sha256 `b5c7929068a8b8a32495cfc421f0ffbd5062a21b3ff167c933ceb335b4f72256`).
  Same rule as every build: uninstall the 2026-10-27 sync APK first (locked
  decision 1), never install over it. Phase 3 APK:
  `folio-mobile-debug-2026-10-29-p3-reader-recovery.apk`.
- **This session's Phases 3+4 changes** (of
  `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`): full detail in
  `.docs/results/M25-s1-phase3.md` and `-phase4.md`.
  - Phase 3 (reader): process recreation rebuilds instead of exits (restored
    dummy/dead fragments dropped; jump extras stripped so resume follows
    saved progress; stale NAV_TAG fragments removed); one-time dismissible
    chrome hint chip via NEW `AppHintsStore` (`app-hints.json`, device-local,
    backup-external, corrupt = "not shown yet"). +5 tests.
  - Phase 4 (library/notes): PDF dead code removed per user decision 2
    (`PdfImporter` + `pdfbox` dep/init deleted; schema-1 compat kept —
    `SourceFormat.Pdf`, `pdfZoom`, backup include-list `.pdf`, magic-byte
    detection → honest rejection); import names via
    `OpenableColumns.DISPLAY_NAME` w/ lastPathSegment fallback (pure
    `displayNameFrom`); Notes header mirrors load state (pure
    `notesHeaderSubtitle`) + back/up + check-icon on selected filter pills.

## Blockers

- No device/emulator: never claim device verification. The §4.6 reader
  regression matrix and all Phase-4 device checks are user-side.
- VM: default 4 GB Gradle daemon OOM-fired on this 7.8 GB 2-core box — run
  gates with `-Dorg.gradle.jvmargs="-Xmx2304m -XX:MaxMetaspaceSize=640m"
  -Dorg.gradle.parallel=false`. `/home` ~736 MB free; keep Gradle home on
  `/tmp/opencode/gradle-home`; re-provision `/tmp/opencode/android-sdk`
  (SDK 35 + build-tools 35.0.0) on any fresh VM.
- Exact CFI restore still gated on the Kotlin 2.1/Readium 3.1.2 upgrade
  decision (TASKS.md font-packaging row) — NOT an S1 item.

## Exact next action

1. S1 is closed. Next work = the user's pick from `.docs/TASKS.md`
   "Active / next": Play-side split (M6), CI workflow restore
   (`mobile-ci.yml`, P1), font-packaging decision (P2, own session),
   repo-hygiene items (needs explicit user authorization).
2. When the user installs a APK: report device journeys back; never mark
   device checks passed from this VM.
