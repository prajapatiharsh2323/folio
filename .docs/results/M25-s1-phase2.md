# M-25 — S1 Phase 2: Settings rebuild (2026-10-29)

> Executes Phase 2 of `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`
> (after Phase 1 landed the sync-free app: `.docs/results/M25-s1-phase1.md`).

## What was built

1. **App appearance — its own store (`core/store/AppAppearanceStore.kt`).**
   System/Light/Dark for Folio's own screens, default System, at
   `app-appearance.json` (filesDir root — deliberately outside `library/` and
   outside the schema-1 backup contract: the include-list enumerates library
   paths only, so it never travels in a backup and desktop/PWA user-JSON
   shapes are untouched). Same data-safety pattern as `PrefsStore`: corrupt
   file surfaces as an explicit state and `save()` preserves the unreadable
   bytes under `library/recovery` (verified byte-identical) or aborts.
   `loadThemeSync()` gives each activity's `onCreate` a one-read head start so
   the first frame carries the stored choice, no system→stored flash; corrupt
   resolves to System at startup (honest banner lives in Settings).
   Wired: MainActivity, NotesActivity, SettingsActivity (its own screen
   re-themes live on tap). `ReaderPrefsMapper.themeColors()` now exposes the
   PWA-tokens verbatim map for previews (single source, previously private).
2. **Reading defaults — the full editor.** Controls extracted from
   `ReadingAppearanceSheet` into a shared `ui/components/ReadingPrefsEditor`
   (Theme · Text size · Typeface · Line spacing · Page margins · Alignment ·
   Reading flow · Page dimming) — the reader sheet and Settings render one
   identical control set; the sheet keeps its header/per-book reset. Settings
   adds a page preview with the REAL reader mapping (canvas+ink+live size/
   lineHeight/spacing/letterspacing/alignment from `ReaderPrefsMapper`).
   Persist on commit only: sliders preview through a conflate-backed display
   stream while onValueChangeFinished drives a single ordered WRITE — no
   write storm. "Reset reading defaults" is a separate confirm-guarded action
   (factory values for new books; per-book overrides untouched), with the
   "changes made inside a book stay with that book" guidance note.
3. **Backup & restore rows fixed for real exports (P1 finding).** Export now
   goes through CreateDocument("application/zip") — the user picks the
   location; staging stays verified+atomic (temp `.part` in cache → full
   stream + flush + fsync → streamed copy to the content URI → temp deleted).
   Restore picker unchanged, with the include-list named in the dialog copy.
4. **Storage & recovery.** Honest rows (Library use / Folio data / Free
   space), a new "Recovery copies" row (count of first-aid copies under
   `library/recovery`; "None" is not an error state) and the retry row on
   storage failures.
5. **Persistent corrupt banners (corruption ≠ first-run empty).** Unreadable
   reading-settings or appearance store = explicit errorContainer banner
   naming what survived and the recovery route; banners clear when a verified
   save replaces the corrupt file. Editor stays usable.
6. **Visible back/up** in the Settings TopAppBar (ArrowLeft → finish()).
7. **Tests:** new `AppAppearanceStoreTest` (8): missing→System; round-trip of
   all three keys; invalid-key rejection writes nothing; JSON carries the one
   appTheme field; isDark mapping matrix (incl. null/bogus fallback);
   corrupt→both typed Corrupt + throw-on-load; verified byte-identical
   preservation on save-over-corrupt; preservation-failure aborts the save
   with the corrupt source untouched (fault-injected recovery store).

## Verification (this VM)

- **Gates green: 329/329 unit tests** (was 321 after Phase 1; +8, 0 failures),
  **lintDebug 0 errors** (61 warnings + 7 info, pre-existing noise class),
  **assembleDebug green** (41.9 MB APK).
- Not run on device (no emulator/KVM) — always user-side.
- User-test APK at repo root: `folio-mobile-debug-2026-10-29-p2-settings.apk`
  (sha256 `3848ac60…aa55f2`). Same uninstall-first rule (locked decision 1):
  uninstall the 2026-10-27 sync APK before installing, regardless of build.

## Deliberately not done here

- Reader recovery (restore-from-savedInstanceState) → Phase 3.
- PDF dead-code removal → Phase 4 (user decision 2), not part of Settings.
- App appearance is intentionally a device-local, non-backed-up file; do NOT
  "fix" it into settings.json (byte-compat risk, no user value).

## Next

S1 Phase 3 — reader recovery & comfort (see S1 audit §Phase 3).
