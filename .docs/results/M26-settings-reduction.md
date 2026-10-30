# M-26 — Settings reduction to app-management + app-level theme propagation

**Date:** 2026-10-30 · **Session:** M-26 · **Gates:** 336/336 unit tests ·
lintDebug 0 errors (57 warnings + 6 info — pre-existing noise) ·
assembleDebug green. User-test APK:
`folio-mobile-debug-2026-10-30-m26-settings.apk`
(sha256 `da83a7a86146bdddb13ed1a5d4adcca47df3c252739b7f712a982dafd40a40d9`).
Device journeys NOT verified (no device/KVM) — user-side.

## What changed

A user-directed refinement of the S1 Phase 2 Settings screen: Settings becomes
a small app-management screen (Appearance / Your library / About); reader
configuration leaves the screen without touching the preference architecture.

1. **App-level theme propagation (new `ui/theme/FolioAppTheme.kt`).** All
   three theme-consuming activities (MainActivity/NotesActivity/
   SettingsActivity) used to snapshot `AppAppearanceStore.loadThemeSync()`
   into a plain val at `setContent` time, so actually picking Light/Dark in
   Settings only moved the segment indicator AND the Library under Settings
   kept the old palette on return. New process-wide snapshot-state holder:
   `ensure(FileStore)` once before `setContent` per activity (same blocking
   tiny read; no first-frame flash), activities read `FolioAppTheme.key.value`
   inside composition, Settings writes through `set(key)` + async
   `AppAppearanceStore.save()` (corrupt-safe semantics unchanged, honest
   corrupt banner kept, scoped to the appearance file).

2. **Settings IA reduced (SettingsActivity rewrite).** Three groups only:
   - **Appearance** — "App theme" + System/Light/Dark `ReadingSegment` picker
     + the app-vs-reader scope note.
   - **Your library** — Export backup / Restore backup (summaries shortened;
     merge/replace dialog, `.part`-staged export, busy gating, warning
     summary, and picker wiring kept byte-for-byte from S1 Phase 2), and a
     single read-only "Library storage" row (books/notes/progress/index
     bytes) that is tap-to-retry when the check fails. The Storage &
     recovery section (app-wide data, free space, recovery-copy count,
     retry row) is gone from the UI; `library/recovery` preservation,
     `RECOVERY_DIR`, and StatFs-based stores are untouched.
   - **About** — "Folio" + version + "Your personal reading space."
   Removed with the Reading defaults section: the settings-screen usage of
   `PrefsStore`/`ReadingPrefsEditor`/`ReadingPagePreview`, the conflated
   writes channel, the ordered-writer effect, `commitPrefs`/`persistedPrefs`,
   and the reset-defaults dialog. `PrefsStore`, `DocPrefsStore`, reader
   mapping, backup include-list (`settings.json` travels on export/restore
   as before) and per-book override precedence: all untouched. Reader-side
   corrupt warning (Toast + defaults-shown) continues to own reader-corrupt
   state.

3. **"Set as default for future books" (reader sheet).** Global reading
   defaults are still user-editable — from the reader's appearance sheet now:
   `ReadingAppearanceSheet` gained a quiet row ("New books without their own
   settings open this way" + Set as default) → confirm dialog ("replaces your
   saved reading defaults…", themed per the dictionary sheet's D1 contract) →
   `prefsStore.saveReadingPrefs(current effective prefs, including any
   per-book override)` on IO with success/failure toasts; `PrefsStore`
   preserve-corrupt-then-abort semantics apply automatically.

## Bug sweep

Findings fixed during implementation: a dropped `CorruptBanner` call-site in
the Settings rewrite draft and the reader-sheet parameter re-wiring (both
caught pre-build). Verified against source: `directoryBytes` non-suspend,
`ReadingSegment`/`FolioSettingsRow` contracts, `RefreshCcw` icon existence,
`BuildConfig` same-package resolution. No warnings from new code (only the
pre-existing Readium experimental-API set).

## Suite math

337 → no test added/removed by this change: 336 = same count as M-25 Phase 4
(334 − 6 PdfImporterTest + 8 DisplayNameFallback/NotesHeader). Unit tests
target the preserved stores/backups, not Compose UI; nothing in this change
removes or adds tested pure logic.

## Not verified here (user-side)

- Device checks: live re-theme of Library/Notes across the Settings change,
  picker contrast at both themes + font scales, reader "Set as default" flow,
  and the standard regression matrix (library/reader/dictionary/notes).
- Uninstall-before-install rule (locked decision 1) still applies.
