# Folio Mobile — Android Rewrite Tracking (AI context file)

> **Read this file FIRST at the start of every session working on `mobile/`.**
> Sessions are short-lived (~30–40 min, AI memory erased). Commit + push to
> `origin/main` (prajapatiharsh2323/folio) after **every** meaningful change, and
> update this file before the session ends. Companion to `.docs/SESSION-TRACKING.md`
> (which covers the PWA side). Result summaries for major changes go in
> `.docs/results/` with `M*` prefixes.

## Status: M3 + L1 + M4 COMPLETE + R1 + R2 DONE + R2b PARTIAL + R3 DONE + Settings screen + Notes screen + sort control + duplicate prompt + Batch 1 fixes + pace/time-left + PERCENT-SCALE compat fix (Progress.percent is 0-100) + Batch 3 backup core (verified zip + merge/replace restore in Settings) + per-book typography overrides + custom selection bar [Highlight]/[Copy] replacing the system menu + WebView main-thread crash fixes + **AUDIT P1/P2/P3 REMEDIATION (2026-10-19, see EPUB_READER_AUDIT_REPORT.md + sessions M-20/M-21)** + in-book search (EPUB-015) + refined chapter resume + notes exact-text jump (EPUB-010 partial, EPUB-018) + bottom-pillar auto-hide + collections Details row-tap fix + folio-collections logcat trace (M-22) - **225/225 tests green, assembleDebug+lintDebug green, pipeline (mobile-ci) live**. TTS + PDF + audio dropped from v1 scope.

## NEXT SESSION — what to build (pick one, verify green build first)

0. **User manual-testing round (in flight as of M-21 close):** a debug APK with
   all M-21 features is at the repo root — `folio-mobile-debug-2026-10-19.apk`
   (~39 MB, supersedes the removed stale `app-release*.apk`). Test points:
   (a) in-book search — open a book → search icon → query ≥2 chars → tap a hit
   → should land ON the matched text; (b) mid-chapter resume — read to ~40%
   of a chapter → close → reopen → should open near the same percent, NOT at
   chapter start (single-chapter TXT sample: percent ≈ fraction, so any
   non-zero open proves the fix); (c) notes/bookmarks tap → opens at the
   annotation's chapter; (d) hardware page-turn keys now animate; (e)
   Settings theme section shows the new per-book-override hint. `gh` still
   unauthenticated — check the mobile-ci Actions tab for the first green
   verdicts of 2bbbc82/fb97fd2/3a66ad7/2b63a1f.
1. **Device-verify the two new reader features** (M-21; emulator env REBUILT
   this session — recipe below verified end-to-end: fresh `D:\Android\avd-sdk`
   sdkmanager install + handcrafted AVD INIs + `skin.name` key REMOVED from
   config.ini or boot dies with "unknown skin name ''"), but hands-on check
   moved to the USER (debug APK at repo root). Emulator notes: kill the
   Gradle daemon before booting (16 GB host was at 0.8 GB free → SystemUI
   ANR loop; `gradlew --stop` freed 10 GB and boot was clean), and the debug
   "Import sample" button builds a SINGLE-chapter TXT EPUB.
2. **Batch 2 remaining polish** — paragraph-snap scrolling (check Readium's
   reflowable script behavior on device first), tap-zone config (PWA has FIXED
   zones — mobile already matches; only build a config setting if we want a
   native-only extra). DONE in M-21: key/page turns animate; Settings hint row
   explains per-book overrides. Per-book typography overrides are DONE (M-19).
3. **Font packaging** — Devanagari/Literata: either bundle ttf + @font-face, or
   upgrade Kotlin→2.1.x to unlock Readium 3.1.2+ `FontFamilyDeclaration`.
   Decision needed (own session). NOTE (M-20): the ghost "lora" menu option was
   REMOVED; bundling lora for real would ride this same session. NOTE (M-21):
   the Readium upgrade is ALSO the unlock for exact text-anchor CFI restore
   (EPUB-010 remainder) and in-page search-hit decorations (EPUB-015 polish) —
   one branch prototypes all three.
4. **R4** — footnote popups + media overlays (own session; TTS stays dropped).

**Carried constraints:** TTS is DROPPED (post-v1 backlog, not debt). PWA schema-1
contracts (manifest, user/*.json, backup ZIP, annotation anchors, Drive sync) must
stay byte-compatible — fixture changes go in BOTH suites in the same commit.

## The locked plan (agreed with user, Oct 17 2026)

- **Route B chosen**: full native Kotlin/Compose rewrite (NOT Capacitor, NOT TWA).
- Search: **in-memory MiniSearch port** over the PWA's `library/index/text/<id>.json`
  corpus (chosen 2026-10-17 — supersedes SQLDelight/FTS5, see decision 3).
- Distribution: **Play Store** (AAB, signing, Play App Signing, target API current).
- PWA stays a **living companion** (Windows/web + clipper host): schema-1 JSON,
  backup ZIP format, annotation anchors, Drive `sync/state.json` protocol are
  byte-compatibility contracts. Any change to them must update fixture literals in
  BOTH test suites in the same commit.
- PDF stack: pdfium viewer (render) + PDFBox-Android (text extraction). Apache-2.0.
  No MuPDF (AGPL).
- **EPUB-first focus** (user priority): R1–R4 reader workstream, see below.
- v1 EPUB scope: R1 core + R2 comfort + R3 annotations (incl. shareable quote-card
  images) + R4 = footnote popups + media overlays. **TTS read-aloud DROPPED for now**
  (user decision, Oct 17 2026 — post-v1 backlog, not debt).
- Deferred post-v1: fixed-layout EPUB, custom user CSS, auto-scroll, repair-import,
  landmarks/page-list.

## Phase ladder

| Phase | Deliverable | Status |
|---|---|---|
| M1 | Scaffold: Gradle project, Compose shell, theme tokens, adaptive icon, wrapper, first successful `assembleDebug` | **DONE** (build green, APK 16.2 MB) |
| M0-verify | (pre-M2 gate) `.\gradlew assembleDebug` green in `mobile/` | **DONE** |
| M2 | Core data layer port: schema-1 serialization, FileStore (app-private, atomic), LibraryManager, conformance fixture suite ported from `packages/core/tests` | **storage layer DONE** (24/24 tests); import pipeline (M3) next |
| M3 | Import pipeline: EPUB (DRM detect), PDF, HTML+sanitizer, TXT; manifest commit-point + write-ahead journal; recovery scan; hash dedupe | **DONE** — 64/64 tests; full result in `.docs/results/M3-import.md` |
| R1 | EPUB rendering: **Readium Kotlin Toolkit**; paginated/scrolled/dual-spread; 5 themes; full typography; Devanagari fonts; publisher-default honored; locator↔CFI compat tests | steps 1-4 DONE — core rendering + prefs + chrome + chapter-accurate restore (strict within-chapter CFI + fonts pending; device smoke blocked) |
| R2 | reader comfort polish (chrome toggle, page pillar, typography controls) | **DONE** (cb7ed34) |
| R2b | Reading comfort (remaining): volume-key + BT page-turner, predictive back, **brightness overlay**, **interactive scrubbing pillar**, page-turn anims, paragraph-snap, tap-zone config, chapter-aware time-left, per-book typography overrides, image zoom | **PARTIAL** (01ce6e5 — keys/brightness/scrubber/predictive-back done; remainder pending) |
| R3 | Annotations: cross-node anchoring, dictionary/translate (Hindi↔English), per-chapter highlight list, color edits, **quote-card share images** | **DONE** (annotation CRUD + reopen rendering + quote-card; dictionary and per-chapter list deferred — see `.docs/results/R3-annotations.md`) |
| R4 | Footnote popups, media overlays (**TTS dropped for now** — user, Oct 17 2026) | pending |
| L1 | Library UI: real list screen (progress, delete, sort), SAF import (content-sniffed), ACTION_VIEW open-with, ImportDispatch; launcher icon from canonical `folio-logo.png` (b0863c4); EPUB covers: extraction + card thumbnails (fb2cb20); spec §4 Rule 2 recovery: damaged banner (59422ea) + interrupted imports finish/delete (3f6cae6); tags + collections: CRUD data APIs, details dialog, filter chips (051c94c); reader splash: cover + spinner while the navigator mounts (e2f8181); collections management: rename/delete dialog + filter rebind (f8b80a0) | **DONE** (01f5dfb — `.docs/results/L1-library.md`; icon M-11; covers M-12; recovery M-13/M-14; tags/collections M-15 — `.docs/results/L1-tags-collections.md`; reader splash M-16 — `.docs/results/L1-reader-splash.md`; collections management M-17 — `.docs/results/L1-collections-manage.md`) |
| M4 | Search: in-memory MiniSearch port (normalise + Hindi stems + boosts 4/3/3/1 + snippet) + library search UI + reader jump; PWA `search.test.ts` golden cases all ported (SQLDelight/FTS5 dropped — decision 3) | **DONE** (da75560 + ecff505 — `.docs/results/M4-search.md`) |
| M5 | Notes/collections/backup/Drive: streaming ZIP backup, merge/replace (typed REPLACE), WorkManager auto-backup, AppAuth + Drive REST mirror | **partial** — streaming backup core DONE in audit remediation (M-20, commits f37b830 + 3f49775: StagedArchive streaming restore/export, staged under file locks); **Drive mirror DONE** (commit faf8394: PWA-compatible `drive.file` mirror — file-by-file into the visible "Folio Library" folder, appProperties relPath+sha256, local `sync/state.json`, manifest uploaded last, 7/7 sync scenarios ported to Kotlin incl. two-device convergence, newer-metadata-wins, deletions-never-propagate, docId remap); **WorkManager auto-sync DONE** (opt-in toggle, 6 h periodic, unmetered + battery-not-low constraints); local ZIP backup export/restore still the offline path |
| M6 | Play hardening: signing, AAB, R8 rules, baseline profile, Data Safety, in-app updates | pending (allowBackup=false + data-extraction-rules landed with M-20) |
| M7 | CI: lint/detekt on PR, emulator instrumented job, AAB artifact, release-on-tag | **partial** — mobile-ci workflow live (M-20, 423f0cf): unit tests + assembleDebug + lintDebug on every mobile push/PR with report artifacts; detekt + instrumented job + AAB/release still pending |

## Key decisions & rationale (do not re-litigate without user)

1. **Native rewrite, not Capacitor** — user explicitly chose Route B over recommendation.
2. **Readium Kotlin Toolkit** for EPUB (replaces epub.js): native rendering, Locator model,
   no iframe/CSS-war, TTS + media-overlays support built in.
3. **Search = in-memory MiniSearch port, NOT SQLDelight + FTS5** (reversed
   2026-10-17, user-approved). Why: Android *platform* SQLite ships without
   FTS5 (only FTS3/4), so SQLDelight FTS5 would need a bundled SQLite engine;
   the PWA reference engine is MiniSearch in-memory anyway and the mobile index
   rebuilds per screen-open from `library/index/text/<id>.json` (reading files
   only — screen-open scale). Keeps search behavior 1:1 with the reference
   (same boosts, prefix/fuzzy rules, BM25 constants, snippet markup) and
   schema-1 untouched.
4. Kotlin 2.0.21 + AGP 8.7.3 + Gradle 8.10.2 — battle-tested trio (installed Gradle is
   9.8.0; wrapper pins 8.10.2 so builds are reproducible anywhere).
5. minSdk 26 / targetSdk 35 / compileSdk 35 (android-35 platform installed locally).
6. Package `dev.folio.mobile`; icon = vector adaptive (charcoal bg #23242F + violet
   bookmark #715CFC brand mark), no bitmaps committed.
7. Material You dynamic color disabled for now — Folio palette is the identity.
8. **Readium Kotlin Toolkit pinned to 3.0.0** (Kotlin 1.9.24-compiled; our Kotlin
   2.0.21 cannot consume metadata from 3.1+ which is built with Kotlin 2.1.x).
   Upgrading mobile to Kotlin 2.1.x later unlocks 3.1.2. Modules: shared/streamer/
   navigator only — NO readium-lcp (DRM refused at import), no opds.
9. **Progress .location contract**: the PWA stores epub.js `epubcfi(...)` strings.
   Mobile writes CFI strings from Readium `Locator.locations.fragments` (or chapter
   href when no CFI). v1 restore uses `publication.locateProgression(percent)`;
   exact CFI-restore is an open R1 item.
10. **Readium-on-JVM test facts (hots, don't repeat)**: constructing Readium
    `Url`/`Locator` values in JVM unit tests fails — `Url` needs the real
    `android.net.Uri` (stubbed android.jar returns null → NPE "encode(...) must
    not be null") and companion factories resolve as nullable in the test
    source set. Pattern: keep pure logic on primitive args (see
    `ProgressBridge`); Locator overloads stay thin wrappers.
11. **Readium 3.0.0 API quirks hit**: `Link.href` is an `Href` object (not `Url`)
    — convert via `link.href.resolve(Url("/"))`; `Locator` constructors demand
    `mediaType`/`type` (use `MediaType.XHTML`); Listener must override
    `onExternalLinkActivated` (only); `Publication` 3.0.0 has NO
    locate/locateProgression helpers → use `findService(LocatorService)`; Activity
    uses `supportFragmentManager` (fragmentFactory before adding the fragment).
12. **Readium 3.0.0 prefs quirks**: `EpubPreferences.fontFamily` is a
    `FontFamily` VALUE class over String (map serif/sans/mono via
    `FontFamily.SERIF/SANS_SERIF/MONOSPACE`; custom → `FontFamily(str)`);
    `Color` is a value class over Int ARGB; enums `Theme{LIGHT,DARK,SEPIA}`,
    `Spread{AUTO,NEVER,ALWAYS}`, `ColumnCount{AUTO,ONE,TWO}`,
    `TextAlign{JUSTIFY,...}`, `ReadingProgression{LTR,RTL}`. Typography prefs
    (lineHeight/textAlign/hyphens/letterSpacing/paragraphSpacing) only take
    effect with `publisherStyles=false` — we default to false (PWA's styles
    always apply). Initial prefs passed as `initialPreferences` on
    `createFragmentFactory`; EpubDefaults via
    `EpubNavigatorFactory.Configuration(defaults=...)`.
13. **ReadingPrefs value semantics (mapping locked in ReaderPrefsMapper)**:
    schema `fontSize`/`margin` are PX → divide by 16 into Readium relative
    fontSize factor / pageMargins em-factor (v1 approximation marked);
    lineHeight passthrough; theme keys resolve via PWA THEME_COLORS tokens
    (exact hex identical to `epub-engine.ts`), warm→SEPIA,
    dark+true-black→DARK, system→light on native v1; spread none→NEVER,
    both→ALWAYS; `fontFamily: publisher` → null override.
14. **Importers are `object`s** (companion-less) with sync `import(bytes, name)`
    — NOT constructors; `LibraryManager.listDocuments(): List<DocMeta>`
    (metadata), NOT ManifestDoc (file-hashes).
15. **Readium 3.0.0 selection/decoration APIs**: `SelectableNavigator.
    currentSelection()` is a **suspend** fn returning `Selection?` (NO flow);
    `clearSelection()`; `Selection(locator, rect: RectF)`. `DecorableNavigator`
    has **batch** `applyDecorations(List<Decoration>, group: String)` (no
    single-add in 3.0) + `supportsDecorationStyle(KClass)` +
    `addDecorationListener(group, Listener{onDecorationActivated(event):
    Boolean})`. `Decoration(id, locator, style, extras)`;
    `Style.Highlight(tint: Int ARGB, isActive: Boolean)`; tints via
    `Decoration.Style.Tinted.getTint(): Int`. `InputListener.onTap(TapEvent):
    Boolean` (+ onDrag/onKey) via `addInputListener` — returning true consumes.
16. **`Locator.Text(before, highlight, after)`** — all three are NULLABLE in
    Kotlin metadata (javap shows non-null; use `.orEmpty()`). PWA annotation
    parity: `prefix = before.takeLast(32)`, `suffix = after.take(32)`,
    `location` = CFI string.
17. **HighlightManager (R3, post-session M-7)**: persisted annotations render on
    reopen **without a CFI parser** — first assumption here was wrong. Verified
    in toolkit 3.0.0 source: the WebView `rangeFromLocator()` anchors decoration
    ranges from `locator.text` (highlight/before/after) using Hypothesis
    `TextQuoteAnchor`; decoration routing is keyed by `decoration.locator.href`
    (`DecorableNavigator.changesByHref`, exact Url equality). Recipe: parse
    `Annotation.location` → spine index (`AnnotationLocators.anchorIndex`:
    `ProgressBridge.cfiSpineIndex` for epubcfi, exact/leading-slash href match
    as fallback), rebuild
    `Locator(href = readingOrder[idx].url(), text = quote/prefix/suffix)` and
    `applyDecorations`. `EpubNavigatorFragment.publication` is protected — pass
    the `Publication` explicitly (HighlightManager constructor). Quote-card
    share = pure `QuoteCardContent` (clamps 280/160, wrap) + Bitmap painter →
    cacheDir PNG → FileProvider (`${applicationId}.fileprovider`,
    `file_provider_paths.xml` cache-path `quote-card/`) → ACTION_SEND.

18. **Jump resolution chain (M-21 javap verification)** —
    `EpubNavigatorFragment.go(locator)` → `PublicationKt.normalizeLocator`
    (href-only normalization) → pager item → `R2EpubPageFragment.loadLocator`
    priority: (1) `locator.text.highlight` → `R2WebView.scrollToLocator`
    (exact text position), (2) `locations.htmlId`, (3)
    `locations.progression` (0..1 → scroll offset in scroll mode, else
    `round(numPages * progression)`). `locations.totalProgression` is never
    consumed here — resource selection happens caller-side (that's why
    `locateProgression` positions carry href AND progression). So jumping
    "to position X of chapter Y" = `Locator(href = chapter, locations =
    Locations(progression = f))`; jumping "to matched text" = hand back the
    search/match locator with its `text.highlight` untouched. NO `epubcfi`
    string exists in the injected scripts (`readium-reflowable.js` etc.) —
    CFI fragments on a locator are IGNORED by the reflowable jump path in
    this pin; decorations are the only text-anchor consumer that resolves
    them differently (see 17). Search: `publication.findService(
    SearchService::class)?.search(query, Options())` → `SearchIterator`
    (suspend `forEach { LocatorCollection }` → `collection.locators`,
    each with resolvable text anchors); `StringSearchService` is
    auto-registered by `EpubParser` (streamer bytecode 1668–1677).
    Iterate on IO (no WebView); `go` on Main.

## Environment facts (fresh sessions: re-verify, don't assume)

- Windows host; repo `D:\folio`; branch `main`; remote origin = prajapatiharsh2323/folio.
- JDK 17 (Temurin 17.0.20.1) on PATH; `ANDROID_HOME=C:\Android\android-sdk`
  (platforms 34–37.2, build-tools 34–37, emulator, NDK, licenses OK).
- **Emulator (added Oct 18 2026)**: writable SDK root `D:\Android\avd-sdk` (C: SDK is
  read-only for this process) holds `platform-tools\adb.exe`, `emulator\emulator.exe`,
  `system-images;android-34;google_apis;x86_64`. AVD `folio_test` handcrafted INIs at
  `C:\Users\RDP\.android\avd\folio_test.avd\config.ini` + `folio_test.ini` (modern
  `avdmanager` ignores env/`--sdk_root`; INI needs `abi.type=x86_64`, `hw.cpu.arch=x86_64`,
  `image.sysdir.1=system-images/android-34/google_apis/x86_64/`). Launch (background;
  BOTH env vars mandatory): `$env:ANDROID_HOME="D:\Android\avd-sdk"; $env:ANDROID_SDK_ROOT="D:\Android\avd-sdk"; & "D:\Android\avd-sdk\emulator\emulator.exe" -avd folio_test -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`.
  Install: `adb install -r -g app-debug.apk` (first plain install hit
  `INSTALL_FAILED_VERIFICATION_FAILURE: Integrity verification timed out`; retry with `-r -g` works).
- Smoke-driving recipe: `uiautomator dump` is broken on this emulator ("null root
  node") — use `adb shell screencap` + `adb pull` + read tool; screen 1080×2340;
  known taps: mid-screen (540,1170) toggles reader chrome, doc-card overflow
  (980,355), overflow Delete item (~896,759), delete-confirm Delete (~816,1349),
  debug import button (542,1341). Emulator is SLOW (~25–55 s to open reader);
  screenshots of large PNGs can time out — pull, don't stream.
- `sdkmanager` needs attached-equals syntax `--sdk_root="D:\Android\avd-sdk"` (space form errors).
- `gradle` 9.8.0 on PATH (only needed to regenerate wrapper; builds use `.\gradlew`).
- Git identity NOT persistent on fresh VMs: run
  `git config user.name "Harsh Prajapati"; git config user.email "harshprajapaticoding@gmail.com"`.
- Build: `cd mobile; .\gradlew assembleDebug` (first run downloads distribution+deps).
- Read-only parts of this repo: `apps/clipper` (never touch); `apps/web`/`packages/core`
  are reference for contracts, not edit targets (unless explicitly asked).

## Contract reference (from PWA source — do not re-derive)

- Schema-1 types: `packages/core/src/types.ts` (DocMeta/Progress/Annotation/Collection/Manifest).
- Library layout: `library/manifest.json` commit point; docs/<slug>/ meta.json +
  original|clean|reading + cover; user/*.json; slug = `YYYY-MM-DD-title-words-xxxx`
  + 4 hex; write order: content → meta.json → manifest LAST.
- Merge rules (backup restore & Drive): never deletes; annotations newer-wins by id;
  progress newer-wins per doc; collections union docIds; replace = typed "REPLACE".
- Search normalise: NFC, strip U+200C/200D, NEVER strip Devanagari combining marks;
  tokenizer `[^\p{L}\p{M}\p{N}]+`; boosts title4/author3/tags3/text1; Hindi stems
  `-यों/-ियों→-ी`; fuzzy Latin >4 chars only. Golden fixtures: `packages/core/tests/fixtures/hindi/`.
- Themes (tokens.css): light bg #FAFAF7 / surface #FFF / text #1C1B19 / accent #2F6F5E;
  warm #F4ECD8/#FBF5E6/#3B3226/#8A5A2B; dark #121214/#1C1C20/#E6E3DC/#7FC8B2;
  true-black #000/#0B0B0E/#C8C4BC. Highlights: #FFE58A #B7E4B0 #A9D1F5 #F5B7CC.
- Reading prefs defaults: 18px/1.6/margin 20, serif, paginated, justify off.
- PWA reader quirks native must FIX (evidence: apps/web/src/reader/epub-engine.ts):
  truncated prefix/suffix (per-node only), ~300 lines of !important CSS overrides,
  iframe coordinate gymnastics, lazy locations generation (percent flicker),
  rendition rebuild on flow switch.

8. kotlinx.serialization gotcha (hit once, don't repeat): enum classes serialize by
   ENTRY NAME, not by constructor properties — always use `@SerialName("lowercase")`
   per entry. And `kotlinx-serialization-json` must be `implementation`, not
   `testImplementation`, if main-source models use it.

## Session log

- **Session M-23 (Oct 20, 2026)** — UX / cloud / notes / settings fix pass
  (branch `ux/cloud-notes-settings`, base `4a96a0e`). Full report:
  `FOLIO_UX_FEATURE_FLOW_FIX_REPORT.md`.
  - **P0 account + sync:** system `newChooseAccountIntent` chooser; typed
    `SavedAccountState` (`Connected` / `Missing` / `Unverified`); no fake empty
    account list; stale account disabled + reconnect copy; separate first-sync
    consent; `UserRecoverableAuthIOException` handling; account change deletes
    local Drive-state mapping. Added `SyncFailureMapper` (auth/permission/
    offline/rate/server/storage, safe copy, retryability), worker attention +
    cancellation handling, and a process-wide `Mutex` around `SyncManager.sync`.
  - **Annotation flow:** one save, then close (`openMenuAfter` removed); sheets
    stay open and preserve text on failure; edit/delete persist before
    decorating. Shared accessible `AnnotationMenuSheet` + `SelectionToolbarSheet`
    with 48 dp radio swatches and multiline note field.
  - **Notes:** loading/error/retry, guarded mutations, inline dialog errors,
    snackbars only after persistence, overflow menu row, raw-kind labels gone.
    New `AnnotationFilter` helper documents that a highlight-with-note appears in
    both Highlights and Notes (unit-tested).
  - **Settings:** `LazyColumn`, ordered `Channel` preference writes with revert,
    system theme split preview + radio semantics, separate library/app/free
    storage, `formatBytes`, friendly backup errors.
  - **Appearance:** new reusable `FolioSlider` (M3 slots, 48 dp target, live
    value); all reading sliders migrated; theme swatches selectable.
  - **Validation:** 249/249 unit tests, `assembleDebug` and `lintDebug` green.
    No device/emulator: runtime OAuth/WorkManager/TalkBack/visual checks remain.
  - **Scope note:** kept `drive.file`; docs now state Android ↔ PWA mirroring is
    unsupported under per-client access (backup ZIP is the bridge).
- **Session M-22 (Oct 19, 2026)** — USER TESTING FIX ROUND (three bugs from
  the manual-testing round of the `folio-mobile-debug-2026-10-19.apk`). Tests
  + assembleDebug green; fresh APK replaced at the repo root.
  - **Collections "unable to add from Details" — diagnose-then-fix.** Static
    audit of every hop (DetailsDialog → setDocCollections → updateDocMeta →
    refresh → filter chips) matched the covered JVM tests, so the failing hop
    is device-side. Landed: (a) the collection checkbox row is now fully
    tappable (PWA parity — previously only the bare Checkbox box toggled, so
    tapping the label read as "can't add"); (b) debug-only `folio-collections`
    logcat trace at each hop (details open, toggle, create, save ids, save
    readback of memberships + meta.collectionIds) + a `Log.w` in
    `getCollections` so a corrupt/unreadable collections.json can no longer
    masquerade as "no collections". Next device run: `adb logcat -s
    folio-collections` pinpoints the failing hop if the row-tap fix alone
    doesn't clear it.
  - **Bottom progress pillar auto-hide (user choice).** The always-floating
    pillar covered the last lines of paginated AND scrolled pages (the
    navigator container only pads system bars). `ReaderChrome` gained a
    `pillarVisible` param — the pillar now follows the middle-third chrome
    toggle (tap to show) and stays pinned while scrubbing, dropping ~1.2 s
    after the drag ends. No layout reflow when it shows/hides.
  - **Notes-tab jump lands on the exact highlighted text (EPUB-018
    completion).** `locationTarget()` was chapter-granular by design. Now the
    annotation's quote+context (always stored: `SelectionBridge` prefix/
    quote/suffix) rides `createIntent` extras; the reader lands chapter-
    accurate first (`AnnotationLocators.anchorIndex`), then fires a text-
    quote `go()` on the navigator's FIRST locator emission — the same
    `loadLocator` text-highlight priority proven by the EPUB-015 search-hit
    jumps. Bookmarks (no quote) and any resolution failure degrade to the
    chapter-start landing.
  - Commits: `ec964f8` (collections fix) + `deb3e7d` (reader UX) + this
    docs/APK commit.

- **Session M-21 (Oct 19, 2026)** — AUDIT REMEDIATION CONTINUED: the
  device-gated items re-scoped against actual Readium 3.0.0 bytecode and two
  shipped as implemented-edge features. **225/225 tests green, lintDebug
  green, assembleDebug green** (commits 2bbbc82, fb97fd2, 3a66ad7 + this
  docs commit).
  - Toolkit verification pass (no device, all via AAR/javap on the gradle
    cache): `EpubNavigatorFragment` does NOT implement `Searchable` in 3.0.0
    (the audit's claim was stale), but the publication-side `SearchService`
    exists and `EpubParser` auto-registers `StringSearchService` (streamer
    bytecode offsets 1668–1677). `R2EpubPageFragment.loadLocator` jump
    priority verified: `text.highlight` → `R2WebView.scrollToLocator`,
    then `locations.htmlId`, then `locations.progression` (scroll offset /
    page index). No `epubcfi` string exists anywhere in the navigator's
    injected scripts → text-anchor CFI jumps are impossible in this pin
    (confirms the tracking hot-note; EPUB-010's "feed CFI back to go()"
    audit fix is unreachable without the Kotlin 2.1/Readium 3.1.2+ upgrade).
  - **2bbbc82 — EPUB-015 (in-book search):** the audit's feature actually
    ships — SearchService iteration on IO streams per-resource batches into
    a new `SearchSheet` (ModalBottomSheet, ≥2-char guard like lib search,
    200-hit cap, whitespace-collapsed 160-char snippets, chapter-title
    labels); tapping a hit calls `navigator.go(locator)` on Main and lands
    on the matched text (text.highlight anchor). 8 new ReaderSearchTest
    cases. Chrome top bar now scrolls horizontally — ten 48dp
    IconButton targets already clipped a 360dp screen BEFORE the search
    icon was added (pre-existing bug surfaced by the batch).
  - **fb97fd2 — EPUB-010 partial + EPUB-018 tie-in:** CFI-bearing saves
    still pick the chapter via the cfi spine component, and the in-chapter
    offset is now recomputed from the saved total percent over
    `PositionsService.positionsByReadingOrder` — pure-core
    `ReaderResume.inChapterFraction` (clamped; null on zero-width/inverted/
    non-finite spans → falls back to chapter-start). Notes/bookmarks cards
    now jump to their saved location (CFI → spine index, TXT-era href
    fallback → reading-order endsWith match); `location` is String?-safe.
    5 new ReaderResumeTest cases. Exact text-anchor restore stays gated on
    EPUB-012 (documented in ReaderActivity + ProgressBridge KDoc).
  - **3a66ad7 — polish:** Settings theme section explains that reader
    theme/Aa controls set per-book overrides (NEXT-SESSION item 4 done);
    hardware/media-key page turns animate (`goForward/goBackward(true)`)
    matching the navigator's own tap-zone turns.
  - Hot notes for future sessions: `mutableStateOf` writes are snapshot-safe
    from any thread (search results stream from IO). Readium `Try` has
    `isSuccess/isFailure/getOrNull/failureOrNull` (no Kotlin-Result
    interop). `Annotation.location` is `String? = null` (Progress.location
    is non-null String). Selectively rethrow CancellationException around
    suspend library calls wrapped in try/catch (runCatching swallows
    cancellation and desyncs the busy/completed flags).
  - **Next session:** user manual-testing verdicts on
    `folio-mobile-debug-2026-10-19.apk` (repo root) drive fixes if any; then
    font packaging / EPUB-012 branch prototype / R4 as before.

- **Session M-20 (Oct 19, 2026)** — AUDIT REMEDIATION: P1/P2/P3 items from
  `EPUB_READER_AUDIT_REPORT.md` fixed in small pushed batches
  (3f49775 → c512265). **212/212 tests green, lintDebug green, assembleDebug
  green.** Device-gated items (EPUB-010/012/015/018/019) intentionally remain
  R3+; schema-1 byte-compat untouched throughout.
  - **3f49775 — P1 data-safety (EPUB-005/006/008):** process-wide mutex
    registry on LibraryManager for every user-file writer (progress/
    annotations/collections/manifest restore), sorted-key `withLocks`,
    non-reentrant discipline (private `*Locked()` helpers), `moveWithRetry`
    for Windows rename races; reader onDestroy flush is fire-and-forget on a
    process-lifetime scope (no runBlocking); allowBackup=false (+ later
    data-extraction-rules). New LibraryManagerConcurrencyTest.
  - **f37b830 — EPUB-007 streaming + EPUB-006 Settings:** restore/export
    stage the ZIP to a temp file (`StagedArchive`, 64 KiB chunks, delete on
    close) and read entry-by-entry; `VerificationResult` no longer holds an
    in-RAM entry map; restore mutation phase runs under the manager's 4 file
    locks; Settings export/restore are suspend on `Dispatchers.IO` with UI
    updates marshalled back to Main. BackupRestoreStreamingTest (120-doc
    multi-MB round-trip, lazy reads, unicode producer parity, trailing-junk).
  - **b4db671 — EPUB-001:** dropped `application/octet-stream` from the
    import picker and the ACTION_VIEW manifest filter (the pipeline refuses
    it → guaranteed dead-end UX); ImportMimeContractTest pins the advertised
    set (first app-package test).
  - **ae1b43c — EPUB-016 + EPUB-009:** the five real Hindi EPUBs from
    `sample/` are checked into `app/src/test/resources/fixtures/` (byte-stable,
    sha256-pinned; ASCII copies of the Devanagari-named originals) and parsed
    by EpubCorpusTest with per-fixture goldens (title/author/language/
    chapters/wordCount incl. the 54-chapter Osho अंतर की खोज), spine-density
    + hash-roundtrip invariants, and a real-data DRM refusal test. Golden
    strings were derived via codepoint dumps (Windows console mangles
    Devanagari — do not eyeball these).
  - **423f0cf — EPUB-017:** `.github/workflows/mobile-ci.yml` (temurin 17,
    test + assembleDebug + lintDebug, test/lint artifacts, path-filtered,
    concurrency-cancel); `mobile/gradlew` exec bit restored for Linux. Lint
    made green to gate: `ProduceStateDoesNotAssignValue` at MainActivity cover
    thumbnail was unresolvable under the K2 UAST bridge → restructured to
    remember(item.cover) + LaunchedEffect (same semantics); AppLinkUrlError
    suppressed with justification on the MIME-only VIEW filter;
    res/xml/data_extraction_rules.xml added (12+ backup-off intent, no-op with
    allowBackup=false). First run not yet verified (gh unauthenticated).
  - **238266d — EPUB-013/003:** dead `importFromUri` deleted (runImport is the
    one import path); Settings "Storage" now shows `directoryBytes(filesDir)`
    ("Folio uses X MB" + device free space) instead of the whole app partition
    (StatFs totalBytes/availableBytes).
  - **48a3e53 — EPUB-002:** ghost "lora" option removed from the typography
    menu (no font shipped; fallback rendering lied). Stored lora prefs keep
    the same pass-through rendering; real support needs TTF bundling +
    WebView @font-face + device verification.
  - **74b94ba — EPUB-014:** LocalLifecycleOwner → androidx.lifecycle.compose;
    Sort/FormatListBulleted → AutoMirrored icons; createDocSlug date prefix
    migrated from deprecated Date getters to ZonedDateTime with an
    equivalence test pinning byte-stable output (contentHash untouched — no
    schema drift). BackupJson ExperimentalSerializationApi item was already
    moot.
  - **c512265 — EPUB-011:** dormant letterSpacing/paragraphSpacing now map 1:1
    (em) into Readium's EpubPreferences; 0.0 schema default → null (publisher
    CSS stays in charge for untouched users); maxWidth/pdfZoom documented as
    legacy-inert (no navigator slot; desktop-column concept / PDF dropped).
    ReaderPrefsAndroidSpacingTest added.
  - Verification summary: `:app:testDebugUnitTest` 212/212 green;
    `:app:lintDebug` exits 0 (remaining warnings = version-update noise + a
    few style items, visible in the uploaded lint report); assembleDebug OK.
  - **Next session:** verify the first mobile-ci run on GitHub Actions; then
    the standing NEXT SESSION list (Batch 2 polish / font packaging / R4).

- **Session M-19 (Oct 18, 2026)** — Batch 2 partial: per-book typography
  overrides + WebView selection bar + MAIN-THREAD CRASH FIXES (commits f2c0565,
  b2b16c4, 8d5a84b, all pushed). **188/188 tests green, assembleDebug green.**
  - **Per-book typography overrides** (b2b16c4): new `DocPrefsStore`
    (`library/user/doc-settings.json`, `docId → ReadingPrefs`; MOBILE-ONLY
    extension — NOT in the backup contract, PWA ignores unknown user files;
    deliberately out of export/restore for v1). Reader loads doc override over
    global prefs; chrome edits persist to THE DOC ONLY (Settings screen stays
    library-wide — settings.json untouched); `reset` chip in the typography row
    clears override + reverts to global. 6 unit tests. DEVICE-VERIFIED: dark
    override applied instantly + persists across force-stop/reopen; global
    settings.json absent after reader edit; reset flips back to light AND
    deletes the file.
  - **Selection action mode, round 1** (f2c0565): suppressing via no-op
    `selectionActionModeCallback` DID work technically but made the highlight
    flow unreachable (tapping the middle third to reveal chrome CLEARS the
    selection — observed on device). ALSO: first on-device verification FAILED
    silently — the installed APK predated the change (never ran `assembleDebug`
    after editing, only `compileDebugKotlin`/`testDebugUnitTest`). Lesson:
    after ANY reader source change, rebuild `assembleDebug` before install.
  - **Selection action mode, final** (8d5a84b): custom
    `selectionActionModeCallback` menu `[Highlight] [Copy]` — long-press gets
    the app's own floating bar instead of the system Copy/Share/Select-all/
    Read-aloud (PWA parity, better than chrome-brush chain). Highlight →
    existing `onHighlightTapped` sheet capture; Copy → new
    `onSelectionCopyTapped` (live-selection capture + clipboard, no sheet).
    DEVICE-VERIFIED end-to-end: long-press → floating bar → Highlight → sheet
    with quote "Lantern" → yellow chip → highlight painted, sheet closed.
  - **CRASH FIX (the big one — 8d5a84b)**: `loadScope` is Dispatchers.IO, so
    `navigator.currentSelection()`, `locateProgression`, and
    `highlightManager` update/remove→`applyDecorations` all ran WebView calls
    off-main → `FATAL: A WebView method was called on thread
    'DefaultDispatcher-worker-1'` (found via logcat after a " keeps
    stopping" screen; this ALSO explains the batch-1 smoke 'reader died'
    moments). Fixed: onHighlightTapped/onSelectionCopyTapped/scrubTo use
    `lifecycleScope.launch(Dispatchers.Main)`; restore locateProgression and
    annotation update/remove decoration calls wrapped in
    `withContext(Dispatchers.Main)`. The earlier "renderer died, environmental"
    note in M-18 partially re-classified: some of those were THIS bug.
  - **Typography row overflow** (8d5a84b): the row clips on 411dp screens —
    brightness + reset chip were unreachable; added `horizontalScroll`.
    Device-verified drag-reveal + reset tap.
  - Commit messages: 8d5a84b bundles the crash fixes + selection bar + row
    scroll (amended once — pushed after amend, safe this session since no
    pull-window).

- **Session M-18 (Oct 18, 2026)** — Batch 1 fixes + pace + percent-scale +
  **emulator unblocked, smoke passed** + Batch 3 backup core (commits 3d9fe33,
  8affd7c, both pushed). **182/182 tests green, assembleDebug green.**
  - **Batch 1 fixes** (3d9fe33): selection toolbar real note editor (saves the
    typed note, opens annotation menu after); quote cards carry doc author;
    Notes screen per-row edit/delete with confirm dialogs; `system` theme
    follows OS dark mode (mapper 2-arg `resolveThemeKey(raw, systemDark)`);
    snackbar on create-collection failure.
  - **Percent-scale contract fix**: `Progress.percent` is 0–100 like the PWA
    (`epub-engine.ts` L582: `round(p*1000)/10`). Mobile keeps Readium 0..1
    internally; `ProgressBridge.locationPercent` writes 0–100, restore reads
    `percent/100`, noise gate 0.4, `LibraryUi` coerces + labels `${toInt()}%`,
    finished at `>=100-1e-9`, MainActivity bar `/100`. **Do NOT revert to 0..1**.
  - **Pace/time-left** (PWA `reading/stats.ts` port): `ReadingPaceStats.kt`
    (`updatePace`, `estimateTimeLeftSeconds`, `formatTimeLeft`, 5% jump
    threshold); accumulates in ReaderActivity locator collection
    (`SystemClock.elapsedRealtime` deltas), persists as `Progress.pace`; pillar
    shows "N min left". Golden tests ported.
  - **Smoke setup**: writable SDK root `D:\Android\avd-sdk` + handcrafted AVD
    `folio_test` (details in Environment facts). `uiautomator` broken →
    screencap + `input tap` driving (recipe in Environment facts).
  - **Smoke results (PASS unless noted)**: debug import → card renders with
    live 0% progress bar (validates new percent scale); reader renders EPUB
    (chrome, typography row, serif content, pillar + scrubber); mid-screen
    chrome toggle; volume-down page turn; **crash recovery** (WebView
    `SandboxedProcessService` died twice under VM memory pressure —
    environmental, app fell back to MainActivity with data intact); delete +
    confirm → empty library; **duplicate skip** (re-import identical sample →
    still 1 card, progress cleaned with doc). Not driven on device (slow VM +
    renderer deaths): highlight reopen/quote share, search→tap, recovery
    banners, tags/collections — all covered by JVM tests.
  - **Device-only follow-ups**: (1) long-press shows the system WebView action
    mode (Copy/Share/Read aloud); the app's SelectionToolbarSheet only opens
    via the chrome brush button — consider suppressing the default action mode
    for PWA parity. (2) Emulator is memory-starved: WebView renderer deaths
    are environmental, not app bugs.
  - **Batch 3 backup core** (8affd7c): `BackupTypes/BackupJson/BackupZip/
    BackupRestore` port of `packages/core/src/backup/*` — verified schema-1
    zip (backup.json first, manifest sha256 guard, unsafe-path/20k-entry/1GB
    limits, STORED for epub/pdf/images, deflate otherwise) + §8
    merge/replace restore (merge never deletes, newer-wins
    annotations/progress by `updatedAt`, collections union docIds, settings
    kept in merge, manifest written LAST). SettingsActivity now streams export
    via `exportBackupZip` + `generateBackupFilename`, and restore verifies
    then asks merge vs replace (no more unzip-overwrite). 8 new tests in
    `BackupRestoreTest` (roundtrip, checksum mismatch, unsafe path, merge,
    duplicate-hash skip, newer-wins, union, replace).
  - **Gotcha**: `BackupZip`/`BackupRestore` are FILE names — import their
    top-level functions (`exportBackupZip`), not `BackupZip.something`.
    `exportBackupZip` is `suspend` (streaming).
  - **Not done**: Batch 2 polish (page-turn animation, paragraph-snap, tap
    zones, per-book typography), WebView action-mode suppression, R4, fonts.

- **Session M-17 (Oct 17, 2026)** — L1 follow-up slice: **collections
  management — rename/delete** (commit f8b80a0, pushed). Full summary in
  `.docs/results/L1-collections-manage.md`.
  - Top-bar overflow → ManageCollectionsDialog: per-row name + doc count,
    rename (prefilled sub-dialog) + delete (confirm sub-dialog); empty state
    points at the Details dialog.
  - Pure: `LibraryUi.sameTarget` + `rebind` — filter refresh now keeps a
    renamed collection selected (id-matched) and drops deleted targets.
  - Tests +1 → **164/164 green**.
  - **Not done**: device smoke (blocked), font packaging, R4.

- **Session M-16 (Oct 17, 2026)** — L1 residue slice: **reader splash** (commit
  e2f8181, pushed). Full summary in `.docs/results/L1-reader-splash.md`.
  - `CoverLoader.decode` gains `reqW/reqH` (defaults = card thumb); LRU key is
    size-scoped (`path@WxH`).
  - `ReaderActivity`: splash layer above the chrome (AnimatedVisibility +
    450ms fadeOut) — cover (files.cover, 660×990 decode) + spinner while the
    navigator mounts; drops on the navigator's first `currentLocator` emission
    (Readium 3.0 listener has NO page-loaded callback — checked via javap) with
    an 8s safety timeout so a silent render failure can't trap the user.
  - No new pure logic → **163/163 green**; L1 residue list is now EMPTY.
  - **Not done**: collections rename/delete UI; device smoke (still blocked).

- **Session M-15 (Oct 17, 2026)** — L1 residue slice: **tags + collections**
  (commit 051c94c, pushed). Full summary in
  `.docs/results/L1-tags-collections.md`.
  - Data: `createCollection` / `renameCollection` / `deleteCollection` /
    `setDocCollections` (PWA library-manager.ts ports); deleteCollection keeps
    stale doc meta ids (PWA parity).
  - Pure: `LibraryItem.tags/collectionIds`, `LibraryFilter` +
    `filters()`/`applyFilter()`, `normalizeTag()` (PWA DocumentSheet rule).
  - UI: filter-chip row (exclusive, All + collections + #tags) + no-match
    empty state; card menu "Details" → dialog with tag chips, collection
    checkboxes, inline create; Save = updateDocMeta(tags) then
    setDocCollections (PWA write order).
  - Tests +6 → **163/163 green**.
  - **Not done**: collections rename/delete UI (data APIs tested, no entry
    point yet); reader splash; R2b remainder.

- **Session M-14 (Oct 17, 2026)** — L1 residue slice: **interrupted-import
  recovery — finish or delete** (commit 3f6cae6, pushed) — completes spec §4
  Rule 2. Full summary in `.docs/results/L1-recovery.md` (now covers both
  halves).
  - Data: `InterruptedImport` + `scanInterruptedImports()` (finishable =
    meta.json parses); `finishInterruptedImport()` re-registers the folder
    into the manifest rebuilding path/size/sha256 from disk (recursive
    `walkFiles` over `FileStore.list/exists`); `discardInterruptedImport()`
    removes folder + index text, refuses registered slugs.
  - UI: second `RecoveryBanner` (refactored shared from M-13's DamagedBanner);
    dialog rows Finish (finishable only) / Delete.
  - Tests +5 → **157/157 green**.
  - **Hot fact**: `AppFileStore.exists()` is file-only (`resolve(p).isFile`) —
    never assert a directory with it; two older test asserts were fixed.

- **Session M-13 (Oct 17, 2026)** — L1 residue slice: **damaged-doc recovery
  banner + review dialog** (commit 59422ea, pushed). Full summary in
  `.docs/results/L1-recovery.md`.
  - `LibraryUi.damagedNotice()` (pure: singular/plural banner text,
    title-or-slug labels); `LibraryScreen.refresh()` loads
    `damagedDocuments()`; errorContainer banner above the list with Review →
    dialog (Remove reuses `deleteDocument`; auto-closes when clean).
  - Tests +4 → **152/152 green**; the corrupt-meta → remove flow is covered
    end-to-end in `LibraryManagerTest`.
  - **Left for next**: interrupted-import UI (spec §4 Rule 2 first half;
    `recoverInterruptedImports()` exists, no UI — needs a "finish" data API);
    tags/collections UI; reader splash image.

- **Session M-12 (Oct 17, 2026)** — L1 residue slice: **EPUB covers
  (extraction + card display)** (commit fb2cb20, pushed). Full summary in
  `.docs/results/L1-covers.md`.
  - Import: `ImportResult.cover` (`ImportCover(name, bytes)`); `EpubImporter`
    detection chain = `properties="cover-image"` → `<meta name="cover"
    content=id>` → id/href contains "cover" + image type; first candidate
    that loads bytes from the zip wins; stored as `cover.<source-ext>`
    (jpg/png/gif/webp/jpeg kept; unknown → jpg).
  - `LibraryManager` writes `cover.*` right after reading (content before
    meta.json), hashes it into `manifestFiles`, sets `files.cover`;
    delete cascade already removes the whole doc dir (no change needed).
  - UI: `LibraryItem.cover` = store-relative path; new `CoverLoader`
    (pure `sampleSize` power-of-two math + `inJustDecodeBounds`/`inSampleSize`
    decode at 132×174 px + 8 MB bitmap LRU); `LibraryCard` shows a 44×58 dp
    `ContentScale.Crop` thumb when present, tinted spine bar stays as fallback.
  - Tests +11 → **148/148 green**; cover EPUBs built in-test
    (`EpubFixtures.epubWithCover`); shared PWA fixture untouched.
  - **Not done (next)**: damaged-doc banner (data side exists), tags/collections
    UI, reader splash image; R2b time-left remainder still pending.

- **Session M-11 (Oct 17, 2026)** — L1 residue slice: **launcher icon from the
  canonical logo** (commit b0863c4, pushed).
  - Asset story (don't re-derive): the canonical brand mark is
    **`folio-logo.png` at the repo root** (user-confirmed; transparent bg,
    orange-gradient mark, 1254×1254, alpha bbox 825×992). It SUPERSEDES the
    charcoal+violet `design/folio-icon` mark (that package traced
    `apps/web/public/icons/logo.png` — keep for reference only) for launcher
    purposes. Plate = charcoal #23242F (user choice, 3 previews reviewed).
  - `scripts/generate-android-icons.js` (new, zero-dep, same style as
    `scripts/generate-icons.js`): alpha-bbox trim → premultiplied-alpha bilinear
    resample → mark at 60/108 of the adaptive canvas, centered →
    `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png` +
    `ic_launcher_monochrome.png` (white RGB, alpha silhouette for themed
    icons) → `design/folio-icon/android/play-store-512.png` (M6 upload asset).
  - Adaptive XMLs now point at the raster layers; old
    `drawable/ic_launcher_foreground.xml` vector deleted (minSdk 26 → no
    legacy fallback needed). APK verified to carry all layers.
  - **BUG HIT + FIXED (hot)**: un-premultiply must be `px[c] / alpha`, not
    `px[c] * 255 / alpha` — the wrong form clamps every pixel to white (first
    generated set came out white; caught visually before commit).
  - Verification: launcher simulation (charcoal + 72/108 crop + circle mask)
    from the REAL generated 432px file — looks correct; build green;
    icon previews live in `%TEMP%\1\opencode\icon-preview` (scratch).
  - **Not done (next)**: covers slice — full plan in NEXT SESSION #1; PWA and
    mobile both still have NO cover code (mobile-first feature; schema-safe:
    `files.cover` already in the model).

- **Session M-10 (Oct 17, 2026)** — M4 search COMPLETE (commits da75560,
  ecff505 — pushed).
  - `core/search/`: `MiniSearch.kt` (MiniSearch 7.2.0 engine port: BM25
    k1.2/b0.7/d0.5, weights fuzzy0.45/prefix0.375, quality multiplier,
    Latin-only fuzzy 0.2 >4 chars), `SearchNormalise.kt` (NFC, ZWJ/ZWNJ strip,
    Latin-only accent fold, `[^\p{L}\p{M}\p{N}]+` tokenizer, Hindi stems
    `-यों/-ियों→-ी` + `-ों/-ें→∅` emitted alongside base), `SearchIndex.kt`
    (boosts title4/author3/tags3/text1, AND combine, 50-doc cap, grouped hits,
    snippet from raw text), `SearchSnippet.kt` (80-char window, `<mark>`,
    HTML-escape), `SearchUi.kt` (snippet HTML → display parts for Compose),
    `SearchService.kt` (library-wide build + query facade).
  - UI: search icon in the library top bar → `SearchPanel` (250 ms debounce,
    auto-focus, back closes, grouped by doc, highlighted snippets);
    hit tap → `ReaderActivity` with `EXTRA_TARGET_ORDER` (1-based spine,
    beats progress restore). `LibraryManager.getSearchText` reads the
    PWA-identical `library/index/text/<id>.json`.
  - Rewritten from the interrupted session's untracked draft: full M4 suite
    (28 new tests; 137/137 green). All PWA `search.test.ts` golden cases ported
    (inline Hindi fixtures; `packages/core/tests/fixtures/` has EPUBs only —
    no `hindi/` dir, the tracking-doc pointer was wrong).
  - **DECISION REVERSAL (user-approved 2026-10-17)**: SQLDelight + FTS5
    dropped for search — Android platform SQLite has no FTS5; in-memory
    MiniSearch port keeps 1:1 parity with the PWA reference. Decision 3 and
    the locked plan updated; `LibraryManager` comment fixed (was "FTS5 store
    supersedes in M4").
  - Gotchas: `decodeEntities` order matters — `&amp;` first then the rest;
    a ported test had a wrong expectation (`&#039;x&quot;` → `'x"` not `'x'`).
    Test report XMLs at `app/build/test-results/testDebugUnitTest` for counts.
  - **Next session**: L1 residue / device smoke / font packaging / R4.

- **Session M-9 (Oct 17, 2026)** — L1 library phase (commits a9787af, 01f5dfb,
  pushed).
  - `folio-logo.png` at the repo root committed — user confirmed it IS the
    canonical logo, to be used for icons later (location TBD; currently at
    root, untracked-then-tracked after the double-commit dance described in
    the R3/M-9 git noise).
  - `LibraryUi` (pure, 6 tests) + `ImportDispatch` (pure, 4 tests) — details in
    `.docs/results/L1-library.md`; MainActivity is now the real screen (cards
    + progress + menus + SAF import + ACTION_VIEW + delete confirm).
  - Gotchas recorded: FNV literal is Long; route tests need REAL pdf fixtures;
    use `git commit -F file` for multiline messages on this VM (PowerShell
    quoting splits multiline `-m`).
  - 109/109 tests green. Device smoke still blocked.
  - **Next session**: M4 search (recommended) / L1 residue (incl. favicon+icon
    work from folio-logo.png) / device smoke / font packaging.

- **Session M-8 (Oct 17, 2026)** — R2b comfort slice (commit 01ce6e5, pushed).
  - `PageTurnKeys` (pure, 4 tests): volume up=backward / down=forward
    (Play Books/Moon convention), MEDIA_NEXT/PREVIOUS (Bluetooth page-turners),
    PAGE_UP/DOWN, DPAD_LEFT/RIGHT → `OverflowableNavigator.goForward/goBackward`
    (non-suspend). **Hot fact: `android` KEYCODE_VOLUME_UP=24, VOLUME_DOWN=25**
    (I swapped them first — the JVM test caught it; don't trust memory here).
    Activity-level `onKeyDown` consumes `ACTION_DOWN` + `repeatCount==0` so
    keys never reach the WebView (no double-turn).
  - Brightness overlay: scrim `Box` (no pointerInput → touches fall through)
    UNDER the chrome so controls stay crisp; `br` stepper 30–100 in typography
    row; mapping `brightnessAlpha` pure + 3 tests. No schema change
    (`ReadingPrefs.brightness` pre-existed).
  - Pillar → interactive scrubber: Slider with a local drag value (release =
    seek via `LocatorService.locateProgression` → `navigator.go` on Main);
    during drag the % label shows the thumb value.
  - Manifest: `android:enableOnBackInvokedCallback="true"` (predictive back).
  - 100/100 tests green. R2b remainder moved to NEXT SESSION (time-left needs
    the ReadingPace/stats port).
  - NOTE: none of R2/R2b/R3 reader UI is device-testable yet (AVD-blocked).

- **Session M-7 (Oct 17, 2026)** — R3 annotations COMPLETE (commit 7ffd693, pushed).
  - **Key finding (already in note 17):** Readium 3.0.0 `rangeFromLocator()`
    anchors decorations from `locator.text` (Hypothesis TextQuoteAnchor) — the
    "CFI parser needed for reopen-rendering" premise from M-5/M-6 was wrong;
    no new parser subsystem.
  - `AnnotationLocators` (pure, 5 tests): annotation.location → reading-order
    spine index (epubcfi base ref or href exact/leading-slash match).
  - `HighlightManager`: `boot(saved)` hydrates persisted annotations at attach
    (ReaderActivity reads `LibraryManager.getAnnotations(docId)` after
    navigator mount); `update(annotation)`/`remove(id)` re-apply; activation
    event surfaced via `onDecorationActivated` callback (runOnUiThread) with
    `event.decoration.id` → live annotation lookup.
  - `AnnotationMenuSheet`: color chips (4 PWA hexes), note editor, delete,
    share — edits bump `updatedAt` + upsert via `saveAnnotation` (newer-wins
    merge contract intact).
  - `QuoteCardContent` (pure, 6 tests) + `QuoteCard` painter: shareable PNG
    quote-card (1200×630 charcoal/violet brand) via new FileProvider →
    ACTION_SEND. `FileProvider` added to manifest (authority
    `${applicationId}.fileprovider`).
  - 93/93 tests green (CANNOT device-smoke: no AVDs — verify
    `mobile/app/build/test-results/testDebugUnitTest`, 93 cases).
  - Full result: `.docs/results/R3-annotations.md`.
  - **Next session**: R2b comfort list / library phase / device smoke — see
    NEXT SESSION above. R3 remainder (dictionary/translate, per-chapter
    highlight list) intentionally deferred.

- **Session M-5 (Oct 17, 2026)** — R1 steps 1-2 + TTS dropped from v1 scope.
  - **TTS dropped** (user decision): R4 scope = footnote popups + media overlays.
  - Readium Kotlin Toolkit **3.0.0** pinned (Kotlin 1.9.24-compiled — our Kotlin
    2.0.21 cannot consume 3.1+ metadata). Shared/streamer/navigator only; NO lcp.
  - Core library desugaring enabled (Readium AAR metadata check requires it);
    desugar_jdk_libs 2.1.2.
  - `ReaderActivity`: doc-id intent → LibraryManager → `library/docs/<slug>/<reading>`
    via AppFileStore.resolve → `AssetRetriever` (DefaultHttpClient) + `EpubParser` +
    `PublicationOpener` (suspend, Try.getOrNull) → `EpubNavigatorFactory` →
    FragmentFactory → commitNow(EpubNavigatorFragment). Process-death path =
    `createDummyFactory()` + finish (Readium guide recipe). Listens
    `currentLocator` (StateFlow) → `ProgressBridge.buildProgress` →
    `LibraryManager.saveProgress` on Dispatchers.IO (distinctUntilChanged with
    0.4% noise gate + same-location gate).
  - `ProgressBridge`: CFI extraction (`epubcfi(` prefix), href fallback, percent
    default 0 — primitive-arg core (JVM-testable, 4 tests; Locator overloads thin
    because Android Uri needed — see hot note 10).
  - Manifest-registered; `activity_reader.xml` added; `ReaderActivity.createIntent`
    ready for the library UI (no nav yet — import UI is a later phase).
  - 68/68 tests green. Commits: ea670a6 (docs), b5642d9 (toolchain), 1bbb5d8 (reader
    + bridge), a24c770 (cleanup). All pushed.
  - **R1 step 3 (same session, 9028907)**: `ReaderPrefsMapper` (pure, 5 tests) —
    ReadingPrefs → Readium knobs with PWA parity (see hot notes 12-13);
    `ReaderPrefsAndroid.toEpubInitialPreferences/Defaults` thin adapter;
    ReaderActivity now passes initialPreferences + Configuration(defaults).
    MainActivity=Library harness (list docs → reader; debug button imports an
    original short sample text via TxtImporter so device E2E needs no files).
    FolioApplication registers `PDFBoxResourceLoader.init` (manifest
    android:name wired). 73/73 tests green.
  - **R1 step 4 (6add9cc, d694339)**: prefs persistence + reader chrome +
    chapter-accurate restore.
    - `PrefsStore` (core/store): ReadingPrefs at **library/user/settings.json**
      (schema-1 backup contract path; all-fields present; unknown keys
      tolerated on read; kotlinx prettyPrint=4-space accepted at value-level
      parity). 5 tests incl. full-key-shape lock.
    - `ReaderChrome` (Compose overlay in activity_reader.xml): back, theme
      menu (PWA token dots, light/warm/dark/true-black/system), Aa stepper
      (2px steps, clamp 14-32), flow toggle → `onPrefsChanged` → live
      `submitPreferences` + IO save to settings.json. Chrome surface/text
      colors follow the active theme.
    - `ProgressBridge.cfiSpineIndex`: decodes epub.js CFI spine ref
      (/6/<even>, ref=2*(spine+1)) → `ReaderActivity` restores chapter-accurate
      when location is CFI; percent fallback otherwise. Within-chapter offset
      restore stays OPEN (Readium 3.0.0 has NO CFI parser classes — verified;
      full-CFI restore needs own parser or the Readium 3.4 cfi module after a
      Kotlin 2.1 toolchain upgrade).
    - Fonts: 3.0.0 `Configuration` has only `defaults` — no
      FontFamilyDeclaration (3.1.2+). Custom profiles (Literata/Lora stacks,
      Devanagari fonts) require the toolchain upgrade OR @font-face injection;
      serif/sans/mono generic families work via FontFamily enum value class.
    - **R2 (cb7ed34, b6abc56)**: reader chrome rework (tap-middle-third toggles
      top bar via InputListener; always-visible bottom pillar = chapter title +
      %; secondary typography row = family menu / line-height / margins /
      justify) + selection→highlight groundwork (SelectionBridge pure core,
      HighlightManager decorations, chrome Highlight button). Progress saving
      throttled in-activity (lastSavedProgress) instead of distinctUntilChanged.
      82/82 tests green.
    - **Next**: R3 proper — annotation context menu (edit note/color/delete),
      rendering persisted highlights on reopen (needs CFI parser), quote-card
      share images; OR device smoke (still blocked: no AVDs/system images).
  - **Next session**: FIRST verify green build. Options in priority order:
    1. Device smoke (still blocked: no AVDs/system images on VM).
    2. R2: reader chrome polish — toggle-by-tap (hidden chrome), page-number
       pillar, within-chapter CFI offset investigation, annotation highlight
       engine (R3 needs DecorableNavigator.addDecorations — available in 3.0).
    3. Font packaging for Devanagari (bundled ttf + @font-face in the streamer
       resources) — decide vs Kotlin 2.1 upgrade to unlock Readium 3.1.2+
       FontFamilyDeclaration (cleaner).

- **Session M-4 continued (Oct 17, 2026)** — M3 phases 2+3: HTML + PDF importers. M3 COMPLETE.
  - `HtmlImporter` + jsoup (`org.jsoup:jsoup:1.18.1`): `articleSafelist()` = DOMPurify
    allow-list port (tags h1-6/p/list/blockquote/pre/code/table/figure/img/a/em/strong/
    sup/sub/hr/br/span/abbr/cite/q/mark/del/ins/u; td/th colspan/rowspan/scope; enforced
    a[rel=noopener noreferrer][target=_blank]; **explicit protocol allow-lists on
    a[href] and img[src]** — jsoup permits ALL schemes on attrs without them, incl.
    `file:`; `preserveRelativeLinks(true)`).
  - Sanitizer tests: script content removed, on* handlers stripped, javascript: href
    removed, iframe/object/form/svg/math/style stripped, safe protocols, hostile page
    end-to-end (clean html AND epub bytes contain no forbidden strings).
  - Structural article extraction (`<article>` → `<main>` → `[itemprop=articleBody]` →
    `[role=main]` → `div#content` → body; non-blank check only). DECISION: Readability
    scoring NOT ported; revisit with clipper. NOTE: a 200-char gate fell through to
    `<body>` for short saved pages — that gate was removed.
  - SingleFile data-URI images: regex extraction, cap 40, **content-hash names**
    (sha256-prefix.ext), `src="images/<name>"` rewrite, beyond-cap srcs blanked,
    **identical images deduped to one stored file** (zip duplicate-entry guard).
  - `PdfImporter` (`com.tom-roush:pdfbox-android:2.0.27.0`, namespace
    **com.tom_roush.pdfbox**, NOT org.apache): Info-dict title/author → filename
    fallback, per-page chapters "Page N" href `#page=N`, avg ≥50 chars/page →
    searchable, locked pdf → `PdfLockedException("This PDF is locked")`,
    no reading/clean editions (parity with TS). App must call
    `PDFBoxResourceLoader.init(context)` before use (noted in KDoc).
  - **PDFBox-on-JVM test infra gotchas (do not repeat)**: tom-roush loads resources
    from Android assets; on JVM it falls back to `getResourceAsStream`. Fixed by
    copying AAR `assets/com/tom_roush/{pdfbox/fontbox}` subset into
    `mobile/app/src/test/resources/` (glyphlist, text, afm, ttf/LiberationSans,
    fontbox unicode); CJK cmaps (~3.2 MB) intentionally omitted. Plus
    `testOptions { unitTests.isReturnDefaultValues = true }` for android.util stubs.
    `PDType1Font` static init NEEDS those resources — raw-PDF fixtures are written
    by hand (`PdfFixtures.buildPdf`) to avoid PDFBox writer+fonts in tests.
  - Fixture-builder gotcha: keep object-ref arithmetic inside parens
    (`${3 + (it - 1) * 2}`) — precedence bug made Kids point at wrong objects.
  - 64/64 tests green. Commits: 9c1ec65 (HTML), d5ca39d (PDF + test resources),
    98a263a (stray-dir cleanup). All pushed. Full result: `.docs/results/M3-import.md`.
  - **Next session: confirm green build, then start R1 EPUB reader (Readium Kotlin
    Toolkit) — add readium deps per `.docs/results/M3-import.md` next-steps, or M4
    search if R1 toolchain is delayed.**

- **Session M-3 (Oct 17, 2026)** — M2 storage layer complete.
  - `FileStore` interface port (suspend fns; blocking IO → Dispatchers.IO by caller).
  - `AppFileStore`: app-private root (injectable File for tests), path-traversal
    guards ("..", absolute, drive letters), atomic writes = sibling temp + fsync +
    ATOMIC_MOVE, non-recursive dir remove rejected.
  - `Hashing.kt`: sha256Hex (vector-tested) + createDocSlug exact port (60-char cap,
    "doc" fallback for Hindi, 4 hex suffix).
  - `ImportResult.kt`: importer output contract (pure bytes→result, save separate).
  - `LibraryManager` port: getManifest (corrupt→reinit), findDuplicate, addDocument
    (exact §4 write order, manifest LAST), listDocuments, damagedDocuments (never
    silently dropped), updateDocMeta, deleteDocument cascade, recoverInterruptedImports,
    progress/annotations/collections ops (mutex-serialized).
  - 24/24 tests green. Commit b5d2a4a pushed. Summary: `.docs/results/M2-storage.md`.
  - Next session: M3 import pipeline (EPUB DRM detect → pdf → html+sanitizer → txt).

- **Session M-2 (Oct 17, 2026)** — M1 verified green + M2 model port.
  - `.\gradlew assembleDebug` GREEN: app-debug.apk 16.2 MB produced.
  - Added kotlinx.serialization plugin 1.7.3 (catalog) + schema-1 model port
    `dev.folio.mobile.core.model.Types.kt` (DocMeta/Manifest/Annotation/Progress/
    Collection/ReadingPrefs — field names identical to packages/core/src/types.ts).
  - Added `TypesContractTest` (5 tests): PWA JSON literals parse, enum strings match,
    compact-JSON tolerance, unknown-enum fails loudly.
  - Fixes en route: XML comment `--` ban (colors.xml), dropped compileOptions restored
    (JVM-target 1.8 vs 17 mismatch), serialization-json moved testImplementation→
    implementation, enum @SerialName rewrite.
  - Commits: 27b1155 (pushed). Session log M-1 below. Result: `.docs/results/M1-scaffold.md`.

- **Session M-1 (Oct 17, 2026)** — M1 scaffold.
  - Created `mobile/` Gradle project (separate from PWA code, user instruction):
    version catalog, settings/gradle.properties, app module, MainActivity (Compose
    placeholder), theme (Folio token palette), XML adaptive icon, wrapper 8.10.2.
  - Fix: `buildFeatures` must live inside `android{}` (config-phase failure; wrapper
    gen aborted the first time).
  - Commits: e88d3f7 (scaffold), b94b547 (fix + wrapper). Both pushed.
  - `.\gradlew assembleDebug` running at session time — check status on resume;
    if red, read output and fix before anything else.
  - Result summary: `.docs/results/M1-scaffold.md`.
  - Next: verify green build → M2 core data layer port (types + FileStore + fixtures).
