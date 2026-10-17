# Folio Mobile — Android Rewrite Tracking (AI context file)

> **Read this file FIRST at the start of every session working on `mobile/`.**
> Sessions are short-lived (~30–40 min, AI memory erased). Commit + push to
> `origin/main` (prajapatiharsh2323/folio) after **every** meaningful change, and
> update this file before the session ends. Companion to `.docs/SESSION-TRACKING.md`
> (which covers the PWA side). Result summaries for major changes go in
> `.docs/results/` with `M*` prefixes.

## Status: M3 COMPLETE (64-test import pipeline) + R1 steps 1–4 DONE + R2 DONE + R3 DONE (annotations: persisted-highlight rendering + context menu + quote-card share) — 93/93 tests green. TTS dropped from v1 scope. Device smoke still blocked (no AVDs/system images on VM).

## NEXT SESSION — what to build (pick one, verify green build first)

1. **Library phase (recommended next)** — polish MainActivity (real list UI with
   delete/rename/covers, per-chapter highlight list screen, library search later
   gated on M4), import UI beyond the debug button (SAF intent filters for
   .epub/.pdf/.html/.txt with FileDetector).
2. **R2b remainder** — chapter-aware scrubber labels + time-left (needs the
   ReadingPace/stats port from packages/core reading/stats.ts), page-turn anims,
   paragraph-snap, tap-zone config, per-book typography overrides, image zoom,
   volume-key on/off setting (needs a schema-1 settings decision if added).
3. **Device smoke test** — still BLOCKED: no AVDs/system images on this VM.
   Unblock = `sdkmanager "system-images;android-34;google_apis;x86_64"` (~1 GB) +
   `avdmanager create avd`; budget it as its own step. Then: `adb install`,
   tap "Import sample text" → open doc → verify rendering, prefs, progress,
   highlight reopen-rendering + context menu + quote-card share (R3),
   volume-key turning + seek + brightness (R2b — all untested on-device so far).
4. **Font packaging** — Devanagari/Literata: either bundle ttf + @font-face, or
   upgrade Kotlin→2.1.x to unlock Readium 3.1.2+ `FontFamilyDeclaration`.
   Decision needed (own session).

**Carried constraints:** TTS is DROPPED (post-v1 backlog, not debt). PWA schema-1
contracts (manifest, user/*.json, backup ZIP, annotation anchors, Drive sync) must
stay byte-compatible — fixture changes go in BOTH suites in the same commit.

## The locked plan (agreed with user, Oct 17 2026)

- **Route B chosen**: full native Kotlin/Compose rewrite (NOT Capacitor, NOT TWA).
- Search: SQLite/FTS5 via SQLDelight + pre-normalized shadow text (Devanagari-safe).
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
| M4 | Search: SQLDelight FTS5 + normaliser port; manifest+index single transaction; Hindi fixture suite | pending |
| M5 | Notes/collections/backup/Drive: streaming ZIP backup, merge/replace (typed REPLACE), WorkManager auto-backup, AppAuth + Drive REST mirror | pending |
| M6 | Play hardening: signing, AAB, R8 rules, baseline profile, Data Safety, in-app updates | pending |
| M7 | CI: lint/detekt on PR, emulator instrumented job, AAB artifact, release-on-tag | pending |

## Key decisions & rationale (do not re-litigate without user)

1. **Native rewrite, not Capacitor** — user explicitly chose Route B over recommendation.
2. **Readium Kotlin Toolkit** for EPUB (replaces epub.js): native rendering, Locator model,
   no iframe/CSS-war, TTS + media-overlays support built in.
3. **SQLDelight + FTS5** (not Room — Room only supports FTS4).
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

## Environment facts (fresh sessions: re-verify, don't assume)

- Windows host; repo `D:\folio`; branch `main`; remote origin = prajapatiharsh2323/folio.
- JDK 17 (Temurin 17.0.20.1) on PATH; `ANDROID_HOME=C:\Android\android-sdk`
  (platforms 34–37.2, build-tools 34–37, emulator, NDK, licenses OK).
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
