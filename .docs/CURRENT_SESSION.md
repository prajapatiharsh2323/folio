# Current Session Checkpoint

> This file is the recovery anchor. After every meaningful milestone, update:
> date, verified state, what changed, blockers, and the **exact next action**.

- **Last updated:** 2026-10-29 (knowledge-system build-out session)
- **Last verified project state:** `main` clean and up to date with `origin/main`
  (commit `ac6332f`, FIX-E release hardening, 2026-10-27). That commit was
  verified in its own session: 340/340 unit tests, `lintDebug` 0 errors,
  `assembleDebug` green. Device journeys remain unverified (no device/KVM in VM).
- **This session changed:** documentation only — created the `.docs/` operating
  layer (this KB: README, PROJECT_CONTEXT, TASKS, CURRENT_SESSION, sessions/,
  audits/, decisions/, improvements/, archive/), moved root reports into the KB
  with reference updates, restored `audits/ui-ux/EPUB_READER_AUDIT_REPORT.md`
  from git history (`d793212^` — it was deleted as PWA spill-over), wrote root
  `AGENTS.md`, fixed the stale PWA Quick Start in the root README. No app code touched.

## Blockers

- Android SDK missing at `/tmp/opencode/android-sdk` in this VM — gates cannot
  run until re-provisioned (or `sdk.dir` repointed). See `PROJECT_CONTEXT.md`.
- Device verification (user-side, APK manual round) still pending from FIX-E/D1.

## Exact next action

1. Verify this session's work: `git status` clean-ish (new + moved docs), review
   `git diff --cached`. Commit the knowledge system, push, and confirm the push
   output — do not attempt to push unless committing was authorized.
2. If authorized to build: install SDK 35 + build-tools via `sdkmanager`, then run
   `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` in
   `mobile/` and record results here.
3. Then pick the next task from `TASKS.md` (highest value: restore `mobile-ci`
   GitHub workflow or start the desktop port Phase 0).
