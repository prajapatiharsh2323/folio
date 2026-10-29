# S1 — Settings / Cloud-sync / Reading-UX Audit (adopted 2026-10-29)

> Source: user-supplied audit report ("Folio Android — UI/UX, Settings & Reading
> Experience Audit"), delivered in conversation 2026-10-29 and verified against
> the working tree by session M-25 before implementation. The original full
> text is the user's; this file records the operative spec + locked decisions
> so a session with zero conversation memory can execute phases 1–4.
>
> All P0/P1 claims were re-verified in code: `core/sync/*` + Google deps in
> `gradle/libs.versions.toml`; `ReaderActivity` finish-on-restore
> (`savedInstanceState != null` → build dummy factory → `finish()`);
> `PrefsStore.preserveCorrupt` swallowing failures (FIXED, M-25);
> `BackupZip` readEntry buffering before cap check (FIXED, M-25); release
> signing debug-keystore fallback (FIXED, M-25).

## Locked decisions (user, 2026-10-29)

1. **Sync removal = one step.** No two-release WorkManager migration. The
   user uninstalls/reinstalls fresh on their device before installing the
   cleanup build (the old APK's WorkManager state dies with its app data).
   The 2026-10-27 debug APK on the user's device had sync enabled — the new
   build MUST NOT be installed over that APK without uninstalling first.
   Document this assumption in release notes. Never delete remote Drive
   copies or any library/user data.
2. **PDF = out of scope** (matches PROJECT_CONTEXT "PDF dropped from v1").
   Remove `PdfImporter`/PDFBox setup/dead resources after a reference sweep;
   UI copy states EPUB/HTML/TXT honestly.
3. **Signing = fail-safe.** `keystore.properties` is production; missing
   credentials refuse release tasks with a clear error; debug keystore only
   behind explicit `-Pfolio.localReleaseSigning=true` /
   `FOLIO_LOCAL_RELEASE_SIGNING=1`, never publishable. (IMPLEMENTED, M-25.)
4. **Cadence = phase-by-phase**, Phase 0 landed as its own verified milestone.

## Guardrails (non-negotiable)

- Keep ZIP backup/restore contract (schema-1, streaming, SHA-256, merge/
  replace semantics, atomic rollback). No casual contract changes.
- Keep offline-first; `allowBackup="false"` stays unless separately reviewed.
- Never silently discard or overwrite user data; corrupt file = preserve
  bytes + honest UI. Missing = valid first run, corrupt ≠ empty.
- Per-book prefs always win over reading defaults until the user resets.
- Do not expose dead controls (hyphenate/paragraphSpacing/letterSpacing/spread
  only with real renderer mapping + tests); `maxWidth`/`pdfZoom` stay legacy.
- Audit remaining `INTERNET` usages before removing it; remove
  `GET_ACCOUNTS` + `ACCESS_NETWORK_STATE` with the sync code.

## Priority findings (source-verified)

| Pri | Finding | Where |
|---|---|---|
| P0 | Release signing could fall back to debug key without credentials | app/build.gradle.kts (FIXED M-25) |
| P1 | Corrupt settings could be overwritten when preservation failed | core/store/PrefsStore.kt (FIXED M-25) |
| P1 | ZIP verified entries by buffering before the cap | core/backup/BackupZip.kt (FIXED M-25) |
| P1 | Reader exits on process recreation instead of restoring | reader/ReaderActivity.kt (~L349) |
| P1 | Export writes to app-specific dir + snackbar-only result | SettingsActivity export flow |
| P1 | Sync removal needs the stale-work consideration (DECIDED: one-step reinstall) | core/sync/*, FolioApplication |
| P2 | Settings exposes only default theme; no app-appearance setting | SettingsActivity, Types.kt |
| P2 | Notes header says "Nothing saved yet" on load error | NotesActivity |
| P2 | Import names from `Uri.lastPathSegment`, not OpenableColumns | MainActivity uriDisplayName |
| P2 | PDF code/deps exist but import contract rejects PDF | ImportDispatch, PdfImporter |
| P2 | Appearance-sheet preview colors follow app theme, not reader theme | ReadingAppearanceSheet |
| P2 | Reader chrome toggle not discoverable; center-tap conflicts | ReaderActivity |

## Phase plan (executed in order, each phase = gates + docs + push)

### Phase 0 — data safety & release gates. ✅ DONE (M-25, commit: see results)
1. PrefsStore preservation verified (exists + byte-identical) or save aborts;
   fault-injection tests (write fails / copy evaporates / copy mutates).
2. Streaming ZIP caps: `StagedArchive.readEntryStreamed`/`readEntryLimited`,
   incremental SHA-256, per-entry 512 MB + total 1 GB + metadata 16 MB +
   user-state 64 MB caps enforced on real decompressed bytes, injectable
   `BackupLimitsSpec` for tests. Zero-bomb + refused-replace tests.
3. Signing gate (see decision 3).

### Phase 1 — remove Drive sync (one step)
- Delete: `core/sync/{CloudSyncWorker,DriveAuth,DriveSyncProvider,SyncManager,
  SyncProtocol,SyncFailure}.kt`, `ui/sync/CloudSyncSection.kt`, sync tests
  (`SyncManagerTest`, `SyncFailureMapperTest`, `FakeSyncProvider`), Cloud sync
  UI in `SettingsActivity.kt`, CloudSyncScheduler (grep exact name), work
  scheduling in `FolioApplication`.
- Remove deps `androidx.work.runtime.ktx`, google-api-client/drive/http-gson,
  play-services-auth + their catalog aliases; remove `GET_ACCOUNTS`,
  `ACCESS_NETWORK_STATE`; `INTERNET` only after a full usage sweep; drop the
  Google META-INF packaging exclusions only after a clean build proves no
  dependency needs them.
  NOTE (M-25): since no old build will ever be updated in place (uninstall/
  reinstall decision), one-step removal is safe per user decision — document
  in release notes; no runtime migration code needed.
- Stale storage copy "…and sync state" → "Books, indexes, reader settings and
  local app data."
- Preserve `library/`, ZIP backup/restore, all recovery copies. ZIP suite must
  stay green after every removal batch.

### Phase 2 — Settings rebuild
- Landing IA: Appearance (app UI: System/Light/Dark, own store, default
  System) · Reading defaults (full editor extracted from
  `ReadingAppearanceSheet`, preview = real reader theme/ink mapping,
  global-vs-per-book copy, reset-defaults separate from per-book) ·
  Backup & restore (CreateDocument("application/zip") export with staged
  temp file + flush + copy; restore picker with preflight + Merge/Replace
  guardrails; include-list copy matches actual schema: doc payloads,
  manifest, annotations/progress/collections + global settings.json) ·
  Storage & recovery (fixed label, persistent corrupt-warning banners,
  guided recovery route) · About (version).
- Slider/reactive edits coalesce preview state, persist on commit (no write
  storm; bounded/ordered writer).
- Visible back/up in `TopAppBar`; settings corruption ≠ first-run empty.

### Phase 3 — reader recovery & comfort
- Reader must rebuild from durable doc id + persisted locator on
  `savedInstanceState != null` (no more finish-on-restore); handle
  deleted/damaged doc with a clear recovery message.
- Precise locator restore after auditing the pinned Readium 3.0.0 API
  (exact CFI restore is behind the Kotlin 2.1.x → Readium 3.1.2 upgrade —
  TASKS.md font-packaging decision).
- Chrome discoverability: one-time dismissible hint; keep 48dp slider.
- Run the §4.6 reader regression matrix on device (user-side; never claim).

### Phase 4 — library/notes finish
- `OpenableColumns.DISPLAY_NAME` with lastPathSegment fallback.
- Notes header from load state: Loading / "Saved items unavailable" /
  "Nothing saved yet" / "N items"; recovery route + back/up.
- Standardize loading/empty/corrupt/success states across screens; selected
  filter chips need non-color-only affordance.
- PDF decision implemented: remove dead PDFImporter/PDFBox (decision 2).
- Honest import limits (50 MB cap stays), resilient batch import states.

## Test/acceptance checklist (device = user-side, report blocked not passed)

Automated: full suite before/after each phase; app-appearance store tests;
defaults-vs-override tests; write-coalescing tests; preservation-failure
test (done); ZIP oversized/bad-CRC/aborted-stream tests (done); ZIP round-
trip/atomic/merge/replace streaming suites preserved (done, 350/350 after
Phase 0); Notes header state mapping tests; displayName fallback tests.

Device: 320dp + 1.3×/1.5× font scale, TalkBack, both app themes × 5 reader
themes (System on dark OS), export-to-chooser + restore clean install,
merge/replace destructive-confirmation flow, corrupt-state banners, no
Drive UI/work remains, center-tap vs selection/link taps, volume-key turns,
process-death resume.
