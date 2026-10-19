# PWA vs Android — Feature Gap Report

**Repo:** folio (`D:\folio`)
**Apps compared:**
- **PWA (web)** — `apps/web/`, React + Vite + epub.js, browser-based reader (EPUB + PDF)
- **Android (native)** — `mobile/`, Kotlin 2.0 + Jetpack Compose + Readium 3.0.0, EPUB-first reader
**Report date:** 2026-10-19
**Scope:** Features present in the PWA that are missing in the Android app, with emphasis on UX and reading-experience enhancements.

---

## 1. Executive Summary

The Android app is a solid, well-tested v1 reader (188 passing JVM tests, schema-1 byte-compatible with the PWA). It covers the core reading loop — open EPUB, paginate/scroll, themes, font size, search, TOC, highlights/notes, bookmarks, progress persistence, backup/restore — but its **reader chrome is a thin layer** compared to the PWA.

The PWA is the feature-complete reference. It adds PDF reading, a far richer typography/geometry toolkit, reading presets, live preview, chapter ticks on the scrubber, jump history, fullscreen mode, haptics, Google Drive sync, backup reminders, batch library actions, annotation Markdown export, and more.

**Key finding:** several Android gaps are **zero-cost UI wins**. `ReaderPrefsMapper.kt` / `ReaderPrefsAndroid.kt` already map paragraph spacing, letter spacing, hyphenation, and spread to Readium, but `ReaderChrome.kt`'s `TypographyRow` exposes none of them. The Lora font is referenced but missing. These can be closed without new backend work.

---

## 2. Feature Inventory — Present in Both (Parity)

These exist in both apps and are therefore **not** gaps:

| Category | PWA | Android |
|---|---|---|
| Themes | Light, Warm, Dark, True-black, System | Light, Warm, Dark, True-black, System |
| Font size | 14–32 px | mapped (relative factor) |
| Line height | 1.3–2.4 | mapped |
| Page margins | 12–64 px | mapped |
| Flow modes | Paginated / Scrolled | Paginated / Scrolled |
| Text alignment | Left / Justify | Start / Justify |
| Hyphenation | On/Off toggle | mapped (no UI) |
| Brightness dimmer | 40–100% slider | 30–100% overlay scrim |
| In-book full-text search | Live streaming results | Live streaming results (Readium SearchService) |
| Table of contents | Flat list (3 tabs: contents/bookmarks/highlights) | Flat list |
| Bookmarks | Add/remove, persisted | Add/remove, persisted |
| Highlights | 4 colors + note | 4 colors + note |
| Selection capture → highlight | Floating toolbar (4 colors, note, copy) | Selection sheet (4 colors, note, copy) |
| Quote sharing | Copy to clipboard | Share as PNG quote-card image |
| Middle-tap to toggle chrome | Yes | Yes (middle third) |
| Keep-screen-awake | Wake Lock API | `FLAG_KEEP_SCREEN_ON` |
| Per-book typography override | Yes (Batch 2) | Yes (Batch 2) with reset-to-global |
| Progress persistence | Debounced + flush on visibility/unload | Debounced + flush on destroy |
| Import formats | EPUB, PDF, HTML, TXT | EPUB, HTML, TXT (PDF dead code) |
| Backup export / restore ZIP | Merge or replace | Merge or replace |
| Library collections & tags | Yes | Yes |
| Library sort | Recent / Title / Author / Progress | Recent / Title / Author / Progress |
| Library filter | Kind / Collection / Tag chips | Collection / Tag chips |
| Damaged-doc & interrupted-import recovery | Yes | Yes |
| Notes activity (list/edit/delete/jump) | Yes | Yes |

---

## 3. Features Missing in Android That Enhance UX & Reading

Grouped by impact on the reading experience.

### 3.1 Typography & Reading Comfort (highest impact)

These directly affect how comfortable and legible long-form reading feels.

| # | Feature | PWA | Android | UX impact |
|---|---|---|---|---|
| T1 | **Lora font** | 5 fonts (Literata, Lora, System Sans, Monospace, Publisher) | 4 (serif, sans, mono, publisher) — Lora absent | Lora is a popular, highly readable serif. Audit notes it as a dangling "ghost menu entry" (`ReaderPrefsAndroid.kt`, `TypographySheet.tsx`). |
| T2 | **Paragraph spacing** | Slider, 0–1.5 em | Mapped in `ReaderPrefsMapper` but **no UI control** | Controls visual rhythm between paragraphs; key for long-form comfort. |
| T3 | **Letter spacing** | Slider, −0.02 to 0.08 em | Mapped in mapper but **no UI control** | Helps readers with dyslexia / visual crowding; improves legibility at small sizes. |
| T4 | **Max reading column width** | Slider, 560–960 px (desktop) | Not exposed | Keeps line length in the optimal ~45–75 char range for readability. |
| T5 | **Two-page spread** | Toggle None / Auto (paginated, desktop/tablet) | Mapped but **no UI control** | Book-like reading on tablets. |
| T6 | **Hyphenation toggle** | On/Off button | Mapped but **no UI control** | Reduces ragged edges in justified text. |
| T7 | **Reading presets** | One-click: Comfortable / Compact / Long Scroll / Academic Focus | None | Lets users instantly apply a coherent font+size+spacing set. |
| T8 | **Live typography preview** | Preview strip updates in real time as sliders move | No preview | Users see the effect before committing. |
| T9 | **Non-modal typography sheet** | Desktop: side panel, book stays visible; Mobile: bottom sheet without veil | TypographyRow crammed into chrome with horizontal scroll | Context-preserving adjustment — reader doesn't lose place. |

### 3.2 Reader Navigation & Orientation

| # | Feature | PWA | Android | UX impact |
|---|---|---|---|---|
| N1 | **Chapter ticks on scrubber** | Chapter positions marked along progress track | Simple scrubber, no chapter markers | Visual sense of where chapters fall in the book. |
| N2 | **Closest-chapter label while scrubbing** | Shows nearest chapter title during drag | Percent only | Helps users land near a chapter boundary. |
| N3 | **Next-chapter hint (scrolled flow)** | Shows upcoming chapter for flow continuity | None | Orientation in continuous-scroll mode. |
| N4 | **Jump history & return pill** | Jump to a location, then return to where you were | None | Essential when following cross-references or search hits. |
| N5 | **TOC tabs (contents / bookmarks / highlights)** | Three-tab TOC sheet | Flat TOC list only | Lets readers browse just their highlights or bookmarks. |
| N6 | **Document details from reader header** | Click book title → author, format, chapter count | None accessible from reader | Context about the current book without leaving it. |
| N7 | **Fullscreen reading mode** | Browser Fullscreen API hides browser chrome | None | Maximum immersion, zero distractions. |
| N8 | **Desktop page-turn arrows / wheel nav** | Side arrows + mouse wheel (paginated) | N/A (mobile-only app) | PWA-only; not applicable to Android form factor. |

### 3.3 Reading Session UX

| # | Feature | PWA | Android | UX impact |
|---|---|---|---|---|
| S1 | **Haptics feedback** | Light taps on page turns, highlight saves, bookmarks | None observed | Tactile confirmation of actions; polished feel. |
| S2 | **Wake Lock** | JavaScript Wake Lock API | `FLAG_KEEP_SCREEN_ON` | Functionally equivalent; different mechanism, not a true gap. |

### 3.4 Format Support

| # | Feature | PWA | Android | UX impact |
|---|---|---|---|---|
| F1 | **PDF reading** | `PdfEngine` reads PDFs natively | `PdfImporter` exists but is **dead code** — `ImportDispatch` throws `UnsupportedFormatException` | Biggest format gap; PDF files hit a guaranteed "Unsupported format" dead-end (audit EPUB-001). |

### 3.5 App-Level UX (library, notes, backup)

| # | Feature | PWA | Android | UX impact |
|---|---|---|---|---|
| A1 | **Google Drive sync** | Full Drive mirror + sync across devices | Local backup ZIP only | Cross-device library continuity (notes, progress, books). |
| A2 | **Backup reminder banner** | Nudges after 14 days / 20 changes | None | Prevents data loss; passive safety nudge. |
| A3 | **Continue reading row** | Resume card on library home | None | Faster return to the current book. |
| A4 | **Batch selection in library** | Multi-select → mark finished / delete | Delete one at a time | Faster library management. |
| A5 | **Custom per-book cover hues** | User-set color per book card | Hash-generated seed color only | Visual library organization. |
| A6 | **Annotations export to Markdown** | Export notes/highlights to `.md` | None | Portability of reading work outside the app. |
| A7 | **Notes filters (doc / color / kind)** | Three dropdown filters | Plain unfiltered list | Finding specific notes in a large library. |
| A8 | **Share target route** | PWA share-target (receive files from other apps) | `ACTION_VIEW` import | Roughly equivalent on Android; not a true gap. |

---

## 4. What Android Has That the PWA Doesn't (for balance)

Only for completeness — these are Android-native advantages:

| Feature | Android | PWA |
|---|---|---|
| Hardware page-turn keys | Volume buttons, Bluetooth media buttons, DPAD with animated turns | Keyboard shortcuts only (no hardware keys) |
| Quote-card image sharing | `QuoteCard.kt` shares highlights as a PNG image card with book title + author attribution | Copies plain text to clipboard only |
| Edge-to-edge system bars | Proper status/navigation bar inset handling + theme-colored system bars | Browser chrome always present (unless fullscreen) |

---

## 5. Root Cause of the Gaps

Most Android gaps are **UI-layer only**. The backend plumbing already exists:

- `ReaderPrefsMapper.kt` maps `letterSpacing`, `paragraphSpacing`, `hyphens`, `spread`, `textAlign` 1:1 to Readium.
- `ReaderPrefsAndroid.kt` passes them to `EpubPreferences`.
- `ReaderChrome.kt`'s `TypographyRow` simply does not render controls for them.

So T2, T3, T5, T6 can be closed by adding UI rows to `TypographyRow` — no new reader-engine work. T1 (Lora font) needs the TTF bundled + `@font-face` injection (audit EPUB-002). T4 (column width) and T7–T9 (presets, preview, non-modal sheet) need new UI.

The one **engine-level** gap is F1 (PDF), which requires wiring `PdfImporter` through `ImportDispatch` and adding a PDF navigator path.

---

## 6. Prioritized Recommendations (close UX/reading gap first)

**P1 — Zero-cost / backend already wired (add UI to ReaderChrome.kt TypographyRow):**
1. Paragraph spacing slider (T2)
2. Letter spacing slider (T3)
3. Hyphenation toggle (T6)
4. Two-page spread toggle (T5)
5. Lora font option (T1) — bundle TTF

**P2 — New reader chrome UI:**
6. Chapter ticks + closest-chapter label on scrubber (N1, N2)
7. Reading presets + live preview (T7, T8)
8. Non-modal typography sheet (T9)
9. Jump history / return pill (N4)
10. TOC tabs: contents / bookmarks / highlights (N5)

**P3 — App-level UX:**
11. Backup reminder banner (A2)
12. Continue reading row (A3)
13. Batch library actions (A4)
14. Notes filters (A7)
15. Annotation Markdown export (A6)
16. Per-book cover hues (A5)

**P4 — Engine-level:**
17. PDF reading support (F1) — largest single gap

**P5 — Cross-device:**
18. Google Drive sync (A1)

**Nice-to-have:**
19. Haptics (S1)
20. Fullscreen mode (N7)

---

## 7. Conclusion

The Android app reaches parity with the PWA on the core read / annotate / persist loop, but lags on everything that makes reading *comfortable* and *orientable*: granular typography controls (paragraph/letter spacing, hyphenation, spread), font choice (Lora), reading presets, chapter-aware scrubbing, jump history, and TOC filtering. Fortunately, most of these are UI additions atop already-working backend mapping. The single largest functional gap is PDF support, currently a dead-end on import.

The fastest path to a premium Android reading experience is to expose the typography knobs the mapper already computes, then layer on chapter ticks, presets, and jump history — all of which exist as proven patterns in `apps/web`.

---

*Generated from static inspection of `apps/web/` (PWA) and `mobile/` (Android) source, cross-referenced with `EPUB_READER_AUDIT_REPORT.md`. No runtime device testing performed.*