# Current Session Checkpoint

> This file is the recovery anchor. After every meaningful milestone, update:
> date, verified state, what changed, blockers, and the **exact next action**.

- **Last updated:** 2026-10-31 (repo cleanup + GitHub deployment-readiness)
- **Last verified project state:** code UNCHANGED — repo is exactly the M-26
  commit (`9c17568`). Verified in THIS VM (fresh VM, SDK re-provisioned
  this session): **336/336 unit tests, lintDebug 0 errors, assembleDebug
  green** in one run (`BUILD SUCCESSFUL in 20m 5s`, exit 0. Gradle flags per
  PROJECT_CONTEXT VM constraints; `/home` was 99% full, ~276 MB stale
  `mobile/app/build` + `.gradle` cleaned first).
  Rebuilt APK `app-debug.apk` sha256
  `cdef5617eb8f74d4ce8d5e42f1517c3912345db78665b0880613d83c4b27010d`,
  also attached to GitHub Release `v0.2.0-m26` as
  `folio-mobile-debug-2026-10-31-m26-settings.apk`.
  Device journeys NOT verified (no device/KVM) — user-side. Same rule as
  every build (locked decision 1): uninstall the previous APK first.
- **This session's changes (repo cleanup, executed 2026-10-31; user-approved
  plan; matches pre-approved backlog in TASKS.md "Repo hygiene" +
  `improvements/README.md` 1 & 3):**
  - **Deleted from working tree** (recoverable from git history, never
    rewritten): `PRD.md`, `docs/{spec,guide,decisions,mobile-cloud-sync}.md`,
    `this.md`, `.prettierrc`, `design/folio-icon/tmp/` (26 scratch files).
  - **Untracked, kept on disk:** `folio-mobile-debug-2026-10-30-m26-settings.apk`
    (33 MB) → shipped via GitHub Release instead; `.agents/` (88 skill
    files) → now gitignored-local.
  - Root `.gitignore` rewritten: PWA-era entries dropped; adds `/*.apk`,
    `.agents/`, OS junk; keeps `.docs/logs*` / `.docs/*.log`.
  - `README.md` rewritten for the actual Android product (features verified
    against `mobile/` code: 4-color highlights, annotations, dictionary,
    SHA-256 ZIP backup, search, collections/tags; NO markdown export — that
    was PWA-era). Points to GitHub Releases for install.
  - `AGENTS.md`: stale `desktop-docs/` references reworded (folder deleted
    by user in `9058c8e`; port paused per TASKS.md).
  - No CI artifact / LICENSE deliberately (user's call, 2026-10-31).
  - `.docs/` untouched (historical references to deleted files remain by
    policy — pointers are correct in AGENTS.md).
- **GitHub:** `github.com/prajapatiharsh2323/folio` — release workflow is now
  "attach dated APK to a Release" (repo-root `/*.apk` stays untracked).

## Blockers

- No device/emulator: never claim device verification. Device checks are
  user-side.
- VM: run Gradle with `-Dorg.gradle.jvmargs="-Xmx2304m
  -XX:MaxMetaspaceSize=640m" -Dorg.gradle.parallel=false` and
  `GRADLE_USER_HOME=/tmp/opencode/gradle-home` (7.8 GB 2-core box; `/home`
  small — free stale `mobile/app/build` before building at >93% full).
  SDK at `/tmp/opencode/android-sdk` (re-provisioned this session:
  platform-35 + build-tools 35.0.0); re-provision on any fresh VM.

## Exact next action

1. User side: device-test `folio-mobile-debug-2026-10-31-m26-settings.apk`
   from the release (or the identical M-26 APK) — same M-26 journeys as
   recorded in `.docs/results/M26-settings-reduction.md`.
2. Ongoing policy: future milestones attach APKs to Releases
   (`gh release create vX-mN-…` with dated asset); don't re-track APKs.
3. Next substantive work = user's pick from `.docs/TASKS.md` "Active / next":
   restore `mobile-ci.yml` (P1), font-packaging decision (P2, own session),
   Play hardening (M6).
