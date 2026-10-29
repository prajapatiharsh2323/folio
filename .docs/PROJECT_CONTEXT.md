# Folio — Project Context (verified 2026-10-29)

> Facts below were verified against the working tree on 2026-10-29.
> The Android app is the only product code. The PWA monorepo was deleted in
> commit `d793212` ("delete the pwa section and keep only mobile") — `PRD.md`,
> `docs/spec.md`, `docs/guide.md`, `docs/decisions.md` and the root `README.md`
> product description are PWA-era and kept as background, not as build truth.

## Product state

- **`mobile/`** — native Android app (Kotlin + Jetpack Compose + Readium),
  package `dev.folio.mobile`, `versionName 0.2.0` / `versionCode 2`. This is the
  shipped artifact (user manual-tests from debug APKs built at repo root).
- **`desktop-docs/`** — deleted from disk by the user (commit `9058c8e`,
  2026-10-29); the Tauri 2 + TS/React desktop-port plan (schema-1 compatible
  with the mobile app) remains recoverable from git history only. Treat the
  desktop port as PAUSED until the user re-instates specs; do not build from
  the old doc references.
- **Dropped from v1 scope**: TTS, PDF rendering/extraction, audio (user decision,
  2026-10-17 — post-v1 backlog, not debt). EPUB-first.

## Stack facts

| Fact | Value |
|---|---|
| Module | `:app` only (settings.gradle.kts: `rootProject.name = "folio-mobile"`) |
| Gradle | wrapper pins **8.10.2** — always use `./gradlew` in `mobile/`, not the host's Gradle 9.8 |
| Kotlin / AGP | 2.0.21 / 8.7.3; version catalog in `gradle/libs.versions.toml` |
| JVM | JDK 21 works locally (CI used Temurin 17) |
| SDK levels | minSdk 26, targetSdk & compileSdk 35 |
| Readium | **pinned 3.0.0** (shared/streamer/navigator only; no LCP). 3.1+ is Kotlin-2.1-compiled and cannot be consumed until Kotlin/AGP are upgraded — upgrading unlocks FontFamilyDeclaration + exact CFI restore |
| Tests | `unitTests.isReturnDefaultValues = true`; pure-logic JVM tests only, **no instrumented-test prerequisites in this VM** (no device/KVM) |
| Dictionary | offline FreeDict `eng-hin` pack, build script `mobile/tools/build_dict.py`, DB = framework SQLite in `app/src/main/assets/dictionary/`, no Room |

## Commands

Run inside `mobile/`:

```bash
./gradlew :app:testDebugUnitTest   # 340 tests, last all-green 2026-10-27 (FIX-E)
./gradlew :app:lintDebug           # 0 errors expected (warnings are pre-existing noise)
./gradlew :app:assembleDebug       # produces app-debug.apk
```

Command order when it matters: fix code → test → lint → assemble. All three
green = the milestone gate; then commit. A new debug APK for the user's
manual-testing round goes to the **repo root** with a date in the name.

## Environment quirks

- **Android SDK is VM-local**: `mobile/local.properties` (git-ignored) points at
  `/tmp/opencode/android-sdk`. On a fresh VM this path won't exist — install
  platform-35 + build-tools via `sdkmanager`, or update `sdk.dir` to the real
  location. Never commit `local.properties`.
- No emulator/device/KVM available: every DEVICE/Touch/haptics/Drive-runtime
  check is blocked here by design; the **user** validates via debug APKs. Don't
  claim device verification you didn't do (see
  `audits/bugs/FIRST_RELEASE_BUGFIX_REPORT.md` "blocked, not passed" §E pattern).
- `gh` CLI is authenticated (account `prajapatiharsh2323`) — older notes saying
  "gh unauthenticated" are stale.
- Git identity is configured in the CI/method notes; if a fresh VM loses it:
  `git config user.name "Harsh Prajapati"; git config user.email "harshprajapaticoding@gmail.com"` (repo-local).
- Emulator memory hazard (from M-21, Windows VMs): stop the Gradle daemon before
  booting an emulator on a 16 GB host (`./gradlew --stop`); otherwise ANR loop.
- **`/home` is a small separate disk (~4.8 GB)** in some VMs: Gradle caches
  (`~/.gradle/caches`) can fill it to 100% → builds fail with "No space left
  on device" or the daemon just disappears. Symptom signature 2026-10-29
  (M-25): crash-like failures that are actually disk-full. Fix:
  `rm -rf ~/.gradle/caches` (regenerable; deps re-download; keep wrapper).
  Keep big artifacts (SDK, temp outputs) on `/` or `/tmp/opencode` instead.

## Compatibility contracts (do not break)

Schema-1 data contracts are **byte-compatibility obligations** — any change must
keep both mobile (now) and the planned desktop port (later) agreeing, and keep
the migration fixtures honest:

- `manifest.json` shape, `user/*.json` (annotations, progress, collections) shapes,
- backup streaming ZIP format + SHA-256 verification + merge/replace semantics,
- annotation anchors (Readium Locator/CFI strings in `Progress.location`),
- Google Drive `drive.file` mirror protocol (`sync/state.json`, file properties,
  newer-metadata-wins, deletions-never-propagate) — **historical**: Drive sync
  was removed from the product (S1 audit decision 1, 2026-10-29); the shape of
  old `sync/state.json` files is still known and they are left untouched, and
  the protocol doc `docs/mobile-cloud-sync.md` is superseded in place.
- canonical shapes + invariants: `desktop-docs/05-data-model-and-compat.md`.

Timestamps: write via `IsoTime` (`core/util/IsoTime.kt`) — it exists because
`LibraryManager.nowIso` produced double-digit-millisecond `…947ZZ` stamps that
broke newer-wins comparisons (FIX-E, 2026-10-27). Corrupt-state policy: **never
overwrite unreadable user data** — preserve bytes under `library/recovery/` and
surface honest UI, per the fix sweep (`FIX-PLAN-TRACKING.md`).

## Code layout

- `mobile/app/src/main/kotlin/dev/folio/mobile/`
  - `core/` — library/manifest, import pipeline, backup/restore, search
    index, annotations, dictionary, reading prefs, util (pure logic; most tests here)
  - `reader/` — ReaderActivity, Readium wiring, chrome, appearance sheets,
    highlight manager, annotation menus
  - `ui/` — theme, components; MainActivity is the library/settings host
- `mobile/app/src/test/kotlin/dev/folio/mobile/` — unit tests + fixtures under
  `src/test/resources`; historical contract fixtures live in submodule folders.
- `mobile/tools/build_dict.py` — dictionary pack toolchain.
- `design/folio-icon/` — canonical adaptive icon package (work scripts under
  `tmp/`, eslint-ignored).
- PWA-era leftovers at repo root: `.prettierrc`, `.gitignore` entries, `this.md`
  (15-byte stub) — safe to ignore, don't build from them.

## Where the truth lives (and what's stale)

| Source | Status |
|---|---|
| `MOBILE-TRACKING.md` | **Authoritative** for Android plan + decisions; session log M-1…M-24 |
| `FIX-PLAN-TRACKING.md` | Authoritative for 2026-10-26/27 fix sweep + evidence rows |
| `results/` | Authoritative summaries per change (verify before trusting old numbers) |
| `desktop-docs/` | Authoritative for desktop plan |
| `docs/spec.md`, `docs/guide.md`, `docs/decisions.md`, `PRD.md` | PWA-era background; product rules/PRD still useful for intent, not build commands |
| `SESSION-TRACKING.md` | PWA-era; Session 1-3 useful as history; env-commands stale (pnpm repo deleted) |
| Root README Quick Start | PWA-era (`pnpm …` doesn't apply) — 2026-10-29 edit points to the real commands |
