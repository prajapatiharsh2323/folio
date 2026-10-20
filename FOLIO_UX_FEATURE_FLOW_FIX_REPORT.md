# Folio Android — UX, Feature Flow and Cloud Sync Fix Report

**Implementation branch:** `ux/cloud-notes-settings`  
**Base:** `4a96a0e` (`release: Android v0.2.0 with Google Drive cloud sync`)  
**Module:** `mobile/` (native Android)  
**Date:** 2026-10-20

---

## 1. Executive summary

This pass fixes the highest-impact trust and flow defects in the native Android
app without rewriting the EPUB engine, changing serialized preference keys,
altering the backup format, or broadening the Drive OAuth scope.

The main outcomes:

- Google account selection now uses Android's supported system account chooser;
  account discovery failures are no longer silently reported as "no accounts".
- Cloud sync has typed user-facing failure states, a shared foreground/worker
  lock, correct consent handling, and no fake "synced" confirmation.
- A selected text highlight or note is persisted once, then the selection closes;
  the redundant second annotation sheet is gone.
- Existing annotations are saved/deleted with the sheet kept open on failure so
  notes are never discarded silently.
- Notes has loading/error states, guarded mutations, accurate filter semantics,
  and a less crowded row hierarchy.
- Settings scrolls, serializes preference writes, separates library/app/free
  storage values, and no longer uses a stock, awkward system-theme swatch.
- Reading appearance uses a reusable Folio slider with a 48 dp target and live
  value text while dragging.
- The visible Drive folder and `drive.file` scope are retained. UI and docs now
  state the real per-OAuth-client limit instead of promising PWA ↔ Android
  mirroring.

Automated validation passed: **249 unit tests**, `assembleDebug`, and
`lintDebug`. No device or emulator was available, so runtime/visual checks remain
outstanding (see §11).

---

## 2. Scope, constraints and non-goals

**In scope**

- Google account selection and Drive consent flow.
- Cloud sync error mapping, stale-account handling, and worker cancellation.
- Selection highlight/note save flow and existing annotation edit/delete.
- Notes screen loading, errors, filters, mutations, and row density.
- Settings scrolling, writes, storage reporting, and appearance controls.
- Accessibility of the new controls.
- Documentation and this report.

**Deliberately unchanged**

- EPUB parsing/rendering and reader WebView bridge.
- Annotation, progress, collection, manifest and preference schemas.
- Serialized preference keys (`warm`, `lh`, `mx`, `flow`, etc.).
- Backup ZIP format and merge/replace rules.
- `drive.file` scope and the visible `Folio Library` folder design.
- No new Play Services sign-in/Picker dependency.

**Known product limitation (not introduced by this pass)**

`drive.file` is per-OAuth-client. Android and the PWA each see only the files
their own client created. Cross-client Drive visibility was **not** implemented
and is not promised in UI copy. The backup ZIP remains the web ↔ Android bridge.
If cross-client mirroring becomes a hard requirement, Google Picker is the narrow
future route; broadening to `drive` is not an acceptable quick fix.

---

## 3. P0 — Google account selection and consent

### Problem

- The old flow tried to enumerate Google accounts and converted any
  `SecurityException`/query failure into an empty list, making a permission
  problem look like "no Google account on this device".
- The chooser and the Drive permission grant were conflated in one interaction.
- A saved account could disappear without the UI noticing.
- Selecting a different account could reuse Drive file IDs belonging to the old
  account.

### Changes

`core/sync/DriveAuth.kt`

- Selection now uses `AccountManager.newChooseAccountIntent(...)` (the supported
  7-argument API), launched with
  `ActivityResultContracts.StartActivityForResult()`.
- Added a typed `SavedAccountState`:
  - `NotConnected` — no saved account;
  - `Connected(name)` — account is visible;
  - `Missing(name)` — saved account genuinely absent;
  - `Unverified(name)` — account visibility could not be checked.
- A query/permission failure maps to `Unverified`, never to "no accounts".
- `connect(account)` reports whether it replaced a different account.
- `disconnect()` clears only the local selection; the Drive copy is untouched.
- KDoc and the manifest comment now describe the actual `drive.file` contract and
  the system-chooser flow.

`ui/sync/CloudSyncSection.kt`

- "Connect Google Drive" opens the system picker. Failure to launch falls back to
  the system "add account" screen, then to clear inline guidance.
- Result validation checks `RESULT_OK`, the returned account name, and the Google
  account type before saving anything.
- Changing accounts deletes the local `sync/state.json` mapping so Drive IDs from
  the previous account cannot be reused.
- Changing/disconnecting an account disables auto-sync until explicitly re-enabled.
- The saved account is revalidated on `ON_RESUME` (for removal in Android
  Settings), and missing accounts disable auto-sync with a reconnect message.
- Drive consent is requested on the first sync. A denied consent leaves the UI in
  "Needs Drive permission", not "Synced".
- `UserRecoverableAuthIOException` is caught before generic errors and its consent
  `Intent` is launched.
- The account row's state is explicit: Not connected / Ready / Syncing / Synced /
  Needs permission / Offline / Needs attention.

**No success is shown before persistence.** The UI says "Synced" only after
`SyncManager.sync()` returns.

---

## 4. P0/P1 — Cloud sync trust, failures and concurrency

### New failure model

`core/sync/SyncFailure.kt` adds `SyncFailureKind` and `SyncFailureMapper`:

| Kind | User copy | Retryable |
|---|---|---|
| `AuthRequired` | Needs Drive permission | No (needs UI consent) |
| `PermissionDenied` | Drive denied access | No |
| `AccountMissing` | Google account no longer available | No |
| `Offline` | You're offline | Yes |
| `RateLimited` | Google Drive is busy | Yes |
| `Server` | Google Drive is having trouble | Yes |
| `Storage` | A required file could not be read | No |
| `Unknown` | Sync didn't finish | Yes |

- `401` → `AuthRequired`; `403` → `PermissionDenied` unless rate-limited;
  `404` → permission/access message; `429` → `RateLimited`; `5xx` → `Server`.
- Raw exception text is logged, never shown to the user.
- `CancellationException` is rethrown by both foreground and worker paths; a
  cancelled sync is never presented as a failure or a success.

### Worker behavior

`core/sync/CloudSyncWorker.kt`

- Missing account/credential: records an attention message, disables auto-sync,
  and returns failure — no silent success.
- Auth/permission/account failures: disable auto-sync (a background worker cannot
  show consent) and record the reason for Settings.
- Offline/rate/server failures: `Result.retry()` up to 3 attempts, then record
  attention and fail.
- Successful sync clears the previous attention message.
- Cancellation is propagated to WorkManager.

`CloudSyncScheduler` now owns `isEnabled`, `setEnabled`, `schedule`, `cancel`,
`attentionMessage`, `markNeedsAttention`, and `clearAttention`.

### Concurrency

`SyncManager.sync()` is wrapped in a process-wide `Mutex` (`runLock`), so a
foreground sync and the periodic worker cannot run the multi-file transaction at
the same time. Per-file `LibraryManager` locks still protect individual writes.

---

## 5. P1 — Annotation and note flow

### Selection → highlight/note

Before: the selection toolbar saved with `openMenuAfter = true`, then opened a
second edit sheet. The UI implied two saves for one action.

After (`ReaderActivity.saveSelectionHighlight`):

1. The captured selection is converted to an annotation and persisted once.
2. Only after storage succeeds does the reader:
   - add the decoration,
   - clear the selection,
   - close the selection sheet,
   - reset its state,
   - show "Highlight saved" or "Note saved".
3. If storage fails, the sheet stays open with the user's text and an inline
   "try again" error.
4. Duplicate taps are guarded by `selectionMutation`; an already-saved annotation
   is not saved twice.
5. A blank note is stored as `null`, preserving the existing schema semantics.

### Existing annotation edit/delete

`AnnotationMenu.kt` is now a shared, accessible sheet implementation:

- One sheet header and quote preview.
- 48 dp selectable color swatches (`selectableGroup`, `Role.RadioButton`,
  content descriptions: "Yellow highlight", etc.).
- Multiline note field.
- Saving and error states; the sheet remains open during a save.
- Delete also remains open until persistence succeeds.
- "Remove note" saves a null note explicitly.
- Share is kept as a secondary action.

`ReaderActivity` now persists edit/delete before updating the WebView decoration,
rather than optimistically closing the sheet. Errors keep the user's text and
offer retry.

---

## 6. P1 — Notes screen

`core/annotation/AnnotationFilters.kt` applies documented, test-covered semantics:

- **All** — every annotation.
- **Highlights** — highlight/note records that carry a selected passage.
- **Notes** — any record with non-blank note text, including a highlight with a
  note (intentionally appears in both Highlights and Notes).
- **Bookmarks** — saved reading locations.

`NotesActivity.kt` now has:

- An explicit loading state (`CircularProgressIndicator`).
- A load-error state with "Try again" instead of an empty list.
- Mutation guards that prevent double delete/save.
- Delete confirmation that stays open and shows an inline error on failure.
- Edit-note dialog that keeps text and stays open when saving fails.
- Snackbar confirmations only after successful storage.
- A calmer row: color rail, book title, quote, note, and one overflow menu
  ("Edit note" / "Delete") instead of two always-visible icon buttons.
- Raw annotation-kind labels were replaced with readable metadata
  ("Has note" / "Bookmark").
- Filter chips are selectable tabs with selected semantics.
- An honest empty-filter state rather than a blank list.

---

## 7. P1/P2 — Settings

`SettingsActivity.kt`

- The whole screen is now a `LazyColumn` with bottom content padding, so all
  sections scroll on small screens and with large font sizes.
- Reading, Backup, Cloud sync, Storage and About are separate groups with one
  `FolioSectionHeader` per group.
- Theme selection is a horizontal row of preview swatches:
  - `light`, `warm`, `dark`, `true-black`, `system`;
  - `system` shows a split light/dark preview;
  - each swatch is a `Role.RadioButton` with selected semantics;
  - labels use user-facing names while serialized keys stay unchanged.
- One concise scope note replaces the old verbose appearance explanation:
  "Default for new books. Changes made inside a book stay with that book."
- Preference writes are serialized through a single `Channel` writer:
  - rapid taps cannot land out of order;
  - a failed write reverts only when no newer edit is queued;
  - failures surface as a snackbar.
- Storage now distinguishes:
  - **Library size** — `filesDir/library`;
  - **Folio data** — all of `filesDir`;
  - **Free space** — `StatFs.availableBytes`.
- Sizes use a new `formatBytes` utility (`B` / `KB` / `MB` / `GB`).
- Backup errors are friendly and specific; "Working…" is no longer mixed into
  the About row.
- Restore keeps the merge/replace choice and uses the existing verified
  `BackupRestore` path.

`FolioSettingsGroup` gained an optional `modifier`, allowing the screen to apply
the 20 dp horizontal inset consistently.

---

## 8. P2 — Reading appearance and FolioSlider

`ui/components/FolioSlider.kt`

- Reusable Material 3 slider wrapper with:
  - 48 dp touch target;
  - 6 dp track and 22 dp thumb;
  - semantic color roles from the app theme;
  - optional content description for TalkBack;
  - native drag, keyboard and accessibility semantics preserved.
- The caller owns persistence timing; sliders commit on
  `onValueChangeFinished`, not on every drag frame.

`reader/ReadingAppearanceSheet.kt`

- Text size, line spacing, margins and brightness now use `FolioSlider`.
- Text size shows the live value while dragging; it no longer displays the stale
  persisted value.
- Plus/minus update the local value immediately.
- Theme swatches are selectable radio buttons; `system` has a split preview.
- No stock Material slider styling remains in this sheet.

---

## 9. Accessibility

- Account picker and Drive consent are system UI (TalkBack-friendly by default).
- Color swatches expose a selectable group, selected state, radio role, and
  color names.
- Theme previews expose radio semantics and selected state.
- All new primary actions retain at least a 48 dp target.
- Notes filter chips expose tab/selected semantics.
- Dialogs keep their titles, buttons and error text in the accessibility tree.
- Progress indicators are paired with textual state ("Syncing", "Saving…").
- Errors are inline text, not color-only signals.
- Settings/Notes content scrolls, so large font scales do not clip controls.

---

## 10. Compatibility, persistence and documentation

**Preserved**

- EPUB engine and reader bridge.
- `Annotation`, `Progress`, `Collection`, `Manifest` schemas.
- Preference keys and `PrefsStore` serialization.
- Backup ZIP layout and verified restore.
- `DriveScopes.DRIVE_FILE`; no scope broadening.
- Visible `Folio Library` folder.

**Documentation updated**

- `docs/mobile-cloud-sync.md` now states:
  - selection uses the system chooser;
  - account selection and consent are separate steps;
  - the PWA uses a separate OAuth client and cannot see Android-created files;
  - `drive.file` is per-client;
  - backup ZIP is the cross-client bridge.
- `AndroidManifest.xml` comment now explains that `GET_ACCOUNTS` is only for
  revalidating a previously chosen account.

---

## 11. Validation, limitations and rollout

### Automated validation (run on this branch)

Environment: JDK 17 (`C:\hostedtoolcache\windows\Java_Temurin-Hotspot_jdk\17.0.20-101\x64`),
Gradle 8.10.2.

| Command | Result |
|---|---|
| `.\gradlew.bat :app:testDebugUnitTest --console=plain` | **PASS** — 249 tests, 0 failures, 0 errors, 0 skipped (40 suites) |
| `.\gradlew.bat :app:assembleDebug --console=plain` | **PASS** — debug APK produced |
| `.\gradlew.bat :app:lintDebug --console=plain` | **PASS** — 0 lint errors (67 warnings, mostly pre-existing dependency/Log/Timber notices) |

New/updated tests cover:

- annotation filter semantics, including highlight-with-note in both filters;
- byte formatting for B/KB/MB/GB;
- sync failure classification, retryability, and no raw exception leakage;
- existing sync, reader, backup, importer and library suites.

### What was not verified

No Android device or emulator was available (`adb devices` empty; no AVDs
installed). Therefore this report does **not** claim runtime verification for:

- the actual Google account chooser UI on a device;
- real OAuth consent/token refresh against Google Drive;
- background WorkManager execution under Doze/battery constraints;
- TalkBack traversal on hardware;
- visual regression at all font scales/themes;
- Android ↔ PWA Drive visibility (also impossible under `drive.file` by design).

These are the remaining checks before release. Recommended device matrix:
Android 8 (API 26), Android 11 (account visibility behavior), Android 14/15
(scoped storage/large fonts), with and without a Google account, online/offline,
consent denied/allowed, account removed while Settings is open, and a full
two-device sync round-trip.

### Rollout notes

- Merge this feature branch into `main` only after a device smoke test.
- Do not force-push; rebase on `origin/main` if it moves.
- No migration is required: storage paths, schemas, preference keys and backup
  format are unchanged.
- If a user previously connected a now-removed account, the UI will mark it
  missing and disable auto-sync; reconnecting is a one-tap system picker flow.
