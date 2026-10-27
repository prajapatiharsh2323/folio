# Folio — First Stable Release Bug-Fix Report

**Date:** 27 October 2026  
**Scope audited/fixed:** `mobile/` — native Android (Kotlin + Compose, Readium 3 EPUB reader), versionName `0.2.0`, versionCode `2`  
**Baseline commit:** `0526b14` (`fix(p1/p2): batch import, payload-verified recovery, notes/search discoverability`)  
**Working tree:** 17 modified files + 2 new files, **uncommitted**; `main` remains 4 commits ahead of `origin/main`.

---

## A. Verdict

**Conditional GO for the code-level release candidate.**

Every P0/P1 defect found in this pass is fixed and covered by automated regression tests. The full unit suite, lint and debug build are green:

- `:app:testDebugUnitTest` → **340 tests, 0 failures, 0 errors, 0 skipped** (49 result XML files)
- `:app:lintDebug` → **BUILD SUCCESSFUL, 0 errors** (76 warnings, 6 informational; all pre-existing categories/noise, see §D)
- `:app:assembleDebug` → **BUILD SUCCESSFUL** (`app-debug.apk`, 44,698,240 bytes)

**Not yet a final ship sign-off:** no Android device, emulator, AVD, system image or `/dev/kvm` is available in this environment, so the 15 end-to-end device journeys, TalkBack/gesture checks and real Google Drive sync could **not** be executed. This is reported as **blocked, not passed** (§E). Code correctness for those journeys is covered only by unit tests, compilation and source review.

---

## B. Bug inventory

Severity scale: **P0** data loss/corruption · **P1** major reader/library/import/accessibility failure · **P2** material UX defect · **P3** polish.

### FR-B01 — Library timestamps could be written as invalid `…ZZ`

| Field | Detail |
|---|---|
| **Severity** | P1 |
| **Platform** | Android (clock-precision dependent), shared schema-1 files |
| **Evidence** | `LibraryManager` default was `Instant.now().toString().take(24) + "Z"`. `Instant.toString()` is variable-width and already ends in `Z`. On a millisecond clock it is exactly 24 chars, so the result became `2026-10-26T18:25:33.947ZZ`. JVM tests used a nanosecond clock, masking it. |
| **Impact** | `Instant.parse` rejects the value, so `epochMs()` returned 0 for both sides. “Newer wins” annotation/progress/metadata merges silently stopped updating; the PWA also could not parse the timestamp. |
| **Root cause** | String slicing assumed a fixed-width representation instead of formatting explicitly. |
| **Fix** | Added `core/util/IsoTime.kt`: fixed millisecond UTC format with one `Z`; `now()`, `format()`, `epochMs()`, `parseOrNull()`. Wired into `LibraryManager.nowIso`, `SyncManager`, `BackupRestore` and `CollectionRows.relativeUpdateLabel`. Legacy `…ZZ` values are still parsed so pre-fix libraries compare correctly. |
| **Test** | `IsoTimeTest` — fixed milliseconds, single-`Z` regex, round-trip, legacy `ZZ`, blank/malformed inputs. |
| **Verification** | Full suite green; `IsoTimeTest` passes. |
| **Limitation** | Existing entries keep their old `ZZ` text until rewritten; tolerance keeps them usable, new writes are canonical. |

### FR-B02 — Corrupt global reader settings were silently replaced by defaults

| Field | Detail |
|---|---|
| **Severity** | P1 |
| **Platform** | Android (`library/user/settings.json`, schema-1 backup contract) |
| **Evidence** | `PrefsStore.loadReadingPrefs()` wrapped decode in `runCatching { … }.getOrDefault(ReadingPrefs())`; `saveReadingPrefs()` then overwrote the unreadable file. |
| **Impact** | A partial/corrupt write looked like “no settings yet”. The user saw factory typography, and the next change destroyed the only copy of their real preferences. |
| **Root cause** | Missing `ReadResult`-style distinction between “missing” and “unreadable”. |
| **Fix** | Typed `readReadingPrefs(): ReadResult<ReadingPrefs>`; strict `loadReadingPrefs()` throws `LibraryFileCorruptException` for corrupt files; `saveReadingPrefs()` copies corrupt bytes to `library/recovery/settings.json-corrupt-<stamp>` before writing. `SettingsActivity` already catches and states defaults are shown; `ReaderActivity` now catches too and keeps reading. |
| **Test** | `PrefsStoreTest` — corrupt read is `Corrupt` and does not modify bytes; strict load throws; save preserves byte-identical recovery copy; valid saves create no recovery files. |
| **Verification** | Full suite green. |
| **Limitation** | No dedicated “restore old settings” UI; the preserved copy is under `library/recovery/` and the user can recover by changing a setting (which rebuilds the file). |

### FR-B03 — Corrupt per-book settings were treated as empty on write

| Field | Detail |
|---|---|
| **Severity** | P2 (typography is reconstructible) |
| **Platform** | Android (`library/user/doc-settings.json`, mobile-only) |
| **Evidence** | `DocPrefsStore.set()` used `runCatching { decode }.getOrElse { emptyMap() }`, then wrote the whole map. |
| **Impact** | One bad byte dropped every per-book override without a copy. |
| **Fix** | `get()` remains a tolerant display fallback (reader always opens); `set()` strict-reads and, on corruption, preserves the original bytes under `library/recovery/doc-settings.json-corrupt-<stamp>` before rebuilding. |
| **Test** | `DocPrefsStoreTest` — corrupt read returns null without modifying bytes; repair preserves a byte-identical copy; repeated writes preserve only once. |
| **Verification** | Full suite green. |
| **Limitation** | Repeated corruption events within the same millisecond would collide on the recovery filename (existing recovery-name convention; extremely unlikely). |

### FR-B04 — Backup merge overwrote unreadable local state without a recovery copy

| Field | Detail |
|---|---|
| **Severity** | P1 |
| **Platform** | Android backup merge (`BackupRestore`) |
| **Evidence** | Merge used tolerant reads for local manifest/annotations/progress/collections and wrote the backup-derived data over them; warnings did not say where the old bytes went. |
| **Impact** | A corrupt local notes/progress/collections file or manifest was replaced permanently during “merge”, which users reasonably expect to preserve local data. |
| **Root cause** | Typed `ReadResult` existed but merge collapsed `Corrupt` straight to empty, then overwrote. |
| **Fix** | `preserveCorruptLocal()` copies the unreadable file byte-for-byte to `library/recovery/<name>-corrupt-<stamp>` before replacement and adds a warning naming the copy. Corrupt manifest fallback now also preserves bytes. |
| **Test** | `BackupRestoreTest` — corrupt notes/progress/collections recovered; corrupt manifest recovered; recovery bytes asserted equal; warnings name the recovery path. |
| **Verification** | Full suite green. |
| **Limitation** | Merge still proceeds after preserving (deliberate escape hatch) rather than blocking; the summary warning must be reviewed by the user. A corrupt local manifest still cannot contribute its doc list, so local docs may disappear from the index while their files remain (pre-existing merge semantics, now recoverable). |

### FR-B05 — Cloud sync could overwrite corrupt local or remote user data

| Field | Detail |
|---|---|
| **Severity** | **P0** |
| **Platform** | Android sync (`SyncManager`) |
| **Evidence** | Remote manifest and remote user files used `runCatching { parse }.getOrElse { empty… }`; local reads used tolerant `getAnnotations()/getAllProgress()/getCollections()`, which log and return empty on corruption. The merged result was then written locally and uploaded. |
| **Impact** | A corrupt local notes/progress/collections file was replaced by empty + remote and pushed to the mirror; a corrupt remote file was replaced by local data. This is permanent loss of the unreadable side. |
| **Root cause** | Corruption was collapsed to “empty” in a write path, violating the repository’s own typed-read contract. |
| **Fix** | Remote manifest / annotations / progress / collections parse failures now throw a typed `SyncDataUnreadableException` **before any user-data write or manifest upload**. Local reads use `readAnnotationsState()/readAllProgressState()/readCollectionsState()` and also abort on `Corrupt`. Local manifest was already strict. |
| **Test** | `SyncManagerTest` — corrupt remote manifest; corrupt remote notes; corrupt local notes; corrupt local progress; corrupt local collections. Each asserts abort, unchanged local bytes and no uploaded manifest. |
| **Verification** | Full suite green. |
| **Limitation** | Document files may already have been downloaded/uploaded before reconciliation aborts. That is idempotent and never advertises a half-synced library (manifest upload is last); the next sync resumes. |

### FR-B06 — Corrupt-sync failures looked like a generic retryable network error

| Field | Detail |
|---|---|
| **Severity** | P2 |
| **Platform** | Android sync UI / WorkManager |
| **Evidence** | All sync exceptions mapped through `SyncFailureMapper`; the new abort was an `IllegalStateException`, which fell through to `Unknown / “Sync didn't finish. Check your connection and try again.”` and was marked retryable. |
| **Impact** | Users would retry forever instead of being told which data is unreadable; periodic workers kept failing. |
| **Fix** | New `SyncFailureKind.DataUnreadable`, mapped first from `SyncDataUnreadableException`, non-retryable, with the actionable message. Manual sync shows the message and disables auto-sync; the worker marks needs-attention and disables the schedule. No exception/cause text is leaked into UI copy. |
| **Test** | `SyncFailureMapperTest` — `DataUnreadable`, verbatim user message, `retryable = false`. |
| **Verification** | Full suite green; UI/worker `when` branches compile. |
| **Limitation** | Recovery still requires the user to repair/discard the affected data in Notes/library; no one-tap repair was added. |

### FR-B07 — Reader closed silently when a book or its files were unreadable

| Field | Detail |
|---|---|
| **Severity** | P2 |
| **Platform** | Android reader |
| **Evidence** | Three paths (`meta/reading` missing, `openPublication == null`, no initial locator) called `withContext(Main) { finish() }` with no message. A corrupt global settings file could also throw out of the prefs coroutine. |
| **Impact** | The reader flashed and disappeared with no explanation; users could not distinguish a deleted book from a damaged file. |
| **Fix** | `finishWithMessage()` toasts a specific reason before closing; prefs load failure falls back to defaults with a toast and leaves the settings file untouched (both initial load and per-book reset). |
| **Test** | Compilation + source review. No instrumentation test (device QA blocked). |
| **Verification** | `:app:assembleDebug` and `:app:lintDebug` green. |
| **Limitation** | Toast copy/behaviour is not verified on a real device or with TalkBack. |

### FR-B08 — Restored libraries had no search index

| Field | Detail |
|---|---|
| **Severity** | P2 |
| **Platform** | Android backup restore (merge and replace) |
| **Evidence** | Backup export deliberately excludes `library/index/text` (spec §8); `BackupRestore` never rebuilt it. Sync already did via `SyncManager.rebuildSearchText`. |
| **Impact** | After restore, books opened normally but global search silently returned nothing until re-import. |
| **Fix** | `rebuildMissingSearchText()` re-extracts search text from each restored original file, best-effort, after the manifest commit. Failures aggregate into one restore warning; they never fail a committed restore. |
| **Test** | `BackupRestoreTest` — replace restore into an empty library, `getSearchText(restored.id)` is non-empty. |
| **Verification** | Full suite green. |
| **Limitation** | Indexing runs under the restore file locks and can be CPU-heavy for large libraries; for very large archives restore completion will be slower. PDF originals are unsupported by design. |

### FR-B09 — Import size cap was checked after buffering the whole file

| Field | Detail |
|---|---|
| **Severity** | P2 |
| **Platform** | Android SAF import |
| **Evidence** | `openInputStream(uri).use { it.readBytes() }` followed by `if (bytes.size > 50 * 1024 * 1024)`. |
| **Impact** | A large or unknown-length file could OOM the process before the friendly “File too large” message. |
| **Fix** | Pre-check `openAssetFileDescriptor().length` when available, then stream through `readAtMost(MAX_IMPORT_BYTES)` which rejects at 50 MB + 1 byte. |
| **Test** | Compilation + source review (the helper depends on `ContentResolver`, so no JVM unit test was added). |
| **Verification** | `:app:assembleDebug` green. |
| **Limitation** | Providers that report `UNKNOWN_LENGTH` are still bounded by the streaming cap; the first 50 MB can be read before rejection, but the heap is bounded. |

### Test-suite correction (not a product bug)

`DocPrefsStoreTest` previously asserted “corrupted file is treated as empty not fatal”, which encoded the old unsafe write behaviour. It now asserts corrupt-read fallback **and** byte-identical preservation before repair.

---

## C. Changed files

**New**

- `mobile/app/src/main/kotlin/dev/folio/mobile/core/util/IsoTime.kt`
- `mobile/app/src/test/kotlin/dev/folio/mobile/core/util/IsoTimeTest.kt`
- `FIRST_RELEASE_BUGFIX_REPORT.md` (this report)

**Production**

- `mobile/app/src/main/kotlin/dev/folio/mobile/core/library/LibraryManager.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/store/PrefsStore.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/store/DocPrefsStore.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/backup/BackupRestore.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/sync/SyncManager.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/sync/SyncFailure.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/core/sync/CloudSyncWorker.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/ui/sync/CloudSyncSection.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/reader/ReaderActivity.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/MainActivity.kt`
- `mobile/app/src/main/kotlin/dev/folio/mobile/ui/components/CollectionRows.kt`

**Tests**

- `mobile/app/src/test/kotlin/dev/folio/mobile/core/util/IsoTimeTest.kt` (new)
- `mobile/app/src/test/kotlin/dev/folio/mobile/core/store/PrefsStoreTest.kt`
- `mobile/app/src/test/kotlin/dev/folio/mobile/core/store/DocPrefsStoreTest.kt`
- `mobile/app/src/test/kotlin/dev/folio/mobile/core/backup/BackupRestoreTest.kt`
- `mobile/app/src/test/kotlin/dev/folio/mobile/core/sync/SyncManagerTest.kt`
- `mobile/app/src/test/kotlin/dev/folio/mobile/core/sync/SyncFailureMapperTest.kt`

**Docs**

- `.docs/FIX-PLAN-TRACKING.md` (FIX-E entry)

---

## D. Verification results

All commands run from `/home/prajapatiharsh2323/projects/folio/mobile`.

| Check | Command | Result | Exit |
|---|---|---|---|
| Unit tests (baseline, before edits) | `./gradlew :app:testDebugUnitTest --console=plain` | 322 tests, 0 failures, 0 errors, 0 skipped (48 XML files) | 0 |
| Unit tests (final) | `./gradlew :app:testDebugUnitTest --console=plain --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8"` | **340 tests, 0 failures, 0 errors, 0 skipped** (49 XML files) | 0 |
| Lint (baseline) | `./gradlew :app:lintDebug --console=plain` | 71 warnings, 6 informational, **0 errors** | 0 |
| Lint (final) | `./gradlew :app:lintDebug --console=plain --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8"` | 76 warnings, 6 informational, **0 errors** | 0 |
| Debug build | `./gradlew :app:assembleDebug --console=plain --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8"` | `BUILD SUCCESSFUL`; APK 44,698,240 bytes | 0 |
| Combined final gate | `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain …` | `BUILD SUCCESSFUL` | 0 |

**Test delta:** +18 tests (322 → 340), all passing. New coverage: timestamps (5), global settings corruption (3), per-book settings corruption (1 net new), backup recovery/search (3), sync corruption (5), sync failure mapping (1).

**Lint delta:** +5 warnings, 0 errors. All five are `LogNotTimber` on newly added `android.util.Log` lines; the project has no Timber dependency and 22 pre-existing `LogNotTimber` warnings use the same pattern. No new accessibility, correctness or security issue was introduced.

**Environment note:** the SDK at `/tmp/opencode/android-sdk` was absent at the start of this run (`/tmp` had been cleared). It was reprovisioned with `platform-tools`, `platforms;android-35` and `build-tools;35.0.0`; `mobile/local.properties` already pointed there. This is environmental, not a code failure.

**Test totals** were parsed directly from `app/build/test-results/testDebugUnitTest/*.xml`, not from Gradle’s summary line.

---

## E. Unresolved / blocked

1. **Device and accessibility QA — BLOCKED.** `adb devices` reports no device; there is no `emulator` binary, no AVD, no installed system image, and no `/dev/kvm` (0 virtualization flags). The 15 release journeys therefore could not be executed:
   1. cold start / empty library
   2. single-file EPUB import
   3. batch TXT/HTML/EPUB import with duplicate Skip/Replace/Keep both
   4. open book and first-page render
   5. resume after process death
   6. table of contents / chapter jump
   7. in-book search with exact-match highlight
   8. highlight + note create/edit/delete
   9. bookmark add/remove and reopen persistence
   10. collection create/add/remove/delete
   11. per-book appearance changes and reset to global
   12. global settings persistence
   13. backup export + merge restore + replace restore (including warnings)
   14. Google Drive connect / manual sync / background sync
   15. TalkBack, large font (1.3×/1.5×), 320 dp and gesture navigation

2. **No real Google Drive integration test.** Sync was verified against `FakeSyncProvider` only. Token refresh, Drive quota/status mapping and network transitions remain device/network-dependent.

3. **No instrumentation or screenshot tests were run.** All verification is JVM unit tests, lint and compilation.

4. **Performance of post-restore search rebuild on very large libraries** is not measured on device.

5. **Recovery files are timestamp-named at millisecond precision.** Two corruption events on the same file in the same millisecond could overwrite the earlier recovery copy. This matches the existing manifest recovery convention and was not changed.

6. **Existing lint warnings remain** (dependency versions, `LogNotTimber`, autoboxing, TrustAllX509TrustManager in the vendored/Drive stack). None are errors and none were introduced by this batch except the consistent `LogNotTimber` lines.

7. **Changes are uncommitted.** No commit, tag or release artifact was published.

---

## F. Regression risks

- **Strict settings read:** `PrefsStore.loadReadingPrefs()` now throws on corruption instead of returning defaults. Only `SettingsActivity` and `ReaderActivity` call it; both handle it. Any future caller must handle `LibraryFileCorruptException`.
- **Sync now aborts on unreadable data.** A user with one corrupt local file will see “Data unreadable” and auto-sync disabled instead of a partial sync. This is intentional, but it is a behaviour change from “sync appears to succeed”.
- **Partial sync side effects:** doc payloads may be copied before the abort, but the manifest is written/uploaded last, so the mirror never advertises a half-synced library. Re-running resumes.
- **Backup merge remains non-blocking** for corrupt local state: it preserves the bytes and continues. Users who ignore the warning still end up with the backup’s version live; the recovery copy is the only rollback path.
- **Search rebuild after restore** adds I/O/CPU to restore and holds the file locks longer. Failures are warnings, never restore failures.
- **Timestamp legacy tolerance** accepts `…ZZ` indefinitely. New writes are canonical; no data migration was performed.
- **Import cap:** files between 50 MB and the provider’s reported size are rejected before reading; unknown-length providers are rejected after 50 MB + 1 byte. The previous behaviour was identical for files under 50 MB.
- **Lint noise:** +5 `LogNotTimber` warnings make the warning count look worse despite no functional regression.

---

## G. Release checklist

| Item | Status |
|---|---|
| Corrupt/unreadable files never treated as empty in write paths | ✅ fixed for manifest, annotations, progress, collections, global settings, per-book settings, sync mirrors |
| Original bytes preserved before overwrite | ✅ `library/recovery/*-corrupt-<stamp>` for settings, doc settings, backup merge, manifest repair |
| Backup format / hashing / paths unchanged | ✅ no schema or path changes; archives remain schema-1/PWA-compatible |
| Timestamp contract valid and cross-app parseable | ✅ fixed milliseconds + single `Z`; legacy values tolerated |
| Reader opens despite corrupt global settings | ✅ defaults + explanatory toast; file untouched |
| Reader explains missing/damaged book | ✅ toast before close |
| Restore rebuilds search corpus | ✅ best-effort + warning |
| Sync never overwrites unreadable local/remote state | ✅ typed abort before writes |
| Sync failure is actionable and not retried forever | ✅ `DataUnreadable`, non-retryable, auto-sync disabled |
| Import size cap enforced before OOM | ✅ descriptor pre-check + streaming cap |
| Full unit suite | ✅ 340/340 pass |
| Lint | ✅ 0 errors |
| Debug APK | ✅ builds |
| Real-device journeys 1–15 | ⛔ **blocked** (no device/emulator/KVM) |
| TalkBack / large text / 320 dp | ⛔ **blocked** |
| Real Google Drive sync | ⛔ **blocked** (fake provider only) |
| Report + tracking updated | ✅ this file + `.docs/FIX-PLAN-TRACKING.md` |
| Commit/tag/release | ⬜ not performed |
