# M-25 — S1 Phase 0: data safety & release gates (2026-10-29)

> Executes Phase 0 of `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`
> (user-supplied Settings/sync/UX audit, decisions locked with the user).

## Baseline (honest, this VM)

- SDK re-provisioned: platform-35 + build-tools 35.0.0 + platform-tools at
  `/tmp/opencode/android-sdk`, `mobile/local.properties` repointed
  (git-ignored, never committed).
- Gates on untouched HEAD `9058c8e`: **test + lint + assembleDebug green**
  (`BUILD SUCCESSFUL in 6m 47s`, log `/tmp/opencode/baseline-gate.log`).
- Note: HEAD moved past the last checkpoint (`1bc7ef7`) — `9058c8e` is the
  user's own commit deleting `desktop-docs/` (10 files). Docs references to
  desktop-docs styling/base remain stale; the desktop plan target is paused
  by the user. Nothing restored.

## Changes (5 files, +415/−62, no data/schema/contract changes)

1. **`core/store/PrefsStore.kt` — unreadable settings can no longer be
   overwritten on failed preservation.** `preserveCorrupt()` now (a) reads
   the corrupt bytes ONCE, (b) requires the recovery copy to exist AND read
   back byte-identical before the save proceeds, (c) throws
   `IllegalStateException` ("save aborted, your old settings file is
   unchanged") in all failure paths. Settings writer's existing catch shows
   the visible error and reverts the UI — the single global-prefs writer.
   (`DocPrefsStore` already threw on write failure and stays as-is;
   per-book settings are reconstructible by design, see its class doc.)
2. **`core/backup/BackupZip.kt` — size caps enforced on REAL decompressed
   bytes while streaming.** New `StagedArchive.readEntryStreamed(limit)`
   (64 KiB chunks, incremental SHA-256, aborts the instant the count passes
   the cap — heap never holds more than one chunk of a hostile entry),
   `readEntryLimited(limit)` (bounded whole-entry bytes), + limits:
   per-entry 512 MB, total 1 GB (unchanged), metadata 16 MB, user-state
   64 MB; `EntryOversizeException`; declared `ZipEntry.size` is a cheap
   pre-flight hint only, never trusted. `unzipAndVerifyBackup(archive,
   limits = BackupLimitsSpec.DEFAULT)` streams/doc-digests (no buffering);
   metadata/user entries are read capped and hashed; message distinguishes
   total-cap vs per-entry breach. Old `readEntry`/`readTextEntry` remain
   for small diagnostics/tests only.
3. **`core/backup/BackupRestore.kt`** — all reads capped
   (`parseUserState` 64 MB, staging/merge reads 512 MB, manifest 16 MB);
   `limits` param threaded through `restore()` (default = production caps).
   Semantics untouched: verify-then-act, merge rules, staged swap,
   rollback, recovery copies.
4. **`app/build.gradle.kts` — production signing can no longer silently
   fall back to the debug keystore.** Real credentials = git-ignored
   `mobile/keystore.properties`. Without them every release-variant task
   fails via the new `folio-release-sign-gate` task (clear GradleException
   listing the options). Debug-keystore signing ONLY behind
   `-Pfolio.localReleaseSigning=true` / `FOLIO_LOCAL_RELEASE_SIGNING=1`,
   with a loud "not for publication" banner. Verified all three cases:
   default refuses (correct error); flag builds a signed APK (apksigner
   confirms `CN=Android Debug`); debug gates (test/lint/assemble) are
   unaffected.
5. **Tests:** `PrefsStoreTest` +3 (fault injection: recovery write fails /
   copy evaporates / copy mutates → save aborted, corrupt file
   byte-for-byte intact); new `BackupZipLimitsTest` (7 tests): mid-stream
   abort at an undersized cap, `readEntryLimited == readEntry` parity,
   oversized-entry refusal, total-cap refusal, metadata-cap refusal,
   **over-limit backup refused via `BackupRestore.restore` with the local
   library byte-for-byte untouched**, and a real 640 MB deflate zero-bomb
   terminated at the production 512 MB entry cap (streamed chunk-wise, no
   heap growth).

## Verification

- Targeted: PrefsStoreTest 11/11, backup package 22/22 pre-existing,
  BackupZipLimitsTest 7/7.
- Full gates AFTER changes: `:app:testDebugUnitTest :app:lintDebug
  :app:assembleDebug` — **green, 350/350 tests** (was 340; +10 new).
- Not run on device (no emulator); nothing device-level claimed.

## Next

S1 Phase 1 — one-step Drive sync removal (see S1 audit file §Phase 1).
