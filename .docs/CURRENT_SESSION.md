# Current Session Checkpoint

> This file is the recovery anchor. After every meaningful milestone, update:
> date, verified state, what changed, blockers, and the **exact next action**.

- **Last updated:** 2026-10-29 (session M-25 — S1 audit Phase 0 landed)
- **Last verified project state:** HEAD is `9058c8e` + the M-25 Phase 0 commit
  (user's `9058c8e` deleted `desktop-docs/` — deliberate, not touched). Phase 0
  changes verified in THIS VM on the freshly re-provisioned SDK:
  **350/350 unit tests, lintDebug clean, assembleDebug green** (was 340 tests
  before M-25's +10). Release-gate behavior verified on the build system:
  `assembleRelease` fails without credentials; `-Pfolio.localReleaseSigning=true`
  builds a debug-signed APK (apksigner confirmed, never for publication).
  Device journeys NOT verified (no device/KVM here) — user-side.
- **This session changed (Phase 0 of `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`):**
  1. `core/store/PrefsStore.kt` — preservation must exist AND hash-verify
     before the save proceeds; all failure paths throw and abort the save
     (`DocPrefsStore` untouched — already throwing).
  2. `core/backup/BackupZip.kt` — streaming caps on real decompressed bytes
     (`readEntryStreamed`/`readEntryLimited`, incremental SHA-256, entry 512 MB
     / total 1 GB / metadata 16 MB / user-state 64 MB), injectable
     `BackupLimitsSpec`; BackupZip semantics/schema untouched.
  3. `core/backup/BackupRestore.kt` — cap-aware reads throughout; `limits`
     param (default = production).
  4. `app/build.gradle.kts` — signing fail-safe: missing credentials refuse
     release tasks (`folio-release-sign-gate`); debug-keystore fallback only
     behind explicit `folio.localReleaseSigning` / `FOLIO_LOCAL_RELEASE_SIGNING`
     with a non-publishable banner.
  5. Tests: PrefsStoreTest +3 fault-injection; new `BackupZipLimitsTest`
     (7: mid-stream abort, parity, entry/total/metadata cap refusals,
     refused-Replace keeps local library byte-identical, real 640 MB
     zero-bomb). ZIP suites otherwise preserved untouched (22/22 green).
  6. Docs updated in the same commit: audit adoption file
     (`.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`),
     `.docs/results/M25-s1-phase0.md`, TASKS.md rows, MOBILE-TRACKING log.

## Blockers

- No device/emulator: S1 device checklist (TBD Phase 4) is user-side.
- `assembleRelease` for a real release needs git-ignored
  `mobile/keystore.properties` (user-side decision 3 of the S1 audit).

## Exact next action

1. **S1 Phase 1 — one-step Drive sync removal** (open the S1 audit §Phase 1 in
   `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md` + execution order):
   repository-wide symbol sweep (CloudSyncWorker/CloudSyncScheduler/DriveAuth/
   DriveSyncProvider/SyncManager/SyncProtocol/SyncFailure/FakeSyncProvider/
   `folio-cloud-sync`/`folio-drive-auth`); delete sync code+tests+UI; remove
   WorkManager + Google deps/catalog aliases; remove `GET_ACCOUNTS`
   (and `ACCESS_NETWORK_STATE` if sync-only); `INTERNET` usage sweep first;
   drop Google META-INF excludes only after a clean assemble; fix the stale
   "and sync state" storage copy; ZIP regression suite green after every
   batch; gates + push per phase.
2. Keep the S1 order: Phase 2 Settings rebuild → Phase 3 reader recovery →
   Phase 4 library/notes finish + PDF dead-code removal (user decision 2).
3. Later: decide the Play-side work split (M6), CI workflow restore
   (`mobile-ci.yml`), `auditscratch/` + repo-hygiene items (user's call).
