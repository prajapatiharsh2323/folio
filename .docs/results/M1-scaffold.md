# M1 — Folio Mobile Scaffold (Result Summary)

**Session:** M-1 · **Date:** Oct 17, 2026 · **Commits:** e88d3f7, b94b547 (pushed)

## What was built

Native Android scaffold in `mobile/` (fully separate from the PWA per user instruction):

```
mobile/
  settings.gradle.kts          pluginManagement + FAIL_ON_PROJECT_REPOS, root "folio-mobile"
  build.gradle.kts             plugin aliases (apply false)
  gradle.properties            4GB daemon, parallel, caching, androidx, nonTransitiveRClass
  gradle/libs.versions.toml    version catalog (pins below)
  gradle/wrapper/*             wrapper pinned to Gradle 8.10.2 (bin)
  gradlew / gradlew.bat
  app/
    build.gradle.kts           :app module (Kotlin 2.0.21, Compose via compiler plugin)
    proguard-rules.pro         placeholder (expanded at release hardening)
    src/main/
      AndroidManifest.xml      MainActivity exported, launcher intent
      kotlin/dev/folio/mobile/
        MainActivity.kt        Compose placeholder home ("Folio" + reader-next note)
        ui/theme/Color.kt      Folio palette = tokens.css values (incl. true-black + highlights)
        ui/theme/Theme.kt      Material3 schemes; dynamic color OFF (brand identity)
        ui/theme/Type.kt       system typography (reader fonts land in R1)
      res/values/              strings, themes (edge-to-edge-ready), colors
      res/mipmap-anydpi-v26/   adaptive launcher (XML only, no bitmaps)
      res/drawable/            ic_launcher_foreground.xml (book + violet bookmark mark)
```

## Pinned toolchain

| Tool | Version | Note |
|---|---|---|
| Gradle | 8.10.2 (wrapper) | installed `gradle` on PATH is 9.8.0 — only used to regenerate wrapper |
| AGP | 8.7.3 | |
| Kotlin | 2.0.21 | compose compiler via `org.jetbrains.kotlin.plugin.compose` |
| Compose BOM | 2024.10.01 | + material3, icons-extended |
| compileSdk / targetSdk | 35 | android-35 installed locally |
| minSdk | 26 | enables XML-only adaptive icons |
| applicationId | dev.folio.mobile | |

## Issues hit & fixes

1. `buildFeatures {}` placed at top level → config-phase failure ("Unresolved reference
   'buildFeatures'"). Moved inside `android{}`; also put `kotlin { compilerOptions { jvmTarget } }`
   inside the android block per Kotlin 2.0 DSL. Wrapper generation aborted the first
   attempt because configuration failed before the task ran.

## Verification status

- Gradle configuration: **GREEN** (wrapper task ran).
- `.\gradlew testDebugUnitTest assembleDebug`: **GREEN** — app-debug.apk 16.2 MB,
  5/5 contract tests passing. (Verified in session M-2; see `.docs/MOBILE-TRACKING.md`.)
- Mid-build file edits caused one red build (stale catalog snapshot) — lesson: never
  edit build files while a background build is running; tracked in session log.

## Next session

1. Read `.docs/MOBILE-TRACKING.md` first.
2. Continue M2: `FileStore` interface + app-private implementation (atomic temp+rename),
   `LibraryManager` port (manifest commit-point, recovery scan), more fixture tests.
