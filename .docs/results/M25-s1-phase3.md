# M-25 — S1 Phase 3: reader recovery & comfort (2026-10-29)

> Executes Phase 3 of `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`
> (after Phase 2 landed the Settings rebuild: `.docs/results/M25-s1-phase2.md`).

## What was built

1. **Process-death recovery (no more finish-on-restore).** When the OS kills
   and recreates the reader (`savedInstanceState != null`), the activity now
   REBUILDS instead of exiting:
   - `super.onCreate` still needs `EpubNavigatorFragment.createDummyFactory()`
     (the dead fragment state can only be re-instantiated through the dummy
     factory — the real constructor needs a live Publication), and ALL
     restored dummy/dead fragments are then removed
     (`commitNow(allowStateLoss = true)`), so navigation never lands on two
     dead fragments in the container.
   - The reusable intent (durable doc id) drives the same `loadAndAttach`
     path as a fresh open: bookmark/notes/search jumps first, then the saved
     progress locator (CFI spine chapter + percent-refined within-chapter
     offset, EPUB-010 partial), then percent-deep locator, then chapter one.
     Deleting/damaged docs keep the existing honest finish-with-message
     routes (missing doc / damaged file / unreadable manifest).
   - **Jump extras are stripped on recreation** (`EXTRA_TARGET_ORDER`,
     `EXTRA_TARGET_LOCATION`, `EXTRA_TEXT_*`): they were consumed by the
     killed instance; after a process death the user expects to resume from
     the persisted progress, not be teleported back to the old jump target.
   - `attachNavigator` additionally removes any stale fragment under
     `NAV_TAG` before the live one lands (belt-and-braces: never two
     navigators in the container).
   - Exact text-anchor CFI restore remains gated behind the Kotlin
     2.1.x → Readium 3.1.2 upgrade (TASKS.md font-packaging decision) —
     Phase 3 keeps the pinned 3.0.0 locateProgression path verified earlier.
2. **Restored-saved-state are dead-path fixes**: no behavior change in the
   normal cold-open path; the dummy factory is only installed when state is
   being restored.
3. **Chrome discoverability (one-time dismissible hint).** The center-tap
   chrome toggle was reported undiscoverable:
   - New `core/store/AppHintsStore.kt` — `app-hints.json` (filesDir root,
     outside `library/` and the schema-1 backup contract, like
     `app-appearance.json`). Hints are device-local noise, NOT user data:
     corrupt/missing file reads as "not shown yet" and the next write simply
     replaces it (the heavy PrefsStore preservation dance is deliberately
     NOT applied). `chromeHintDismissedSync()` gives `onCreate` the blocking
     pre-mount read (same pattern as `AppAppearanceStore.loadThemeSync`).
   - New `ReaderChromeHint` composable: a small chip above the bottom edge —
     "Tap the middle of the page to show the controls" — with a dismiss (X)
     and a tappable body that reveals the chrome at once. It appears once
     the reader is ready and the chrome is hidden, fades in/out (300ms),
     never blocks the reading area. Revealing the chrome once IS the
     discovery moment — the hint retires itself then (dismissal persisted
     off the main thread on the process-lifetime flush scope; a lost write
     re-shows the hint once, harmless).
4. **Tests:** new `AppHintsStoreTest` (5): missing file = not-shown;
   dismissal round-trips across store instances (simulates next session);
   corrupt file reads as "not dismissed" AND is recovered by the next
   write; `chromeHintDismissedSync` survives a corrupt file (never throws);
   JSON carries only the one hint field.

## Verification (this VM)

- **Gates green: 334/334 unit tests** (was 329 after Phase 2; +5, 0
  failures), **lintDebug 0 errors** (61 warnings + 7 info, same pre-existing
  noise class), **assembleDebug green** (42.4 MB debug APK).
- Fresh-VM preflight: Android SDK 35 + build-tools 35.0.0 re-provisioned,
  `GRADLE_USER_HOME=/tmp/opencode/gradle-home` (documented VM procedure).
- Device journeys NOT run (no device/KVM here) — the §4.6 reader regression
  matrix (process-death resume, 320dp/1.3×+ font scale, TalkBack, both
  app themes × 5 reader themes, center-tap vs selection/link taps,
  volume-key turns) is user-side; report blocked, never passed.
- User-test APK at repo root:
  `folio-mobile-debug-2026-10-29-p3-reader-recovery.apk`
  (sha256 `d6cffe7451574cadf22370b7255723084548105b5c4894016f26f58e315bde04`).
  Same uninstall-first rule (locked decision 1): uninstall the
  2026-10-27 sync APK before installing, regardless of build.

## Deliberately not done here

- Exact CFI restore → needs the Kotlin 2.1/Readium 3.1.2 upgrade decision
  (separate session; TASKS.md row).
- Library/notes finish + PDF dead-code removal → Phase 4 (user decision 2).
- The §4.6 reader regression matrix — device-only, user-side.

## Next

S1 Phase 4 — library/notes finish (displayName fallback, notes header states,
loading/empty/corrupt parity, filter-chip non-color affordance, PDFImporter/
PDFBox dead-code removal per user decision 2, import-limit honesty).
