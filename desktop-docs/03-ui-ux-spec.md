# Folio Desktop — UI/UX Spec

**Port source (authoritative, 1:1):** `.docs/design-system.md`
**Other sources:** `.docs/ux-improvement.md`, `mobile/app/src/main/kotlin/dev/folio/mobile/ui/theme/*`
**Desktop adaptation principle:** *the window frame replaces Android system UI; everything
else maps to the same components with bigger targets and hover states.*

---

## 1. Design principles (from the design system, unchanged)

1. **Books are the content, UI is the frame.** Reader chrome auto-hides; library chrome is quiet.
2. **Editorial styling, not app-y styling.** Thin hairline dividers, generous whitespace,
   serif-leaning type, restrained color.
3. **Two independent color systems:** app-UI palette vs. reader-canvas palette. Never swap them.
4. **Sanity of rhythm:** every number comes from the token tables in §2/§3.

## 2. Color tokens

### App UI · light

| Token | Value | Usage |
|---|---|---|
| canvas | `#F7F6F1` | app background |
| surface | `#FFFEFB` | panels, sheets, elevated groups |
| surface-subtle | `#F1F0E9` | inputs, quiet panels |
| surface-raised | `#FFFFFF` | dialogs (rare) |
| text-primary | `#22251F` | deep ink |
| text-secondary | `#676A61` | helper copy |
| text-muted | `#87897F` | non-essential metadata |
| border | `#E1E0D6` | hairlines, outlines |
| accent | `#355847` (pressed `#294638`) | evergreen primary |
| accent-container | `#E2EAE1` | selected chips |
| on-accent | `#FFFDF7` | |
| success / warning / danger | `#356B4D` / `#94692E` / `#A44C43` | recovery notices; danger containers `#F6E4E1` on `#5C241E` |
| scrim | `rgba(20,23,19,.6)` | modal backdrops |

### App UI · dark

canvas `#141713` · surface `#1C211C` · subtle `#242B24` · raised `#293029` ·
text `#ECEFE7` / `#B2B7AB` / `#8D968A` · border `#343D35` ·
accent sage `#A7C4A6` (pressed `#BED4BB`) · accent-container `#2B3C2F` ·
success `#8CC39B` · warning `#D8B77E` · danger `#E0A09A` · scrim `rgba(0,0,0,.6)`.

Dark is **not** pure black (§2.4 of the design system). App dark mode follows Windows
with a three-way setting: Light / Dark / System (mobile is Compose-defaulted; desktop
adds an explicit toggle in Settings → Appearance).

### Placeholder cover tints (going from `CoverTints.kt`)

`sage-grey #D8DDD0`, `parchment #E5DECD`, `eucalyptus #CFD8CF`, `clay #E0D4C6`,
`slate #CFD4D9`, `oat #E2DBD0`, `stone #D4CEC0`, `moss #CBD3C4`; ink `#3A4038`.
Selection by **stable `colorSeed`** derived from doc id (deterministic per book).

### Highlight swatches (shared with reader chrome)

`yellow #FFF3B0`, `green #CDE8C2`, `blue #BFDFF7`, `pink #F8CFE0`.

## 3. Typography

System sans stack (desktop: `Segoe UI` first, then SF/Roboto fallbacks),
with a **display serif** (`Georgia`, `Iowan Old Style`, serif) for hero items.

| Role | Size / line | Weight | Notes |
|---|---|---|---|
| displayTitle | 28 / 34 | 600 | empty-state titles |
| title L | 22 / 28 | 600 | sections in panels |
| title | 18 / 24 | 600 | screen titles |
| body | 15 / 22 | 400 | standard copy |
| body-strong | 15 / 22 | 500 | list primary text |
| label | 13 / 18 | 500 | chips, list secondary |
| micro | 11 / 14 | 500 | overline assist (e.g., "UPDATED YESTERDAY") |

Reader canvas typography is **not** this system — it's `ReadingPrefs` (§reader spec).

## 4. Spacing, size and radii (port of `FolioTokens.kt`)

Spacing x1..x12 = **4, 8, 12, 16, 20, 24, 32, 40, 48** px.

| Token | Value | On desktop |
|---|---|---|
| touchTarget | 48 px | interactive minimum (keep it; also aids pointer precision) |
| icon | 24 px / small 20 px | |
| search/button height | 50 px | |
| chip height | 32 px | |
| settings row | 56 px min | |
| book cover thumb | 56×78 px, r=4 | larger hover (=1) only |
| divider | 1 px hairline | |
| progress track | 3 px | |
| screen inset | 20 px → **32 px on desktop** | one scale step up on wide canvas |
| radii: cover 4 · control 8 · field 12 · card 14 · **panel 16** · pill ∞ | | mobile sheets r=24 stay for dialogs |

## 5. Screen architecture

### 5.1 Window frame

- **Title area**: app logo + current screen title; on hover, standard traffic-light-less
  Windows title bar (keep native — don't custom-draw) with Folio's light/dark; only the
  reader goes fully custom-drawn when in fullscreen-immersive mode.
- **Left sidebar (Library only)**: quiet 232 px nav — Library, Collections, Notes,
  Settings. Sidebar tone = `surface`, active = accent-container pill. **Hidden entirely
  inside the reader** (the reader keeps its own overlay chrome).

### 5.2 Library (port of `MainActivity.kt`)

Layout:

```
| sidebar | toolbar: "Library" + count (n documents)      [+ Search icon] [+ Sort ▾] [+ More ▾] |
|         | optional status banners (damaged docs, interrupted imports)                       |
|         | filter chips row: [All] [#tag…] [▾ collections…]            + Import + “+” |
|         | list rows (LazyColumn → virtualized list)                                          |
```

- **Rows** (`FolioBookRow`): 56×78 r4 cover + title/author (body-strong + label) +
  kind text ("Book"/"Article") + trailing progress % and a tiny 3-px progress line.
  On desktop add a hover state (surface-subtle) and a "…" button visible-on-hover for
  the Details / Delete menu.
- **Selection mode**: long-press → replaced by checkbox-on-hover + select-all checkbox
  in the toolbar; selecting shows a top bar: count + Add to collection ▾ + Remove from
  collection (when filtered by one) + Clear.
- **Empty state** (`FolioEmptyState`): display serif title "Nothing here yet",
  explanation, and a primary accent button "Import a file" (+ drag-and-drop hint).
- **Recovery banners** go in-warning tone with action buttons (Remove… / Finish
  imports…) opening dialogs.

### 5.3 Reader (port of ReaderActivity overlay)

```
┌────────────────────── reader window (canvas = reading theme) ──────────────┐
│   [top bar: ◀ back | title (truncate center) | Aa | 🔍 | ⋯] auto-hide     │
│                                                                           │
│            Readium web navigator canvas  (theme/hyphen/…)                 │
│            selection → floating 4-act toolbar (see 04)                    │
│                                                                           │
│   brightness scrim (under chrome), bottom pill:                           │
│   [chapter · 32% · 12 min left      ─────●──────] auto-hide w/ grace      │
└───────────────────────────────────────────────────────────────────────────┘
```

- **Top bar**: compact, non-scrolling, chrome-surface colors per reading theme
  (mobile `ReaderChrome.chromeSurface/chromeText`): light→`rgba(255,254,251,.92)`,
  dark→`rgba(18,18,20,.92)`, paper→`rgba(244,236,216,.94)`, true-black→`#000` @ .92.
  On desktop add **hover + tooltips**; keep the same icons: back, title, Aa,
  in-book search, ⋯ (TOC / Add bookmark).
- **Bottom pillar**: rounded pill (r ∞, chrome surface @ .94 opacity + subtle shadow),
  contains chapter title (truncate) · percent · time-left, and a slim scrubber slider
  whose thumb grows on hover/drag. Auto-hides 1.2 s after scrubbing stops.
- Page-turn **click zones** (left/right third), middle third toggles chrome — same
  geometry as mobile (§07 keys/clicks doc for the full matrix).
- Reader splash: theme-matching canvas + centered cover (or generated default art) +
  spinner; the nav shows only after the first locator.
- External links: open the system browser via Tauri shell plugin, with an in-reader
  neutral toast "Opened in browser — back to Folio". No embedded browsing.

### 5.4 Sheets & panels

Mobile bottom sheets → desktop **right-anchored panels** for: Details, TOC, Appearance,
collections pickers, note editor; small centric **dialogs** only for confirmations
(destructive action, drive consent, backup restore mode).

| Mobile sheet | Desktop surface |
|---|---|
| Appearance (Aa) | Right panel 360 px |
| TOC | Right panel 360 px, scrollable, section-maintains |
| In-book search | Top-anchored overlay bar + results dropdown |
| Selection menu | In-canvas floating toolbar anchored at the selection |
| Dictionary meaning | Floating card above the selection (re-themed per reading theme, like mobile re-theming the dictionary sheet) |
| Details (tags+collections) | Right panel 400 px |
| Quote card share | Centered dialog with canvas preview + Save PNG / Copy buttons |
| Note editor | Right panel (multi-line) |

All panels: surface color, hairline left border, x6 padding, ESC/backdrop closes,
focus-trapped, themed with the app theme (never the reading theme — except the
dictionary card).

### 5.5 Collections (port of `LibraryCollections.kt`)

Two-level flow unchanged: gallery of collection cards (cover fan preview + name + count
+ "Updated <date>") → detail view (rows w/ Remove from collection, Add via search +
multi-select picker, Rename, Delete w/ confirm). On desktop the detail is a route, not
a panel — buttons live in the toolbar.

### 5.6 Notes (port of `NotesActivity.kt`)

- Same toolbar (count subtitle), filter pills **All / Highlights / Notes / Bookmarks**
  (mobile `AnnotationFilters.kt`), rows with a **3 px color rail** using the swatch
  colors (bookmarks: neutral accent rail).
- Row: quote (body, 3-line clamp) + note preview (label, secondary), badges: "Has
  note", "Bookmark".
- Tap → open the reader at that annotation's exact location (chapter first; second,
  text-quote-anchored jump).
- ⋯ menu: Edit note (dialog with multiline field; Save / Remove note), Delete (confirm).

### 5.7 Settings (port of `SettingsActivity.kt`, `FolioSettingsRow`)

Grouped rows, each group with a `FolioSectionHeader`; row min-height 56 px.

Groups (order):

1. **Reading** — Default reading theme picker: horizontal scroll of swatch cards
   (Light / Paper / Dark / True black / System) with edge fade masks; helper text
   "Changes made inside a book stay with that book."
   *(desktop addition: App appearance Light/Dark/System row)*
2. **Backup** — Export backup / Restore backup (restore asks Merge or Replace with a
   summary count; see `06` § backup).
3. **Cloud sync** (`ui/sync/CloudSyncSection.kt`) — pick Google account, first-sync
   consent, auto-sync toggle, "Sync now" + inline progress/status, failure banner w/
   retry (see `06` § sync). Desktop: re-auth happens in a browser pop-out; see
   `09-platform-decisions.md`.
4. **Storage** — Library size / Folio data / Free space (Rust `StatFs` equivalent).
5. **About** — version, licenses (the dictionary pack is GPL-2.0 and must be credited:
   "Folio Dictionary (English→Hindi) — FreeDict, GPL-2.0").

## 6. Motion (port of the design-system motion spec)

- Durations: **140 ms** (hover/focus), **220 ms** (panel/sheet enter/exit,
  `cubic-bezier(.2,.7,.2,1)`), **300 ms** (page-turn fade/slide from the navigator).
- Cross-fade when opening a book (library → reader splash → canvas).
- Chips: pressed scale to 0.97. Rows: hover background 140 ms. NO bounce/spring.
- Page turn is driven by the navigator itself; never dim the whole page during turns.

## 7. Accessibility

- Focus rings always visible (2 px accent, offset 2 px), tab order east-to-west.
- All interactive ≥ 48 px target, ≥ 4.5:1 text contrast against their surface,
  secondary copy `#676A61` only on light canvas (7:1 ok), muted `#87897F` never for
  functional labels.
- Reader canvas: extra `+reader-text-adjust` handles user zoom inside the webview;
  Windows OS display scaling fully supported.
- ARIA accurately for: rows, chips (radio group for filters), slider (progress
  scrubber), dialogs.

## 8. Custom icons — port note

`ui/icons/FolioIcons.kt` defines Folio's own set; recreate each glyph as 24-px SVGs
with 20-px variants for dense metadata. On desktop prefer **inline SVG components**
over a font, and ship both filled/outlined versions only where the design system says.
