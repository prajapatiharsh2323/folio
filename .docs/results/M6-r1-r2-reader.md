# R1 steps 3–4 + R2 — Reader prefs, chrome, selection groundwork (Result Summary)

**Session:** M-6 · **Date:** Oct 17, 2026
**Commits:** 9028907 → aa393dc → 5fe47ff → 6add9cc → d694339 → b89a983 →
7e72432 → cb7ed34 → b6abc56 → 4d55392 (all pushed to `origin/main`)
**Tests: 82/82 green** · `testDebugUnitTest assembleDebug` green

## Scope change
- **TTS read-aloud DROPPED from v1** (user decision). R4 = footnote popups +
  media overlays only. Post-v1 backlog, not debt.

## What was built

```
core/reader/ReaderPrefsMapper.kt   ReadingPrefs → Readium knobs (pure, 5 tests)
reader/ReaderPrefsAndroid.kt       thin adapter → EpubPreferences/EpubDefaults
core/store/PrefsStore.kt           library/user/settings.json persistence (5 tests)
core/annotation/SelectionBridge.kt selection → schema-1 Annotation (pure, 3 tests)
reader/HighlightManager.kt         session Locator map + applyDecorations
reader/ReaderChrome.kt             chrome overlay (top bar + typography row + pillar)
reader/ReaderActivity.kt           prefs load/apply/persist + tap-toggle + highlight
reader/ReaderPrefsAndroid.kt       PWA token → Color/FontFamily mapping
MainActivity.kt                    library harness (list → reader; sample import)
FolioApplication.kt                PDFBoxResourceLoader.init
```

## PWA-parity decisions (locked in code + tests)

- **ReadingPrefs → Readium value semantics**: schema `fontSize`/`margin` are PX →
  divided by 16 into Readium's relative `fontSize` factor / `pageMargins` em-factor
  (v1 approximation, marked). `lineHeight` passthrough. Theme keys resolve via the
  exact `THEME_COLORS` tokens from `apps/web/src/reader/epub-engine.ts`; warm→SEPIA,
  dark+true-black→DARK, system→light (native v1); spread none→NEVER, both→ALWAYS;
  `fontFamily: publisher` → null override.
- **`publisherStyles = false`** so typography prefs actually apply (Readium gates
  lineHeight/textAlign/hyphens/letterSpacing/paragraphSpacing behind it; the PWA's
  styles always win, so this matches).
- **settings.json**: bare `ReadingPrefs`, all 14 fields present, unknown keys
  tolerated on read, path `library/user/settings.json` (schema-1 backup contract).
- **Annotation parity**: `prefix = before.takeLast(32)`, `suffix = after.take(32)`,
  `location` = CFI string (matches `epub-engine.ts` lines 633–634).
- **Progress restore**: `cfiSpineIndex` decodes the epub.js CFI spine ref
  (`/6/<even>`, ref = 2×(spine+1)) → chapter-accurate restore; percent fallback
  otherwise. Within-chapter offset stays OPEN (Readium 3.0.0 has no CFI parser —
  verified against the AAR class lists).

## Readium 3.0.0 API facts (verified against AAR bytecode; don't re-derive)

| Area | Fact |
|---|---|
| Version | 3.0.0 = last release compiled with Kotlin <2.1 (our 2.0.21 can't read 3.1+ metadata); requires core library desugaring |
| Open pub | `AssetRetriever(contentResolver, httpClient)` + `EpubParser()` + `PublicationOpener(parser)` (suspend `open(asset, allowUserInteraction=true)` → `Try`) |
| Link.href | `Href` object → `link.href.resolve(Url("/"))` for a plain `Url` |
| Locator ctor | `(href: Url, mediaType: MediaType, ...)` — mediaType NOT defaulted; use `MediaType.XHTML` |
| Prefs enums | `Theme{LIGHT,DARK,SEPIA}`, `Spread{AUTO,NEVER,ALWAYS}`, `ColumnCount{AUTO,ONE,TWO}`, `TextAlign{JUSTIFY,...}`, `ReadingProgression{LTR,RTL}`; `FontFamily`/`Color` are value classes (over String / Int ARGB) |
| Configuration | 3.0.0 has ONLY `defaults` — **no FontFamilyDeclaration** (3.1.2+) |
| Selection | `SelectableNavigator.currentSelection()` is a **suspend** fn → `Selection?` (no flow); `Selection(locator, rect)`; `clearSelection()` |
| Decorations | `DecorableNavigator.applyDecorations(List<Decoration>, group)` — **batch only** (no single-add in 3.0); `Decoration(id, locator, style, extras)`; `Style.Highlight(tint: Int, isActive: Boolean)`; `addDecorationListener(group, Listener{onDecorationActivated(event): Boolean})` |
| Input | `InputListener.onTap(TapEvent): Boolean` via `addInputListener`; return true consumes |
| Locator.Text | `(before, highlight, after)` — all NULLABLE in Kotlin metadata (use `.orEmpty()`) |
| Fragment state | `currentLocator` is a `StateFlow<Locator>` (3.0.0); restore has NO `Publication.locate/locateProgression` → use `findService(LocatorService)` |
| Fonts | generic serif/sans/mono work; custom stacks need toolchain upgrade or @font-face |

## Known to-dos (carried into next session)

1. **R3 proper** — annotation context menu (edit note/color/delete) on
   `onDecorationActivated`; render persisted highlights on reopen (needs the CFI
   parser); quote-card share images.
2. **Device smoke** — blocked (no AVDs/system images on this VM). Unblock with
   `sdkmanager` + `avdmanager`, budget as its own step.
3. **Font packaging** — Devanagari/Literata: bundle ttf + @font-face vs
   Kotlin 2.1 upgrade to unlock Readium 3.1.2+ `FontFamilyDeclaration`.
4. **Within-chapter CFI restore** — needs own parser or the Readium 3.4 cfi module.

## Next session

1. Read `.docs/MOBILE-TRACKING.md` first (status + "NEXT SESSION" block at top).
2. Verify `.\gradlew testDebugUnitTest assembleDebug` green.
3. Pick R3 / device-smoke-unblock / fonts per the priorities in the tracking file.
