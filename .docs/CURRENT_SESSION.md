# Current Session Checkpoint

> This file is the recovery anchor. After every meaningful milestone, update:
> date, verified state, what changed, blockers, and the **exact next action**.

- **Last updated:** 2026-10-29 (session M-25 — S1 Phases 0 AND 1 landed)
- **Last verified project state:** HEAD is `9058c8e` + Phase 0 commit
  (`bf09d9c`) + this session's Phase 1 commit (user's `9058c8e` deleted
  `desktop-docs/` — deliberate, not touched). Phase 1 verified in THIS VM on
  a fully re-provisioned SDK (fresh:
  platform-35 + build-tools 35.0.0 + platform-tools):
  **321/321 unit tests, lintDebug 0 errors (60 pre-existing warnings),
  assembleDebug green** (41.9 MB APK). Suite math: 350 − 29 deleted sync
  test cases = 321 ✔. Release fail-safe re-verified after build-file edits:
  `assembleRelease` refuses via `folio-release-sign-gate` without
  credentials. Device journeys NOT verified (no device/KVM) — user-side;
  user-test APK at repo root:
  `folio-mobile-debug-2026-10-29-sync-free.apk`
  (sha256 `47068661…` — see `.docs/results/M25-s1-phase1.md`).

- **This session changed (Phase 1 of `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`
  — one-step Drive sync removal, locked decision 1):**
  1. Deleted the whole `core/sync/` tree (`CloudSyncWorker`, `DriveAuth`,
     `DriveSyncProvider`, `SyncManager`, `SyncProtocol`, `SyncFailure`),
     `ui/sync/CloudSyncSection.kt` + its Settings section, and sync tests
     (`SyncManagerTest`, `SyncFailureMapperTest`, `FakeSyncProvider`).
  2. `libs.versions.toml` + `app/build.gradle.kts`: all Google
     api-client/drive/http-gson/play-services + `work-runtime-ktx`
     dependencies and catalog aliases removed; Google META-INF excludes
     dropped (clean assemble proved nothing needs them).
  3. `AndroidManifest.xml`: `INTERNET` removed only after the usage sweep
     (`java.net` use is just URLDecoder in EPUB import — local), plus
     `ACCESS_NETWORK_STATE` + `GET_ACCOUNTS`. App is now fully offline by
     manifest.
  4. Copy/comments de-synced: settings storage row ("…and sync state." →
     "Books, indexes, reader settings and local app data."),
     `LibraryManager` + `SearchIndex` comments.
  5. `docs/mobile-cloud-sync.md` marked SUPERSEDED in place; PROJECT_CONTEXT
     compatibility note updated (old `sync/state.json` + Drive copies still
     known schema-1 shapes, never deleted by the app).
  6. Docs updated in the same commit: S1 audit Phase 1 → ✅ DONE, TASKS.md
     rows, MOBILE-TRACKING session log, `.docs/results/M25-s1-phase1.md`.

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

1. **S1 Phase 2 — Settings rebuild** (open the S1 audit §Phase 2 + locked
   decisions): landing IA (Appearance app-UI store · Reading defaults as a
   real editor with reader-theme preview · Backup & restore rows · Storage
   & recovery with honest labels + corrupt banners · About), slider commit
   coalescing, TopAppBar back/up. Gates + push per phase.
2. Then Phase 3 (reader recovery & comfort) → Phase 4 (library/notes +
   PDF dead-code removal, user decision 2).
3. Later: Play-side split (M6), CI workflow restore (`mobile-ci.yml`),
   `auditscratch/` + repo-hygiene items (user's call).
