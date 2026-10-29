# Current Session Checkpoint

> This file is the recovery anchor. After every meaningful milestone, update:
> date, verified state, what changed, blockers, and the **exact next action**.

- **Last updated:** 2026-10-29 (session M-25 — S1 Phases 0, 1, 2 AND 3 landed)
- **Last verified project state:** HEAD is the Phase 3 commit (built on
  `db3e9d8` = Phase 2, `404e595` = Phase 1, `bf09d9c` = Phase 0, user's
  `9058c8e`). Phase 3 verified in THIS VM after a full SDK re-provision:
  **334/334 unit tests, lintDebug 0 errors, assembleDebug green**
  (was 329/329 after Phase 2; +5 `AppHintsStoreTest`). Device journeys NOT
  verified (no device/KVM) — user-side. User-test APK at repo root:
  `folio-mobile-debug-2026-10-29-p3-reader-recovery.apk`
  (sha256 `d6cffe7451574cadf22370b7255723084548105b5c4894016f26f58e315bde04`).
  Same rule as every build: uninstall the 2026-10-27 sync APK first (locked
  decision 1), never install over it.
- **This session changed (Phase 3 of `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`
  — reader recovery & comfort):** see `.docs/results/M25-s1-phase3.md`.
  Key points:
  1. `ReaderActivity.onCreate` — process recreation (OS killed the reader)
     REBUILDS instead of exiting: restored dummy/dead navigator fragments
     dropped (dummyFactory round-trip unchanged), then the durable doc id +
     persisted progress locator drive the normal restore path (deleted /
     damaged docs keep the honest finish-with-message routes). Locked jump
     extras (`EXTRA_TARGET_ORDER/LOCATION`, `EXTRA_TEXT_*`) are stripped on
     recreation so resume comes from saved progress, not a replayed jump;
     stale `NAV_TAG` fragments removed before the live navigator lands.
  2. Chrome discoverability — one-time dismissible hint chip
     (`ReaderChromeHint`): "Tap the middle of the page to show the
     controls"; revealing the chrome retires it; dismissal persisted off
     the main thread.
  3. `core/store/AppHintsStore.kt` (NEW) — `app-hints.json`, device-local,
     outside `library/` + backup contract; corrupt = "not shown yet",
     replaced on next write (deliberately NOT PrefsStore-grade preservation).
  4. Tests: `AppHintsStoreTest` (+5: missing/round-trip/corrupt-reads-not-
     dismissed-and-recovers/sync-helper/json-shape).

## Blockers

- No device/emulator: never claim device verification. The §4.6 reader
  regression matrix (process-death resume, font scale, TalkBack, themes,
  taps) is user-side. User must **uninstall the 2026-10-27 sync-enabled
  APK** before installing any new build (locked decision 1).
- `assembleRelease` for a real release needs git-ignored
  `mobile/keystore.properties` (user-side decision 3).
- VM: `/home` only ~4.8 GB; keep Gradle caches off it
  (`GRADLE_USER_HOME=/tmp/opencode/gradle-home`); re-provision
  `/tmp/opencode/android-sdk` (SDK 35 + build-tools 35.0.0) on any fresh VM.

## Exact next action

1. **S1 Phase 4 — library/notes finish** (open the S1 audit §Phase 4):
   `OpenableColumns.DISPLAY_NAME` with lastPathSegment fallback; notes
   header from load state (Loading / "Saved items unavailable" / "Nothing
   saved yet" / "N items") + recovery route + back/up; loading/empty/
   corrupt/success parity across screens; non-color-affordant selected
   filter chips; **PDF decision implemented: remove dead PDFImporter/
   PDFBox (user decision 2)**; honest import limits (50 MB cap stays) +
   resilient batch-import states. Gates + push per phase.
2. S1 audit fully closed, then: Play-side split (M6), CI workflow restore
   (`mobile-ci.yml`), font-packaging decision (exact CFI restore),
   `auditscratch/` + repo-hygiene items (user's call).
