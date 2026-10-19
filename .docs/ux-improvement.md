# Folio EPUB Reader — UX Audit & Redesign Plan

**Scope:** Source-code audit of the uploaded Android/Kotlin + Jetpack Compose implementation.  
**Design direction:** Premium, minimal, editorial, book-first.  
**Status:** Findings are based on source code, not a running build or screenshot review.

---

## 1. Executive diagnosis

Folio already has a useful feature foundation: a personal library, EPUB/HTML/TXT import, library and in-book search, reading progress, table of contents, bookmarks, highlights, notes, reading themes and typography controls, collections/tags, damaged-document recovery, and backup/restore.

The app feels less premium than its feature set because the interface does not yet have a strong visual system. It uses default Material 3 typography (`Typography()`), mixes familiar filled Material icons with text-based controls, presents the library as small elevated cards, places too many actions in the reader toolbar, and lays settings out as an ungrouped vertical form. The reader's typography controls expose raw values such as `lh`, `mx`, and `br`, which are implementation-like labels rather than reader-friendly language.

**The redesign should not add decoration for its own sake.** It should reduce competing UI, improve typography and hierarchy, make actions predictable, and use high-quality spacing, icons, states, and surfaces consistently.

### The five highest-value changes

1. **Build a real design foundation:** semantic colors, a deliberate UI type scale, spacing, shapes, icon rules, and reusable Compose components.
2. **Simplify reader controls:** replace the two horizontally scrolling control rows with a compact top bar and one well-designed “Reading appearance” sheet.
3. **Redesign the library as an editorial book list:** larger covers, quieter rows, a clear “Continue reading” focus, stronger metadata hierarchy, and useful empty states.
4. **Make the whole app feel like one product:** consistent navigation, search fields, sheets, dialogs, status messages, and iconography across Library, Reader, Notes, and Settings.
5. **Resolve theme language and ownership:** clearly distinguish the app's interface appearance from the default reading theme and per-book overrides.

---

## 2. What was inspected

The uploaded source was inspected around these areas:

- `MainActivity.kt`: `LibraryScreen`, library top bar, filter row, empty/loading/results states, `SearchPanel`, `LibraryCard`, book details, collection management, and import/recovery dialogs.
- `ReaderChrome.kt`: reader top bar, typography row, stepper controls, theme menu, and bottom reading-progress control.
- `ReaderActivity.kt`: reader overlay integration, search/contents/highlight sheets, reading splash, and visibility behavior.
- `SettingsActivity.kt`: default reading theme, storage information, version, backup and restore flows.
- `NotesActivity.kt`: notes/highlights/bookmarks list and edit/delete flows.
- `Color.kt`, `Type.kt`, and `Theme.kt`: current palette and application theme configuration.
- `LibraryUi.kt` and `SearchUi.kt`: display models and search-result formatting.

This is a code-first audit. I could verify component choices, labels, layout patterns, and interaction wiring from source. I could not verify rendered pixels, real device clipping, animation quality, dynamic type behavior, or runtime accessibility without an installed build or screenshots.

---

## 3. What is already good — preserve it

- **The core product is focused.** It is a reader and library, not a general-purpose content dashboard.
- **Reading preferences are meaningful.** Font size, family, line height, margins, justification, brightness, and reading flow are exposed, and per-book overrides exist.
- **Important reading actions are present.** Table of contents, in-book search, bookmarks, selection highlights, notes, and location jumps are integrated.
- **Reading progress has context.** The bottom control can show the current chapter, percentage, and estimated time left.
- **Library data is handled thoughtfully.** Search, sort, collections, tags, duplicate handling, and recovery of damaged/interrupted imports exist.
- **The base palette is restrained.** The current ivory/green and charcoal/sage direction is a suitable starting point for a bookish brand.
- **The reading engine should remain the source of truth.** Redesign the chrome around Readium rather than rebuilding EPUB navigation or altering the publication-rendering path for visual reasons alone.

The objective is to elevate presentation and interaction without destabilizing the reading/import/storage behavior that already exists.

---

## 4. Findings by priority

### P0 — Fix before calling the redesign complete

| ID | Finding and source evidence | UX impact | Recommended change |
|---|---|---|---|
| P0-01 | `ui/theme/Type.kt` defines `val Typography = Typography()`. The app therefore uses Material defaults rather than a deliberate Folio interface type scale. | The app has no clear typographic signature. Labels, screen titles, metadata, and actions can feel generic even when the layout is simple. | Define explicit interface styles and typography tokens. Keep interface typography separate from the book's reading typography. |
| P0-02 | `ReaderChrome.kt` puts back/theme/font-size/flow/TOC/search/bookmark/highlight controls in a horizontally scrolling top row, followed by another horizontally scrolling row for font, line height, margin, justification, brightness, and reset. | Controls overflow narrow screens; key features are hidden off-screen; short labels such as `lh`, `mx`, and `br` require interpretation; the toolbars compete with the text. | Use a compact, non-scrolling top bar. Put secondary controls in a single “Reading appearance” sheet with named sections, sliders/segmented choices, live preview, and reset. Move lower-frequency actions into a clear overflow menu. |
| P0-03 | `LibraryCard` is a clickable `Card` with 14dp corners, 1dp elevation, a 44×58dp cover, and a 6dp colored strip when no cover exists. The list repeats this presentation for every book. | Small book art and repeated raised cards produce a generic list/card look instead of a considered book collection. The placeholder stripe reads as a fallback, not a designed book identity. | Use flat editorial rows on a shared canvas, 56×78dp or 60×84dp covers where space allows, subtle dividers, and a designed cover placeholder (quiet generated cover field + initial/monogram or abstract geometry). Avoid adding a card shadow to every row. |
| P0-04 | `SettingsScreen` presents theme options as five plain buttons labelled `light`, `warm`, `dark`, `true-black`, `system`; its page is a single vertical column with loose section labels and action rows. `FolioMobileTheme` itself follows OS light/dark, while these saved preferences control the reader. | “Theme” can imply the whole app changes, although the stored preference is specifically the reading theme. The settings page reads like a developer configuration form rather than a finished product. | Rename this group **Default reading appearance**. Use descriptive labels and selected preview chips/cards. If app-wide theme control is added, create a separate **App appearance** setting rather than conflating it with book-page themes. Group backup and storage in distinct settings sections. |
| P0-05 | Iconography comes from a mix of `Icons.Filled`, AutoMirrored Material icons, text glyph controls (`Aa`, `J`), and abbreviated labels. | Different visual weights and metaphors make the app feel assembled from component defaults. | Establish one icon family: consistent 24dp geometry, 1.75–2dp stroke, rounded caps/joins, matching optical weight, and predictable active states. Replace one-off glyph controls with designed buttons and accessible labels. |

### P1 — High impact on day-to-day use

| ID | Finding and source evidence | UX impact | Recommended change |
|---|---|---|---|
| P1-01 | `LibraryScreen` shows `Folio` plus sort, search, add, and overflow icons. Library filters appear as a horizontally scrolling chip row; the list begins immediately beneath it. | The header does not establish a strong page hierarchy or tell the reader how large/organized their collection is. Primary and secondary actions compete. | Use a clear page title (**Library** or **Your library**), a subdued book count, search and import as the main actions, and sort/collection management as secondary actions. Keep filters concise and clearly selected. Consider Library / Notes / Settings as visible primary destinations. |
| P1-02 | The empty-library state is a plain text block: “Library is empty. Add a book or article with the + button.” | The first-run experience relies on discovering the plus icon and has no direct, reassuring next step. | Provide a designed empty state with a single quiet icon/illustration, the heading **Your reading life starts here**, one short explanatory sentence, and a strong **Import a book** button. Keep supported formats and a secondary explanation below it. |
| P1-03 | `LibraryCard` exposes “Open” in the overflow menu even though tapping the row already opens the book. It also exposes Details and Delete. | The menu repeats the main action and gives destructive action similar menu weight to routine actions. | Keep row tap for open/resume. In overflow, put Details and management actions first; style Delete as destructive and keep confirmation. Add a clear resume label only where it helps. |
| P1-04 | Search in `SearchPanel` uses a `BasicTextField` with a floating text placeholder and a close icon; there is no visible search field container or clear-query affordance. | Search feels unfinished compared with a polished OS-native search experience. | Use a 48–52dp search field surface with leading search icon, visible focus state, trailing clear button when non-empty, clear close/back behavior, and result-count/loading feedback. Preserve current cross-library search functionality. |
| P1-05 | Library search results are grouped with document and chapter text but rely mostly on default typography and spacing. Empty/no-results states are plain text. | Result scanning, query feedback, and match emphasis do not feel connected to the rest of the product. | Add a stronger document group header, quiet chapter labels, a maximum of 2–3 snippet lines, tasteful match highlight, and distinct initial/loading/results/no-results/error states. |
| P1-06 | `NotesScreen` shows annotations in plain stacked rows with no strong separation, while edit and delete icons sit at the right. The screen has only a generic empty message. | Notes are meaningful personal content but do not feel like a library of saved passages. Highlight, note, and bookmark types are hard to scan at a glance. | Present each item as a clean quote row/card with a subtle color rail or dot, book title, quote, optional note, and compact type metadata. Add filters for All / Highlights / Notes / Bookmarks and a polished empty state. Preserve tap-to-return-to-text. |
| P1-07 | `DetailsDialog` can contain tags, horizontally scrolling chips, collection checkboxes, and new collection fields inside an `AlertDialog`. | A content-heavy editing task risks feeling cramped, especially on smaller screens and with the keyboard open. | Move book details to a tall modal bottom sheet or dedicated screen, with clear Tags and Collections sections, a sticky Save action where possible, and explicit unsaved-change behavior. |
| P1-08 | `TocSheet`, `SearchSheet`, and `SelectionToolbarSheet` each use bottom sheets but have different levels of structure and spacing. TOC items are `TextButton` rows. | Sheets are natural for reader tools, but a few different default components can make them feel inconsistent. | Standardize sheet header, close affordance, top spacing, dividers, row heights, empty states, and action placement. TOC items should be full-width touch rows with meaningful indentation and an optional current-location marker. |
| P1-09 | Recovery banners for damaged documents and interrupted imports can appear ahead of the filter/list content. | Important recovery information is correctly surfaced, but multiple banners can dominate the library and break the calm layout. | Create a unified, accessible **Needs attention** area with count and short summary. Keep the detailed review and recovery actions; allow the summary to collapse when appropriate. Do not silently hide or delete recovery information. |
| P1-10 | The bottom reading-progress “pillar” is a centered surface with chapter title, percentage and slider. It uses a 22dp slider height and a small label. | Progress is a useful persistent affordance, but the interaction may not feel like a purpose-designed reading navigator. | Design a compact bottom reading dock: progress line/slider, chapter label, percentage, optional time left, clear drag feedback, and system-bar-safe padding. Ensure slider touch target and accessible value are adequate. |

### P2 — Polish and quality improvements

| ID | Finding | Recommended change |
|---|---|---|
| P2-01 | The same rounded-card treatment is used for many kinds of content by default. | Use elevation sparingly. Separate list rows with hairlines; reserve filled surfaces for sheets, search, selected controls, and meaningful grouped content. |
| P2-02 | Labels such as `font: serif`, `lh 1.6`, `mx 20`, `br 100`, and raw theme keys expose internal preference values. | Use familiar names: Font, Line spacing, Page margins, Screen brightness, Text alignment. Show values secondarily only where helpful. Use an explanation tooltip or preview rather than unexplained abbreviations. |
| P2-03 | `NotesScreen` and settings use plain text empty states and ungrouped text. | Apply a common empty-state pattern and reusable settings-row pattern throughout the app. |
| P2-04 | The default library cover fallback is a narrow colored strip using a small set of strong tint colors. | Build a deliberate cover placeholder with a restrained palette and consistent geometry; make it feel intentional without inventing metadata or a fake cover. |
| P2-05 | The theme picker currently uses small color dots and raw theme names. | Use labelled swatches with contrast-aware text and a visible selected indicator. Name themes by experience, e.g. Paper, Light, Dark, True black, System. |
| P2-06 | The application `MaterialTheme` only defines a small subset of the color scheme; Material defaults fill out the rest. | Define all semantic colors the app uses: surface/container tiers, outline, error, on-primary, disabled, selected and scrim roles. Verify both light and dark schemes deliberately. |
| P2-07 | No UI screenshot or running-app evidence was supplied with the source. | After code redesign, test actual devices and create before/after screenshots for Library, Reader (controls visible/hidden), Reading appearance sheet, Notes, and Settings. |

---

## 5. Recommended experience by screen

### A. Library — the home of the product

**Recommended composition**

1. Compact top app bar with Folio mark/wordmark and one clear page heading.
2. Search field or search action and a prominent, unambiguous import action.
3. A **Continue reading** section when any unfinished book exists. Show a meaningful cover, title, author, percentage and **Continue** action. Do not show it when there is no in-progress book.
4. A **Your library** section with book count, concise filter access and a sort control.
5. Quiet rows on one shared background, with cover, title, author/type, optional progress and a contextual menu.

**List-row anatomy**

- Cover: 56×78dp minimum for a comfortable phone list; 60×84dp where the screen width allows. Use a consistent 3–5dp corner radius.
- Text: title is the strongest line; author/type is lower contrast; progress/context is tertiary.
- Progress: 3dp or 4dp thin line, with percent or “Continue reading” context. Do not show a meaningless empty progress bar for never-opened books.
- Row spacing: 16dp horizontal content inset, 12–16dp vertical padding; use a fine divider rather than a shadow between every row.
- Taps: tapping a row resumes/opens the book. Keep secondary actions in a 48dp touch target, not a tiny custom hotspot.

**Empty, loading, and failure states**

- Empty: clear import CTA and supported-format information.
- Loading: show low-contrast skeleton rows matching final geometry instead of a lone spinner for the whole screen if loading lasts long enough to be noticeable.
- Import in progress: identify the current stage/title and show progress when available. Keep the user informed if the file is being inspected or indexed.
- Import error: use a human-readable message and a recoverable action; avoid raw exceptions as the primary message.
- No filter matches: offer **Clear filter**.
- Damaged/interrupted files: preserve the recovery flow but visually separate it from ordinary browsing.

### B. Reader — make the page dominant

**Always-visible reading content should win.** Reader UI should appear only when requested or when the user is using it. Use the document's own typography, not the app's interface font, for book text.

**Compact reader chrome**

- Top row: Back, current book/chapter title (truncated gracefully), Reading appearance (`Aa` icon plus optional label in the sheet), Search, and Overflow.
- Overflow: Table of contents, Bookmarks, Highlight/selection tools, and other less frequent actions. Keep primary actions reachable without horizontal scrolling.
- Bottom dock: one quiet progress track plus chapter/percentage; time left is optional and secondary.
- Hide/show: retain the familiar tap-to-toggle behavior if it works well with page-turn zones, but introduce it during first-run reading (e.g. “Tap the centre to show reading controls”). Never let chrome cover important page text longer than necessary.

**Reading appearance sheet**

Group controls in the order a reader thinks about them:

1. **Theme:** Paper / Light / Dark / True black / System. Use labelled previews.
2. **Text size:** slider with minus/plus buttons and a live sample.
3. **Typeface:** Serif / Sans serif / Publisher. Show the label as a normal phrase, not a raw enum.
4. **Spacing:** Line spacing and paragraph spacing; sliders or small segmented steps.
5. **Page layout:** Margins, alignment, paginated/scrolled flow.
6. **Brightness:** place only if it remains useful and correctly represents what it changes; call it “Reader brightness” and show a clear scale.
7. **Reset:** “Reset this book to defaults” when a per-book override exists. Include a calm indication that the settings apply only to this book.

Use a sheet that is tall enough for one-handed use but not overloaded with every control on the same horizontal line. If the set of settings becomes too large, separate Reading appearance from Reading layout.

**Selection/highlight flow**

- Keep the selected quote visible at the top of the sheet.
- Use four or five deliberate color swatches with accessible names and a visible selected state.
- Present Add note and Copy as clear labelled actions; preserve the ability to dismiss and return to the selection.
- Avoid colored buttons with low-contrast icons. Color should encode highlight type without being the only identifier.

**Contents and in-book search**

- Contents: full-width rows, clear hierarchy for nested headings, 48dp minimum interactive height, and current chapter indication where possible.
- Search: proper search field, explicit Search/keyboard submit, progress while searching, result count, readable snippet and chapter title. Keep a differentiated error state from zero results.
- Search result: tapping a result should jump and dismiss the sheet predictably, with the relevant passage visible.

### C. Notes, highlights, and bookmarks

Saved passages are personal reference material. Give them the same typographic care as the reader.

- Add a top title with count and filter/search controls.
- Each saved item: book title, quote, optional note, annotation type/date as quiet metadata.
- Highlight color: small side marker or chip; do not paint the whole screen with accent color.
- Whole quote row: tap to return to location. Edit and delete are separate, accessible 48dp actions or live in a menu on smaller screens.
- Deletion: confirmation should state what will be removed, then provide a clear success message or undo when technically feasible.
- Empty state: explain that selecting text while reading creates highlights, notes, or bookmarks; link/CTA to the library rather than showing a dead end.

### D. Settings

Structure settings into groups with one consistent reusable row pattern.

- **Reading**: Default reading theme, default text size, reset reading defaults (if supported).
- **Appearance** (optional app-level feature): System / Light / Dark. Only add this if it is an actual separate preference.
- **Library & storage**: library size, item count, and any meaningful storage breakdown available from the app's own data.
- **Backup**: Export backup and Restore backup as two separate rows or full-width actions, each with a concise explanation.
- **About**: Version and relevant app information.

For Restore, make the difference between Merge and Replace explicit. Treat Replace as destructive and visually secondary/destructive, not as a visually equal casual option. Explain that Replace affects local data before the user commits.

### E. Shared components and overlays

Create reusable, consistent versions of:

- Search field with clear action and focused state.
- Settings section header and settings row.
- Book list row and placeholder cover.
- Annotation/quote row.
- Filter chip and selected-state indicator.
- Modal bottom sheet header and close affordance.
- Primary, secondary and destructive buttons.
- Snackbar/status message, loading skeleton, empty state and inline error.
- Recovery banner and destructive confirmation dialog.

Use the same horizontal padding, corner logic, typography, divider opacity, touch targets, and button height throughout. A minimal design still requires a deliberate component system.

---

## 6. Visual direction: “Quiet editorial”

The target is not a decorative, glassy dashboard. It is an understated book product with the finish of a well-typeset reading instrument.

**Do**
- Use warm neutral canvas colors and deep ink text.
- Reserve the deep green accent for primary actions, progress and selected states.
- Let cover art bring variety; keep the rest of the library calm.
- Prefer hairline separators and carefully grouped surfaces to stacks of elevated cards.
- Use typography and whitespace for hierarchy.
- Keep icons visually consistent and quiet.
- Use motion only to clarify transitions and state changes.

**Do not**
- Add gradients, neon accents, glowing borders, excessive shadows or random illustrations to make the app seem “premium”.
- Put every control inside a rounded card.
- use multiple unrelated icon packs or mix filled and thin-outline icons without a rule.
- make secondary labels tiny, gray-on-gray, or abbreviated.
- hide common reader controls in a wide row that can only be reached by horizontal scrolling.
- redesign the book text area as a dashboard. The book page is the central experience.

The companion file `Folio_Mobile_Design_System.md` defines exact tokens and implementation guidance.

---

## 7. Accessibility and interaction requirements

- **Touch:** all icon-only actions should have a 48×48dp minimum touch area, including clear, close, overflow, and note actions.
- **Contrast:** normal text should target at least 4.5:1 against its actual surface; large text and meaningful icon/state indicators should meet applicable WCAG contrast requirements. Do not use muted/disabled colors for content users need to read.
- **Dynamic type:** test system font scaling at 1.0×, 1.3×, and 1.5×; important actions must not clip. The reader's own text-size preference should remain separate from app UI text scaling.
- **Screen readers:** every icon-only button needs an accurate content description; selected theme/color should expose selection; progress should expose current value and a useful label.
- **Keyboard/focus:** support IME search submission, visible focus, back behavior and sensible traversal order.
- **Motion:** use short, restrained transitions (around 160–220ms); respect system reduced-motion expectations when supported. Never make progress or selection state dependent on animation finishing.
- **Feedback:** provide clear loading, success, empty, error, and destructive states. Avoid raw exception strings as the first line of UI copy.
- **Reader safety:** preserve page-turn gestures, selection behavior, location restoration, and per-book preference overrides during the visual redesign.

---

## 8. Implementation sequence

### Phase 0 — Protect behavior and baseline

1. Create before screenshots from a real device/emulator: empty Library, populated Library, Reading view (chrome visible/hidden), Reading appearance controls, Contents, in-book Search, selection/highlight sheet, Notes, Settings, import failure, damaged/interrupted document state.
2. Record core flows: import a book; reopen and resume; adjust a per-book preference; search and jump; create highlight + note; export and restore a backup.
3. Do not change schema, importer behavior, Readium navigation, or location serialization as part of a UI-only pass.

### Phase 1 — Design foundations

1. Define semantic color palettes for light/dark app UI and separate reading-canvas themes.
2. Implement explicit typography styles, spacing, shapes, icon sizes and component sizes.
3. Create reusable buttons, fields, rows, chips, section headers, sheets, dialogs, status messages, and empty states.
4. Remove local one-off colors and ad hoc alpha values where a semantic token should be used.

### Phase 2 — Library and navigation

1. Redesign the empty state and header.
2. Redesign book rows and covers.
3. Improve search field, result rows and filter-clearing.
4. Simplify sort and overflow actions.
5. Consider primary navigation for Library / Notes / Settings while preserving current Activity boundaries if a full navigation refactor is not necessary.
6. Rebuild and check 320dp-wide and large-screen layouts. No critical action should require horizontal scrolling.

### Phase 3 — Reader chrome and appearance controls

1. Replace the two horizontally scrolling rows with compact chrome.
2. Implement the Reading appearance sheet with labeled controls and preview.
3. Standardize progress dock, Contents, Search, and selection/highlight sheets.
4. Test all five reading themes, system dark mode, cutouts/system bars, both paginated/scrolled flow, font-size range, and per-book reset.
5. Confirm book text remains readable and page-turn zones still work with chrome visible/hidden.

### Phase 4 — Notes and Settings

1. Redesign annotation rows, filters, count, and empty state.
2. Group settings into named sections and make theme semantics clear.
3. Make Restore Merge/Replace consequences explicit.
4. Standardize confirmation dialogs and success/error feedback.

### Phase 5 — polish and QA

1. Replace mixed icons with the chosen icon system.
2. Apply consistent pressed, focused, disabled, selected, loading, error, and destructive states.
3. Run accessibility and dynamic-type checks.
4. Compare real screenshots before/after, not just Compose previews.
5. Run existing unit tests and add UI tests for high-risk flows.

---

## 9. Acceptance checklist

The redesign is ready for review when:

- [ ] No major toolbar or settings row requires horizontal scrolling to reach a normal action.
- [ ] Library, Reader, Notes, and Settings share the same typography, spacing, icon weight, button language, and surface rules.
- [ ] The Library has a meaningful empty state and a clearly discoverable import action.
- [ ] Cover-less books still have a polished, intentional thumbnail.
- [ ] Book rows are scannable at 320dp screen width and at 1.3× font scale.
- [ ] Reader controls can be found without guessing raw labels or values.
- [ ] Secondary reader settings remain discoverable but do not take over the page.
- [ ] Theme settings clearly state whether they change the app UI or the book page.
- [ ] Notes/highlights/bookmarks are visually distinguishable and can reliably return to the saved location.
- [ ] Empty, loading, no-results, import-error, recovery, and destructive states are designed.
- [ ] Icon buttons have screen-reader labels and appropriate touch targets.
- [ ] The UI redesign has not broken import, search, resume, annotation, backup, or restore behavior.
- [ ] Actual emulator/device screenshots confirm the result looks premium on both light and dark app UI and every reading theme.

---

## 10. Bottom line

Folio does not need more surface decoration. It needs a consistent visual language, a more confident type hierarchy, a book list that makes covers and reading progress feel intentional, and reader controls that are compact until needed. Build the design system first, then implement screen-by-screen so the result looks like one designed product rather than a collection of Compose defaults.
