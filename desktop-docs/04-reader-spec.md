# Folio Desktop — Reader Spec

The reader is the app's core. Port sources:
`mobile/.../core/reader/{ProgressBridge,ReaderResume,ReaderPrefsMapper,PageTurnKeys,ReadingPaceStats,ReaderSearch}.kt`,
`mobile/.../reader/*` (Activity, sheets, AnnotationMenu), and the PWA `docs/spec.md` §7.

Engine: **Readium web toolkit** (`@readium/*`), replacing the Android
`EpubNavigatorFragment` (same toolkit family, same semantics).

---

## 1. Opening a book

```
open(docId, opts): 
 1. read meta.json → find reading/book.epub path
 2. parse with Readium EpubParser (web equivalent); fail → dialog "This book can't be opened"
    with options Try again / Back to library
 3. instantiate navigator with ReadingPrefs (doc override merged over global, §3)
 4. set theme → mount splash (cover + spinner)
 5. restore location (§ resume cascade) once navigator ready
 6. on FIRST locator → fade out splash (cross-fade 300 ms)
 7. 8-second failsafe: if no locator, drop the splash and show an error state
```

## 2. Reading themes (schema-1 `theme` values)

| theme | page bg | page text | Readium theme |
|---|---|---|---|
| `light` | `#FAFAF7` | `#1C1B19` | LIGHT + colors |
| `warm` (Paper) | `#F4ECD8` | `#3B3226` | SEPIA + colors |
| `dark` | `#121214` | `#E6E3DC` | DARK + colors |
| `true-black` | `#000000` | `#C8C4BC` | DARK + colors |
| `system` | resolves with OS dark mode: light scheme → `light` values, dark scheme → `dark` values | resolves | LIGHT or DARK |

Reader chrome surface/text pairs follow the resolved theme (values §5.3 of `03-ui-ux-spec.md`).
The reader canvas is themed **independently** of the app UI (principle #3 in `03`);

## 3. ReadingPrefs schema-1 fields and semantics

```ts
interface ReadingPrefs {
  schema?: 1
  theme: "light"|"warm"|"dark"|"true-black"|"system"      // default "light"
  fontFamily: "serif"|"sans"|"mono"|"publisher"           // default "serif"
  fontSize: number        // 14..32 px (defaults 18) → Readium relative factor = px/16
  lineHeight: number      // 1.3..2.4 (default 1.6)
  margin: number          // 12..64 px (default 20) → convert to em at 16px base
  justify: boolean        // default false
  hyphenate: boolean      // default false
  paragraphSpacing: number // em 0..(tool default max .8) → CSS userspace var
  letterSpacing: number    // em 0..0.1 step .01 → CSS userspace var
  maxWidth: number        // legacy-inert on mobile; DESKTOP MAY HONOR as column cap
  brightness: number      // 30..100 default 100 → scrim, see §6
  flow: "paginated"|"scrolled"
  spread: "none"|"auto"|"both"
  pdfZoom: string         // BYTE-COMPAT DEAD FIELD — persist but ignore
}
```

Constraints enforced in the Appearance sheet (mobile parity):

- Theme picker always lists: Light / Paper / Dark / True black / System.
- Font size control: `−` button, slider, `+` button; step 1.
- "Reset to defaults" button restores the GLOBAL defaults (not the schema defaults)
  and states the doc-overrides will be removed for this book.
- Per-book scope note in sheet footer: "Changes made inside a book stay with that book."
  (writes to `user/doc-settings.json[docId]`, NOT `settings.json`).
- Reset button removes ONLY the current doc override; the global default stays.

## 4. Typeface mapping

| value | CSS stack |
|---|---|
| serif | `"Iowan Old Style", "Palatino Linotype", Georgia, serif` |
| sans | `"Segoe UI", system-ui, sans-serif` |
| mono | `"Cascadia Code", Consolas, monospace` |
| publisher | `null` → do not send a font-family; honor EPUB css |

## 5. Flow and input

- `flow=paginated` (default): horizontal page navigation (Readium paginated). Column snapping driven by the
  navigator.
- `flow=scrolled`: continuous scroll; Chrome hides only while the surface is scrolled
  near the top of the docs.
- Drag the progress slider → jump to `progression` value (0..1) via navigator
  `locateProgression`-equivalent; thumb grows while dragging; chrome stays for 1.2 s
  grace after scrubbing.
- Click zones: left third = back, right third = forward, middle third toggle chrome.
  (Desktop: also show hover chevrons At edges — see 07.)
- Keyboard: full matrix in `07-keyboard-and-input.md`. Forward = centered on more
  content; back = opposite. Animated turns, no ordering issues.
- Prevent body overscroll from navigating/reloading the webview (important on scroll flow).

## 6. Brightness scrim

- `brightness=100` → no scrim, screen normal.
- Otherwise a black overlay `alpha = (100 − brightness)/100 * 0.7` **under the
  chrome** but over the canvas, with `pointer-events: none`.
- Slider lives in the Appearance panel, range 30–100.

## 7. Selection → annotations pipeline (port of `SelectionBridge.kt` + `AnnotationMenu`)

- Use the webview's native selection (or Readium selection events). When user lifts the
  pointer, show a floating toolbar anchored under/over the selection with actions in
  this order:
  1. **four color swatches** (yellow/green/blue/pink) → creates a highlight of that
     color immediately (already-highlighted selection with matching color & quote →
     removes the highlight);
  2. **Add note** → floating note prompt sheet (mobile's inline note field) that
     stores aNOTATION kind=`note` whose `quote` is the selected text (color picked
     first defaults yellow when the user changes their mind);
  3. **Copy** → clipboard copy of the selection (quote);
  4. **Meaning** → dictionary card (P2 phase; dictionary spec §`06`).
- Annotation creation contract — generate and store:
  - `docId`, `chapterId` (resolved chapter id; may be null when locator lacks href),
  - `quote` — trimmed text; `prefix` = up to 32 chars before; `suffix` = up to 32 after,
  - `location` = Readium locator string (engine hint; quote+context is authoritative),
  - `kind`, `color`, `createdAt`/`updatedAt` ISO 8601 UTC.
- Rendering highlights: on navigator ready, walk annotations for this doc and paint
  decoration with the swatch color at ~35% page-opacity overlay (mobile uses
  `Decoration/TextQuoteAnchor`; the web toolkit exposes decoration application).
  Re-anchor by quote+context when clean CFI is unavailable.
- Bookmarks: `kind=bookmark` with `quote=""`, `location` filled; add via ⋯ → "Add
  bookmark" in reader top bar; toggling existing bookmark at the same locator deletes it.

## 8. Progress tracking + resume (port of `ProgressBridge.kt` + `ReaderResume.kt`)

- On each locator (debounced): percent = total progression ×100 (one decimal),
  location = locator CFI (prefer `epubcfi(...)` string; if the toolkit does not
  provide CFI, store `href#<chapter-href>` or a spine-index string — the SAME
  path-derived format the mobile app writes).
- **Write gates**: save if changed ≥ 0.4% absolute OR 500 ms since last write, and at
  least every 3 s while turning; flush-on-close (window close / route leave) always
  writes final (only if changed at all) to `user/progress.json`.
- **Restore cascade** (~identical to mobile):
  1. explicit `opts.locatorOverride` (from search hit / annotation tap) — first try the
     locator's chapter href, then, if text anchor info (quote+prefix+suffix) is
     available, perform a second anchored `go()` for a precise spot;
  2. else saved `progress.location`:
     - if it contains a CFI → resolve chapter; recompute the in-chapter fraction
       using `saved.percent` against the navigator's positions list (same trick
       mobile uses since Readium 3.x has no CFI parser — the web toolkit DOES parse
       CFI, so prefer direct CFI application; keep the fraction recompute as fallback);
     - otherwise treat as plain percent → `locateProgression`.
  3. else first spine item.
- After the restore `go()` completes, immediately write progress.json once (prevents
  drift). No `seek` writes for the first 60 s to avoid competing loops.

## 9. Reading pace (port of `ReadingPaceStats.kt`)

- State: `{ sessionsMs, percentRead }`, kept per-doc in `Progress.pace`.
- Clock: accumulate wall-clock time ONLY between two *forward* continuous moves
  (delta < 5 percentage points). A jump, scrub, sheet open > 5 minutes, or app
  suspension break the session (do not credit that gap).
- Time-to-finish: `remaining minutes = sessionsMs/1000/60 * ((100 − percent) /
  percentRead)` — guard `percentRead > 0`; display in the pillar as
  "≈ N min left" (N < 60) / "≈ N h left" / hidden if unestimatable.
- Only forward movement credits; back-page moves don't add time.

## 10. In-book search (port of `ReaderSearch.kt`)

- Readium search service over the current publication (matches all chapters). Results
  stream into a dropdown: chapter group title + highlighted snippet (query term wrapped,
  mobile `SearchSnippet.kt` style: prefix … term(s) bolded … suffix).
- Tap a result → jump to its locator; if the search hit has text anchors, do the
  two-step jump (chapter then text anchor) exactly like annotation taps.
- Query normalization same as library search (NFC, lowercase, strip ZWJ/ZWNJ, latin
  accent-folding, PRESERVE Devanagari combining marks).

## 11. Other reader behaviors

- **Splash**: cover from `cover.*` or bundled fallback art (`DefaultCoverArt.kt`
  behavior: tint from the 8-tint family by colorSeed + first-letter/note motif).
- **Screen awake**: while the reader is the active route and the doc is being read
  (locator events in last 45 s), call Rust `prevent_display_sleep(true)`; reset on
  route leave / inactivity. Setting toggle stored in `user/settings.json`? — NO:
  desktop-only add `user/window.json {preventSleepWhileReading:true/false}` default true.
- **External links**: intercept link taps → if `http(s)`, open system browser; if
  internal spine link, navigate the reader.
- **Copy quote**: selection → Copy puts plain text in the clipboard (not attributed).
- **Quote card** (P1): "…" menu → Share as image: render quote card to canvas
  (charcoal `#23242F` plate, violet `#715CFC` mark, book title/author, quote + marks)
  → Save PNG (native save dialog) or Copy image to clipboard.
- **Errors**: navigator load failure → fullscreen error state with Retry / Back.

## 12. Performance budget

- Cold open of a 3 MB EPUB: parser → first locator ≤ 1.2 s on a mid-range laptop
  (splash covers the gap).
- Page turn: input → visual change ≤ 80 ms (navigator's native turn).
- Highlight paint of 200 annotations on open ≤ 400 ms (batch decoration application).

