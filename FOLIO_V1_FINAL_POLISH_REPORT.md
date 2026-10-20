# Folio v1 — Final MVP Polish Report

Date: 2026-10-20 · Branch: `main` (working tree, uncommitted) · Scope: mobile (`mobile/`) Android app
Verification JDK: Temurin 17 (`C:\hostedtoolcache\windows\Java_Temurin-Hotspot_jdk\17.0.20-101\x64`) — the shell default JDK 8 cannot configure AGP 8.7.3.

---

## 1. Summary

| Area | Result |
| --- | --- |
| P0 Settings duplication | **Passed** — no duplication exists in current source; verified composable tree renders each section exactly once (diagnosis below). Theme selector made an intentional scroller with edge-fade cues. |
| P1 "Cloud sync says offline" | **Partially verified** — root cause confirmed at code level: the mapper labelled *every* `IOException` as Offline. Cause-chain-aware classification implemented + 17 mapper regression tests green. Real-Drive verification requires a device → listed in the manual checklist. |
| P2 Backup vs cloud sync | **Passed** (code-level) — export is now atomic (temp + rename), collision-safe, and uses the PWA filename contract. Restore flow re-audited: verified-before-mutation, staged streaming, distinct from sync. |
| P3 Reference-style slider | **Passed** (code-level) — diamond thumb, thin receding track, clamped/NaN-safe values, native drag/tap/keyboard/TalkBack. |
| P4 Input sheets & IME | **Passed** (code-level) — collection sheets and Details sheet ride above the IME, fields carry accessible labels, keyboard Done submits, duplicate-submit guards added. |
| P5/P6 sweep | **Passed** — attention state refresh on resume; safe diagnostic logging; busy-flag reset on the inline Create path. |

Build/tests: `:app:testDebugUnitTest` **Passed** (42 suites, 266 tests, 0 failures), `:app:assembleDebug` **Passed**, `:app:lintDebug` **Passed** (only pre-existing `OldTargetApi` / `UnusedAttribute` warnings).

## 2. Root-cause findings

### Settings duplication (Priority 0)

I traced the full Settings composable tree against the checked-in source:

- `SettingsActivity.kt` contains a single `SettingsScreen()`; the only `CloudSyncSection()` call site is there (`item("cloud-group")`), and every section — Reading, Backup, Cloud sync, Storage, About — is emitted exactly once as unique, non-colliding `LazyColumn` item keys (`reading-*`, `backup-*`, `cloud-*`, `storage-*`, `about-*`).
- Grep across the whole repo finds exactly one `FolioSectionHeader(text = "Storage")`, one `"Cloud sync"` header, and one row set containing `Auto-sync` / `Disconnect account` (inside `CloudSyncSection.kt`). No second `SettingsActivity`, no duplicate composable calls, no nested groups.
- Also checked history: commit `be56911` rebuilt Settings into the current clean structure; the pre-redesign layout (`4c4753d`, before it) is what a returned/stale install could look like.

**Conclusion:** the duplicated Cloud-sync/Storage content in the screenshot is evidence of a stale installed APK, not the current source. Confirmed by clean rebuild: `assembleDebug` produced a fresh APK at `app/build/outputs/apk/debug/app-debug.apk` — reinstalling it replaces the duplicated screen. I deliberately did **not** add masking flags (alpha/visibility/height) anywhere.

### "Offline" misclassification (Priority 1)

- `SyncFailureMapper.from()` mapped **every** `java.io.IOException` to `Offline`, including local storage I/O and transport setup failures from the Google HTTP stack, so non-network problems were reported as "your phone is offline". The shipped unit test even codified the wrong behaviour (`generic io failures stay recoverable` → asserted `Offline`).
- Exception semantics inspection: `GoogleJsonResponseException`, `UserRecoverableAuthIOException` and Google-auth errors all arrive as `IOException` subclasses, so the class name alone proves nothing; HTTP status and cause chain carry the real signal.
- Connectivity pre-check in the manual path: there is none — manual "Sync now" goes straight to the provider, so no stale network snapshot can block it. Manual sync also bypasses the worker's `UNMETERED` + battery-not-low constraints entirely (they are WorkManager constraints only, applied in `CloudSyncScheduler.schedule`, not to the foreground path).
- Duplicate concurrent syncs cannot interleave: `SyncManager.sync()` is serialised by a process-wide `Mutex` plus the `busy` flag in the UI.

## 3. Changed files

| Path | Change |
| --- | --- |
| `mobile/app/src/main/kotlin/dev/folio/mobile/core/sync/SyncFailure.kt` | Rewrote classification to walk the cause chain, most-specific first: recoverable-consent → Google HTTP status → Google-auth/account → `FileNotFoundException` (local storage) → named network-transport types (DNS/connect/socket-timeout/socket/SSL) → **neutral retryable "sync didn't finish"** for unknown I/O (no longer claims offline). `CancellationException` is rethrown, never mapped. 403 rate-limit reasons (`rateLimitExceeded`, `userRateLimitExceeded`, `sharingRateLimitExceeded`, `dailyLimitExceeded`) map to retry-later, not permission loss. |
| `mobile/app/src/main/kotlin/dev/folio/mobile/ui/sync/CloudSyncSection.kt` | Safe diagnostic logging on manual-sync failure (kind + exception class name only — never tokens, messages, or book content). ON_RESUME also re-reads the auto-sync attention state (background worker failure now shows when Settings reopens) while leaving an in-progress manual sync untouched. |
| `mobile/app/src/main/kotlin/dev/folio/mobile/core/backup/BackupZip.kt` | `generateBackupFilename` now emits the PWA contract `folio-backup-YYYYMMDD-HHMM.zip` (code previously returned a bare date-time, contradicting its own doc and the web test `^folio-backup-\d{8}-\d{4}\.zip$`). |
| `mobile/app/src/main/kotlin/dev/folio/mobile/SettingsActivity.kt` | Export writes to a temp `.part` file, fsyncs, then renames — an interrupted export can no longer leave an apparently-complete backup; serial suffix prevents same-minute truncation. Theme picker (`DefaultThemePicker`) made an intentional horizontal scroller: tighter rhythm (12 → 8dp gaps), larger end inset so the "System" card scrolls fully into view, and animated-free `EdgeFade` gradients that appear only on the sides where more content exists (driven by `canScrollForward/Backward`). Selection targets unchanged (≥44dp swatches + label, `selectableGroup`). |
| `mobile/app/src/main/kotlin/dev/folio/mobile/ui/components/FolioSlider.kt` | Replaced the plain M3 look: 4dp thin track (inactive `surfaceVariant` recedes onto the page, active half in restrained `primary`, round caps), compact 13dp diamond/lozenge thumb (rotated rounded square) centred on the track line, transparent native chrome under it. Contract: values clamped to range, NaN/∞ never rendered or emitted, `steps` coerced ≥ 0. M3 core retained → drag, track tap, steps, keyboard, and TalkBack range semantics all remain native; persistence still owned by callers via `onValueChangeFinished` (one commit per drag). Same component reused by every reading slider (text size, line spacing, margins, brightness). |
| `mobile/app/src/main/kotlin/dev/folio/mobile/ui/components/FolioTextField.kt` | Optional accessible `label` (semantic `contentDescription` on the field) — placeholders remain examples only, per a11y requirement. |
| `mobile/app/src/main/kotlin/dev/folio/mobile/ui/components/CollectionSheets.kt` | `CreateCollectionSheet`: a11y label, helper sentence removed, keyboard Done submits the trimmed name, Create disabled until valid (blank/whitespace rejected), `creating` guards duplicate submission, keyboard hidden on success, sheet content `imePadding()` so field stays reachable above the IME. `RenameCollectionSheet`: `imePadding()`, label, Done saves when valid. `CollectionPickerSheet` / `BookPickerSheet`: `imePadding()`, labels, quick-create duplicate-submit guard. |
| `mobile/app/src/main/kotlin/dev/folio/mobile/MainActivity.kt` | `DetailsDialog` sheet: `imePadding()` inside the scroll column, accessible labels on tag/collection fields, and a busy guard (`finally` reset) so the inline Create cannot submit twice while the first call runs. |
| `mobile/app/src/test/kotlin/dev/folio/mobile/core/sync/SyncFailureMapperTest.kt` | Rewrote + extended to the required matrix: DNS/connect/timeout/SSL → Offline retryable; wrapped network causes inside plain IOException → Offline; generic IOException → Unknown retryable and *never* claims "offline"; `FileNotFoundException` → Storage; 401 → AuthRequired; 403 permission vs rate-limit reasons; 404 message; 429; 5xx; wrapped status errors inside IOException honoured; cancellation rethrown; no exception-text leakage. |
| `mobile/app/src/test/kotlin/dev/folio/mobile/core/backup/BackupFilenameTest.kt` | New: filename parity with the PWA contract + collision-suffix stays a valid `.zip` name. |

Not changed (deliberately): scopes (`DriveScopes.DRIVE_FILE`), the Drive mirror folder contract (`"Folio Library"`, `relPath`/`sha256` app properties), backup ZIP schema, WorkManager constraints, restore merge/replace rules.

## 4. UI outcomes

- **Settings order** (unchanged, intentional): Reading → Backup → Cloud sync → Storage → About; each exactly once; `Scaffold` padding + `imePadding` + bottom content padding keep About reachable and above system navigation.
- **Theme selector**: `EdgeFade` only appears while the row actually overflows, so a wide device shows no misleading fade; narrow devices show a soft fade where content continues, and the System card can be scrolled fully into the group's content box — no half-broken clipping.
- **Slider**: thin quiet track, diamond thumb aligned to the track, value text still rendered by callers next to the label. No new decoration; thumb 13dp visual within a 48dp control (touch target preserved by the M3 shell).
- **Input sheets**: title/field/actions grouped; Cancel and Create sit together; keyboard no longer covers them.

## 5. Sync diagnosis

| Item | Detail |
| --- | --- |
| Original exception/category | Any `IOException` from the Drive request chain was mapped to `SyncFailureKind.Offline` → status "Offline" even for auth/permission/local failures. |
| Why it happened | Broad `error is IOException -> offline()` branch sat after only a few specific checks; Google API failures, transport setup, and local file I/O all flow through IOException subclasses. |
| Change made | Cause-chain semantics-based classification with correct HTTP status handling; neutral unknown-I/O copy; safe category/class-name logging; attention state surfaced on resume. |
| Test evidence | 17 mapper tests pass (statuses are constructed directly via the public `GoogleJsonResponseException(Builder, GoogleJsonError)` constructor — no mock server). `SyncManagerTest` + all other suites green. |
| Not reproducible here | A real device + real Google account + Drive consent is required to run an actual manual sync. Not performed in this environment → **Blocked**; see manual checklist. If genuine network failures still surface as something unexpected on-device, the new category/class-name log line (`Manual sync failed: kind=…, thrown=…`) is the first evidence to collect. |

## 6. Backup / restore validation (local ZIP ≠ cloud sync)

- **Distinct concepts preserved**: local export/restore writes/reads `folio-backup-*.zip` through `BackupRestore` (verified staging, merge/replace rules untouched); cloud sync mirrors per-file through `DriveSyncProvider`/`SyncManager`. No messaging conflates them.
- Verified by reading + tests (code-level):
  - Export: streaming per-entry writer (verified by existing `BackupRestoreTest`/`BackupRestoreStreamingTest`), temp+rename atomicity, handles closed via `use`, serial suffix collision safety — **Passed** (code review + new filename test; on-device export covered in checklist).
  - Restore: invalid/corrupt/pre-traversal archives fail verification **before** any mutation (` fatal` issues throw; `BackupRestoreTest` covers corrupt/unsafe/missing-manifest); merge vs replace clearly labelled in the confirmation dialog; restore runs under the library file locks; streamed from disk (no RAM archive).
  - Partial failure cannot report success: the summary/verification pair is produced from the restore result; UI shows warnings if `!verification.valid`.
  - Search index: `SyncManager.rebuildSearchText` (sync) and restore's PWA contract keep index behavior unchanged.

## 7. Tests / build / lint

| Command | Result | Exit |
| --- | --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest --offline` | **Passed** — 42 suites, 266 tests, 0 failed/skipped | 0 |
| `.\gradlew.bat :app:assembleDebug --offline` | **Passed** — APK `app/build/outputs/apk/debug/app-debug.apk` | 0 |
| `.\gradlew.bat :app:lintDebug --offline` | **Passed** — pre-existing `OldTargetApi` (targetSdk 35) and `UnusedAttribute` (enableOnBackInvokedCallback) warnings only; no new issues | 0 |

Baseline run before changes also passed (same JDK note). No emulator/device tests are available in this environment; none were claimed.

## 8. Manual device checklist

1. Install the fresh `app-debug.apk` over the existing build, open Settings: each section appears once; Cloud sync lives once between Backup and Storage; scroll to About; content clears the nav bar.
2. Settings → Reading: scroll the theme row; the trailing fade shows while content continues; confirm “System” can be scrolled fully visible and select all five options.
3. Reader → Appearance sheet: drag each slider to min/mid/max; tap the track; confirm the value text and the persisted value (close/reopen the book — content position must not jump).
4. New collection with keyboard open: field and Create remain visible (short device); whitespace name → Create disabled; Done creates exactly one collection; Cancel/Back/outside create nothing.
5. Export a backup → confirm `folio-backup-YYYYMMDD-HHMM.zip` in the backups folder; two exports in one minute must not overwrite.
6. Restore Reject an invalid/corrupt ZIP → clear error, library unchanged.
7. On Wi-Fi with a signed-in Google account: tap **Sync now**; grant Drive consent on first run. Observe the sandbox status and the message row. If it fails, capture the snackbar text plus `adb logcat -s FolioCloudSync` (category + exception class only) as root-cause evidence.
8. Enable Auto-sync, force a background failure (airplane mode) → retry behavior; then remove the account from Android Settings and reopen Folio Settings → auto-sync must switch off and the reason must surface.
9. Manual sync on a **metered** network must still run (worker constraints must not gate the manual path).

## 9. Remaining issues (actual, unresolved)

- **Real Drive end-to-end round-trip**: needs device + credentials (items 7–9 above). Everything compile/fake/mail-level that could be verified has been; the real-network classification can only be confirmed on-device.
- `LocalLifecycleOwner` deprecation warning in `CloudSyncSection` (pre-existing; moved to `androidx.lifecycle.compose` in newer lifecycle) — left untouched to avoid scope creep.
- Pre-existing lint notes: `targetSdk 35` vs latest, `enableOnBackInvokedCallback` unused below API 33 (harmless).
- Restore atomicity: the PWA-contract restore applies the archive under file locks but is not a single-transaction snapshot; a crash mid-restore can leave a merge/replace partially applied. Documented here accurately — changing that would break the established format/contract, so it was not altered.
