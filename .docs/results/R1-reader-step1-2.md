# R1 steps 1-2 — Readium toolchain + Reader skeleton (Result Summary)

**Session:** M-5 · **Date:** Oct 17, 2026 · **Commits:** ea670a6 → b5642d9 → 1bbb5d8 → a24c770 (pushed)
**Tests: 68/68 green** · `testDebugUnitTest assembleDebug` green · APK builds

## Scope changes

- **TTS read-aloud DROPPED from v1** (user decision): R4 = footnote popups + media
  overlays only. Post-v1 backlog entry, not debt.

## What was built

```
mobile/gradle/libs.versions.toml   readium 3.0.0 (shared/streamer/navigator),
                                   desugar_jdk_libs 2.1.2, fragment/activity-ktx pins
mobile/app/build.gradle.kts        coreLibraryDesugaring enabled
core/reader/ProgressBridge.kt      Locator → schema-1 Progress mapping (CFI contract)
reader/ReaderActivity.kt           EPUB reader activity (Readium navigator, progress
                                   save/restore hooks)
res/layout/activity_reader.xml     FragmentContainerView host
```

## Readium 3.0.0 wiring facts (verified against the AAR bytecode)

| Area | Fact |
|---|---|
| Version choice | 3.0.0 = last release compiled with Kotlin <2.1 (our 2.0.21 reads metadata only up to 2.0.x); minSdk 21, compileSdk 34 — OK under compileSdk 35 |
| Desugaring | Required by all three AARs (`checkDebugAarMetadata` gate); enabled → builds green |
| Open pub | `AssetRetriever(contentResolver, httpClient)` + `EpubParser()` + `PublicationOpener(parser)` (3.0.0 HAS PublicationOpener; suspend `open(asset, allowUserInteraction=true)` returns `Try`) |
| Link.href | `org.readium.r2.shared.publication.Href` object — `resolve(Url("/"))` → plain `Url` for Locators |
| Locator ctor | `(href: Url, mediaType: MediaType, ...)` — mediaType NOT defaulted; `MediaType.XHTML` is the reference constant |
| Progression | v3.0.0 `currentLocator` is a StateFlow (earlier LiveData note in 3.3 docs doesn't apply) |
| Restore | No `Publication.locateProgression()` helper in 3.0.0 → `findService(LocatorService::class)?.locateProgression(percent)` |
| Listener | Must override `onExternalLinkActivated(AbsoluteUrl)`; `shouldFollowInternalLink` has a default impl |
| Death path | `EpubNavigatorFragment.createDummyFactory()` + finish (official recipe) |

## Progress byte-compat contract (locked in code)

- `Progress.location` = `epubcfi(...)` string from `Locator.locations.fragments`
  (first match), else chapter href — matches the PWA's epub.js CFI strings.
- `Progress.percent` = `locations.totalProgression ?: 0.0`.
- Save: distinctUntilChanged(location + 0.4% percent gate) → `saveProgress` on IO.
- Restore (v1): `locateProgression(saved.percent)`; stored CFI not yet used for
  restore (open item; see R1 next-steps).

## Readium-on-JVM test constraint (pattern to reuse)

`Locator`/`Url` construction requires the Android runtime (`android.net.Uri` via
`returnDefaultValues` stubs → NPE). Therefore `ProgressBridge` exposes a
primitive-args core (`fragments, href, totalProgression`) for JVM tests; Locator
overloads are thin wrappers (KDoc cross-reference added).

## Known to-dos (carried)

- Device smoke test (emulator incl.): import → open reader → pagination works.
- `PDFBoxResourceLoader.init(context)` still not wired (import UI phase).
- EpubPreferences defaults for PWA parity (18px/1.6/20, serif, paginated, justify
  off) + 5 themes + typography: R1 step 3-4.
- No DI yet: AppFileStore/LibraryManager constructed ad-hoc in ReaderActivity —
  centralize (FolioApplication + simple AppContainer) when import UI lands.

## Next session

1. Read `.docs/MOBILE-TRACKING.md` first.
2. Verify `.\gradlew testDebugUnitTest assembleDebug` green.
3. R1 step 3: emulator device run (adb install, open ReaderActivity — may need a
   temporary debug intent or a test doc bundled into filesDir via adb), fix runtime
   crashes, then themes/typography defaults.
