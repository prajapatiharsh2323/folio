# M-25 — S1 Phase 1: one-step Drive sync removal (2026-10-29)

> Executes Phase 1 of `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`
> (locked decision 1: the app ships sync-FREE; no runtime migration code
> because old APKs are uninstalled, never updated in place).

## What was removed (one step, no code path to sync remains)

- Deleted `core/sync/` entirely: `CloudSyncWorker.kt`, `DriveAuth.kt`,
  `DriveSyncProvider.kt`, `SyncManager.kt`, `SyncProtocol.kt`,
  `SyncFailure.kt` (all of `SyncManager` scheduling, Drive appDataFolder /
  mirror protocol, now-metadata-wins, WorkManager doze-wraparound logic).
- Deleted `ui/sync/CloudSyncSection.kt` and the Cloud sync section + header
  from `SettingsActivity.kt`.
- Deleted sync tests: `SyncManagerTest.kt`, `SyncFailureMapperTest.kt`,
  `FakeSyncProvider.kt` — exactly 29 test cases (17 + 12), matching the
  350 → 321 suite delta. No other test changed.
- `FolioApplication` work scheduling: no WorkManager scheduling code existed
  there after the sweep (confirmed: zero matches for `CloudSync|WorkManager|
  workmanager|SyncManager…` across `app/src`).
- `docs/mobile-cloud-sync.md` — marked SUPERSEDED in place (historical
  protocol record; schema-1 `sync/state.json` shape stays a known contract,
  old remote Drive copies are never deleted by Folio).

## Dependency & manifest cleanup

- `libs.versions.toml`: dropped `androidx-work-runtime-ktx`,
  `google-api-client-android`, `google-api-services-drive`,
  `google-http-client-gson`, `play-services-auth` + all their versions.
- `app/build.gradle.kts`: dropped the 5 `implementation` lines and the whole
  Google `packaging { excludes }` block (META-INF/DEPENDENCIES, INDEX.LIST,
  LICENSE, NOTICE) — the clean assemble AFTER removal proved no remaining
  dependency needs the excludes (APK packaged fresh, 41.9 MB).
- `AndroidManifest.xml`: removed `INTERNET`, `ACCESS_NETWORK_STATE`,
  `GET_ACCOUNTS`. INTERNET sweep first (as ordered): the only `java.net`
  usage in main source is `URLDecoder` (EPUB import path decoding — local
  string decoding, no network). The app is now fully offline by manifest.

## Copy + comments fixed

- Settings storage row: "Library plus local settings and sync state." →
  "Books, indexes, reader settings and local app data."
- `LibraryManager` corrupt-recovery comment de-synced (libraryId for cloud
  sync) → neutral wording; `SearchIndex` class doc "drive-synced" → single
  local library.

## Preserved intentionally (untouched)

- `library/`, backup/restore streaming ZIP + SHA-256 + Merge/Replace, all
  Phase 0 data-safety work, schema-1 contracts. Old `sync/state.json` files
  on disk and old Drive `Folio Library` copies are simply left as-is.

## Verification (this VM, fully re-provisioned SDK after a fresh reset)

- SDK re-provisioned again from scratch (platform-35 + build-tools 35.0.0 +
  platform-tools at `/tmp/opencode/android-sdk`).
- **Gates: `testDebugUnitTest` 321/321 green, `lintDebug` 0 errors
  (60 warnings + 6 info, all pre-existing noise), `assembleDebug` green**
  (APK at `app/build/outputs/apk/debug/app-debug.apk`, 41.9 MB).
- Release fail-safe re-verified after the build-file edits:
  `assembleRelease` still refuses via `folio-release-sign-gate`.
- Suite math check: 350 (Phase 0) − 29 deleted sync tests = 321 ✔.
- User-test APK: `folio-mobile-debug-2026-10-29-sync-free.apk` at repo root
  (sha256 47068661…ad02315). NOTE for the user: per locked decision 1,
  UNINSTALL the 2026-10-27 sync-enabled APK before installing this build —
  never install over the old one.
- Device journeys NOT run (no device/KVM in VM) — user-side, as always.

## Environment note (for future sessions)

- `/home` is a small 4.8 GB separate disk on this VM class; Gradle caches on
  `~/.gradle` + project build dirs can fill it (daemon "disappeared"
  signature, no swap on the box). Mitigation used here: run gates with
  `GRADLE_USER_HOME=/tmp/opencode/gradle-home` (big `/` disk), and stop
  daemon + drop caches if disk-full symptoms appear. `--rerun-tasks` full
  rebuilds can OOM the daemon on this 7.8 GB / no-swap VM (gradle.properties
  `-Xmx4096m`) — run gate tasks individually instead.

## Next

S1 Phase 2 — Settings rebuild (see S1 audit §Phase 2).
