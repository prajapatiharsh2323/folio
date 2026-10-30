# Current Session Checkpoint

> This file is the recovery anchor. After every meaningful milestone, update:
> date, verified state, what changed, blockers, and the **exact next action**.

- **Last updated:** 2026-10-30 (session M-26 — Settings reduced to
  app-management; old root APKs removed)
- **Last verified project state:** the M-26 commit (on M-25's Phase 4
  `144bff5`). Verified in THIS VM: **336/336 unit tests, lintDebug 0 errors
  (57 warnings + 6 info = pre-existing noise), assembleDebug green**
  (33.3 MB APK). Device journeys NOT verified (no device/KVM) — user-side.
  User-test APK at repo root:
  `folio-mobile-debug-2026-10-30-m26-settings.apk`
  (sha256 `da83a7a86146bdddb13ed1a5d4adcca47df3c252739b7f712a982dafd40a40d9`).
  Same rule as every build (locked decision 1): uninstall the previous APK
  first, never install over it.
- **This session's changes** (full detail: `.docs/results/M26-settings-reduction.md`):
  - New `ui/theme/FolioAppTheme.kt` process-wide app-theme holder; picking
    System/Light/Dark in Settings now re-themes the visible session live
    (previously only the indicator moved and the Library under Settings kept
    the old palette). MainActivity/NotesActivity/SettingsActivity consume it.
  - `SettingsActivity` reduced to three groups: Appearance (theme picker +
    appearance-corrupt banner), Your library (export / restore with the
    unchanged merge/replace + staged-export machinery, one tap-to-retry
    "Library storage" row), About (version). Reading defaults left Settings;
    its UI/writer plumbing deleted. `PrefsStore`, `DocPrefsStore`, backup
    include-list, recovery preservation: untouched.
  - Reader sheet: "Set as default for future books" (confirm dialog →
    `saveReadingPrefs` of effective prefs) is now the global-defaults
    editing surface.
  - Repo hygiene (explicit user authorization): all 6 older repo-root APKs
    removed; only the M-26 APK remains.

## Blockers

- No device/emulator: never claim device verification. The M-26 device checks
  (live re-theme across screens, picker contrast both themes/fonts, reader
  set-default flow, regression matrix) are user-side.
- VM: run Gradle with `-Dorg.gradle.jvmargs="-Xmx2304m
  -XX:MaxMetaspaceSize=640m" -Dorg.gradle.parallel=false` and
  `GRADLE_USER_HOME=/tmp/opencode/gradle-home` (7.8 GB 2-core box; `/home`
  small and ~91% full). SDK at `/tmp/opencode/android-sdk` (provisioned this
  session: platform-35 + build-tools 35.0.0); re-provision on any fresh VM.

## Exact next action

1. User installs `folio-mobile-debug-2026-10-30-m26-settings.apk` and tests:
   theme pick re-themes immediately and persists across process death;
   Settings shows only the three groups with no duplicated reader controls;
   backup export + merge/replace restore still work; reader appearance sheet
   "Set as default" records defaults a fresh book opens with; light + dark
   + font scales look right. Report journeys back; never mark device checks
   passed from this VM.
2. Next substantive work = user's pick from `.docs/TASKS.md` "Active / next":
   restore `mobile-ci.yml` (P1), font-packaging decision (P2, own session),
   Play hardening (M6), repo hygiene leftovers.
