# Folio Mobile — Premium UI Design System

**Version:** 1.0 proposal  
**Platform:** Android / Jetpack Compose / Material 3 foundation  
**Product character:** Quiet editorial, book-first, minimal, premium  
**Brand approach:** Warm neutrals + restrained evergreen accent; reading canvas is independent from app chrome.

---

## 1. Product principles

### 1.1 Books are the content, UI is the frame

The app interface should feel like a well-made book object: quiet, intentional, legible, and focused. The book cover and passage are the visual content. Avoid attention-grabbing backgrounds and decorative UI that competes with them.

### 1.2 Premium through precision, not decoration

Premium appearance comes from consistent spacing, well-set type, good line lengths, clear interaction hierarchy, clean icon drawing, quiet surfaces and polished states. Do not rely on blur, gradients, large shadows, ornamental borders or a different rounded card around every component.

### 1.3 One interface system, multiple reading canvases

Library, Notes, Settings and reader chrome use the **Folio app palette**. The actual EPUB page uses a separate **reading palette** selected by the user/book. Keep these systems separate in code and language.

### 1.4 Progressive disclosure

Expose common actions directly and move less frequent settings into a sheet or overflow. Do not force readers to horizontally scroll through controls to reach an action.

---

## 2. Color system

Use semantic roles rather than assigning colors by visual guess. These are proposed initial tokens; verify them in the actual rendered interface and on OLED devices before locking them.

### 2.1 App UI — light

| Token | Hex | Use |
|---|---|---|
| `canvas` | `#F7F6F1` | App page background; warm, almost-white canvas |
| `surface` | `#FFFEFB` | Main surfaces, sheets, input backgrounds when elevated from canvas |
| `surfaceSubtle` | `#F1F0E9` | Quiet selected regions, secondary panels, placeholder fields |
| `surfaceRaised` | `#FFFFFF` | Rare elevated surfaces/dialogs; do not use a shadow for every row |
| `textPrimary` | `#22251F` | Main titles, primary content |
| `textSecondary` | `#676A61` | Author, helper copy, secondary values |
| `textMuted` | `#87897F` | Non-essential metadata only; not body copy or required controls |
| `border` | `#E1E0D6` | Fine dividers, field outlines, quiet separators |
| `accent` | `#355847` | Primary CTA, progress, links and selected states |
| `accentPressed` | `#294638` | Pressed/stronger action state |
| `accentContainer` | `#E2EAE1` | Selected chips, soft selected backgrounds |
| `onAccent` | `#FFFDF7` | Text/icon on evergreen accent |
| `success` | `#356B4D` | Successful operation status |
| `warning` | `#94692E` | Caution/recovery notice |
| `danger` | `#A44C43` | Destructive action and error state |

### 2.2 App UI — dark

| Token | Hex | Use |
|---|---|---|
| `canvas` | `#141713` | Base app background; avoid pure black for ordinary dark UI |
| `surface` | `#1C211C` | Main surfaces and sheets |
| `surfaceSubtle` | `#242B24` | Input fields, secondary panels, selected list rows |
| `surfaceRaised` | `#293029` | Dialogs and rare raised surfaces |
| `textPrimary` | `#ECEFE7` | Primary content |
| `textSecondary` | `#B2B7AB` | Supporting text |
| `textMuted` | `#8D968A` | Non-essential metadata only |
| `border` | `#343D35` | Dividers and outlines |
| `accent` | `#A7C4A6` | Selected states, progress, primary action highlights |
| `accentPressed` | `#BED4BB` | Pressed/high-emphasis state |
| `accentContainer` | `#2B3C2F` | Selected chips and quiet active surfaces |
| `onAccent` | `#162017` | Text/icon over the sage accent |
| `success` | `#8CC39B` | Successful operation status |
| `warning` | `#D8B77E` | Caution/recovery notice |
| `danger` | `#E0A09A` | Destructive action and error state |

### 2.3 Reading themes — separate palette

These tokens are for the reader's page/chrome palette. The EPUB's own CSS and publisher styling may require a separate renderer mapping; avoid assuming a Compose surface automatically recolors publication HTML.

| Reading theme | Page/background | Page/surface | Main ink | Muted ink | Accent |
|---|---|---|---|---|---|
| Paper | `#F1E6CF` | `#FBF3E4` | `#3D3328` | `#756653` | `#8A5B35` |
| Light | `#F7F6F1` | `#FFFEFB` | `#22251F` | `#676A61` | `#355847` |
| Dark | `#141713` | `#1C211C` | `#ECEFE7` | `#B2B7AB` | `#A7C4A6` |
| True black | `#000000` | `#090A09` | `#E0E0D8` | `#AAA99F` | `#A7C4A6` |
| System | Resolve from OS | Resolve from OS | Resolve from OS | Resolve from OS | Resolve from OS |

**Important:** `Default reading appearance` is not the same as `App appearance`. The former changes the book page/read controls and may be overridden per book. The latter, if implemented, changes Library/Notes/Settings chrome. Keep the labels and persisted values separate.

### 2.4 Color usage rules

- Use `canvas` for the base page. Use `surface` for grouped content or sheets, not for every row.
- Use `accent` for a clear action/state, not for every icon or label.
- Use color and shape/icon/text together for selected states; never rely on color alone.
- Keep destructive red limited to destructive actions and error states.
- Avoid pure black except for the reader's explicit True black theme.
- Define semantic tokens in one source and use them instead of local hex/alpha literals.
- Test contrast against the **actual** background used by each component; translucent layers change the result.

---

## 3. Typography

### 3.1 Font families

**Interface font:** Inter, bundled in the app if approved for the build and licensing workflow. Until the font asset is actually added and packaged, use Android's system sans-serif fallback rather than referencing a missing family.

**Reading font:** Literata for a high-quality book serif option where supported by the current Readium configuration. Also offer a legible sans-serif option and preserve the publisher's embedded font mode. For Devanagari/Hindi, use a tested Noto Devanagari fallback stack. Verify mixed Latin/Devanagari punctuation, line breaks and glyph fallbacks on a device.

**Monospace:** system monospace or a bundled programming-oriented face only where monospace improves comprehension; it should not become a decorative UI font.

The interface font and book font are distinct systems. Changing the book's text size must not change Settings labels or the library layout.

### 3.2 UI type scale

Use explicit `TextStyle` definitions. Sizes below are starting tokens in sp; line heights are in sp.

| Token | Size / line height | Weight | Purpose |
|---|---:|---|---|
| `displayTitle` | 28 / 34 | Semibold (600) | Rare feature/empty-state headline, not every page |
| `screenTitle` | 24 / 30 | Semibold (600) | Page title when space permits |
| `sectionTitle` | 17 / 24 | Semibold (600) | “Continue reading”, “Your library”, settings section |
| `bookTitle` | 16 / 22 | Medium (500) | List row title |
| `titleSmall` | 15 / 21 | Semibold (600) | Dialog/sheet heading or compact content title |
| `body` | 15 / 22 | Regular (400) | Standard UI copy |
| `bodySmall` | 13 / 19 | Regular (400) | Author and secondary supporting copy |
| `label` | 13 / 18 | Medium (500) | Buttons, field labels, chip labels |
| `metadata` | 12 / 16 | Medium (500) | Percent, kind, date, optional secondary metadata |
| `micro` | 11 / 14 | Medium (500) | Rare non-critical metadata only; avoid for core actions |

### 3.3 Type rules

- Use sentence case, not all-caps labels.
- Do not fake hierarchy by bolding every title. Use size, weight and space intentionally.
- Avoid using letter spacing on body copy. A small positive tracking may be used for tiny numeric metadata if it improves legibility.
- Never use 11sp or faded gray for a required instruction, core control, or error explanation.
- Use tabular numerals for progress percentages and numerical settings where alignment matters.
- Limit a reading-interface title to one or two lines; truncate metadata rather than squeezing body type.
- Allow dynamic type to grow without clipping buttons or causing horizontally scrollable toolbars.

### 3.4 Book-reading typography defaults

The existing `ReadingPrefs` starts at font size 18, line height 1.6 and margins 20; keep these as compatibility-aware defaults unless on-device reading tests suggest otherwise. Design presentation around them:

- Comfortable initial body size: 18sp-equivalent in the renderer.
- Typical line-height range: 1.5–1.75; preserve wider user-selected range when the preference supports it.
- Provide enough side margin for comfortable reading, but keep narrow phone displays usable.
- Do not force full justification by default; allow user control.
- Use real preview text in the appearance sheet instead of abstract size numbers alone.
- Preserve publisher font and layout when the user has chosen Publisher mode; don't silently overwrite publication styling beyond what the preference mapping is designed to do.

---

## 4. Spacing and layout

Use a 4dp base scale. Prefer the tokens below rather than arbitrary values.

| Token | Value | Common use |
|---|---:|---|
| `space1` | 4dp | Icon/text micro gap, fine alignment |
| `space2` | 8dp | Label-to-value gap, compact chip spacing |
| `space3` | 12dp | List-row vertical padding, compact group gap |
| `space4` | 16dp | Standard content spacing, card/row internal spacing |
| `space5` | 20dp | Phone screen horizontal inset |
| `space6` | 24dp | Section separation and sheet padding |
| `space8` | 32dp | Large section break |
| `space10` | 40dp | Empty-state composition |
| `space12` | 48dp | Larger visual separation, hero spacing |

### Screen rules

- Phone horizontal content inset: 20dp as the main default; 16dp is acceptable in dense lists if cover/text geometry still breathes.
- Tablet/wide layout: 24dp or more, with a sensible max content width (around 720dp for text/list content).
- Normal list row: 72–104dp depending on cover and metadata; do not hard-code all rows to one height if longer titles need two lines.
- Input field: 48–52dp height.
- Button: typically 48dp min height.
- Icon button: 48×48dp hit area even if the icon artwork is 20–24dp.
- Section gap: 24–32dp; related label/value gap: 4–8dp.
- Don't put horizontal scroll on a primary action row as the main overflow strategy. Collapse secondary controls instead.

---

## 5. Shapes, borders, and elevation

| Token | Radius | Use |
|---|---:|---|
| `radiusCover` | 4dp | Book thumbnails/covers |
| `radiusControl` | 8dp | Chips, small fields and compact controls |
| `radiusField` | 12dp | Search and text fields |
| `radiusCard` | 14dp | Only grouped content that benefits from a clear container |
| `radiusSheet` | 24dp | Modal bottom sheet upper corners, if consistent with Material 3 setup |
| `radiusPill` | 999dp | Pills, progress/small tags where appropriate |

- Standard divider: 1dp using the semantic `border` color.
- Prefer no shadow for rows and chips.
- Use low elevation/tonal difference to establish hierarchy, not dark blurry drop shadows.
- A modal surface may be visibly distinct from the page without a heavy shadow.
- Do not nest rounded cards inside rounded cards unless the second container expresses a real sub-group.

---

## 6. Icon system

### 6.1 Style

Create one coherent, clean outline icon set for Folio instead of mixing filled Material icons with unrelated glyph controls.

- Grid: 24×24 viewBox.
- Stroke: 1.75–2dp, adjusted optically per icon.
- Stroke caps and joins: rounded.
- Fill: none for ordinary actions; use a limited filled state only where it conveys selection (for example, an active bookmark) and apply that rule consistently.
- Artwork size: usually 22–24dp; smaller 18–20dp icons are acceptable for dense metadata only.
- Tap target: minimum 48×48dp.
- Color: `textSecondary` for quiet actions, `textPrimary` for high relevance, `accent` for active/selected states, and semantic `danger` for destructive actions.
- Do not use emoji as functional icons.

### 6.2 Initial icon inventory

| Name | Meaning / use |
|---|---|
| `library` | Library destination |
| `book-open` | Open/current reading |
| `search` | Library or in-book search |
| `plus` | Import/add book |
| `sort` | Sort options |
| `filter` | Filters/collections |
| `more-horizontal` or `more-vertical` | Context/overflow menu — pick one orientation by placement |
| `arrow-left` | Back/navigation |
| `list-tree` | Table of contents |
| `bookmark` | Save or view bookmark |
| `highlighter` | Highlight selected passage |
| `notebook-pen` | Notes |
| `type` | Reading appearance/text settings |
| `sun-moon` | Reading theme |
| `align-left` / `align-justify` | Text alignment |
| `minus` / `plus` | Text size only when paired in a labelled control |
| `check` | Confirmation/selected state |
| `x` | Close or clear; label/accessibility differs by action |
| `trash-2` | Delete, styled as destructive |
| `edit-3` | Edit note/metadata |
| `clock` | Time left / last read metadata |
| `hard-drive` | Storage/backup section |
| `refresh-ccw` | Reset settings |

### 6.3 Implementation guidance

- Keep icons in a dedicated `ui/icons` package or `res/drawable` vector resources.
- Prefer vector assets that scale cleanly; no PNG icon exports for interface icons.
- Add content descriptions at the action call site, not inside a generic icon definition.
- Provide visual and semantic selected states for theme swatches, bookmarks and navigation destinations.
- Avoid shipping several icons that mean the same thing. For example, `x` for closing a modal and `x` for clearing a search need different accessibility labels even if the vector is shared.

---

## 7. Component specifications

### 7.1 Primary, secondary, and destructive actions

**Primary button**
- Filled `accent` background, `onAccent` label.
- 48–52dp min height, 12dp corner radius.
- Label uses 13sp medium/semibold, sentence case.
- One primary button per small decision area whenever possible.

**Secondary button**
- Surface or transparent background, semantically clear outline/text treatment.
- Same height, type, corner and horizontal padding as the primary button.

**Destructive action**
- Use `danger` for icon/label and keep it separate from routine primary actions.
- Confirmation title states the object and consequence; e.g. “Delete this book?” rather than “Are you sure?”.
- Mention consequential data removed, especially notes/highlights/progress, before final confirmation.

### 7.2 Icon button

- 48×48dp target, 22–24dp vector artwork.
- Transparent base by default; subtle `surfaceSubtle` press/focus background.
- Visible keyboard focus where relevant.
- Always provide an accessible action description.
- Don't use icons for primary tasks if the user cannot reasonably infer their meaning; pair them with a label or onboarding text when needed.

### 7.3 Search field

- 48–52dp height, 12dp radius, `surfaceSubtle` fill, no heavy shadow.
- Leading search icon, label/placeholder in `textSecondary`.
- Focus state: subtle `accent` border or tonal change.
- Trailing clear icon appears only with non-empty query.
- Separate close/back action belongs outside the field when search is a full-screen mode.
- Support IME Search, visible query, loading indicator when meaningful, result count, and distinct empty/no-results/error content.

### 7.4 Book list row

- One shared page canvas, no shadow by default.
- Cover 56×78dp (compact) or 60×84dp (comfortable); 4dp radius and suitable crop.
- Title uses `bookTitle`; up to two lines.
- Author/type uses `bodySmall`; do not present `book`/`article` as if it were an author. Use explicit metadata or a quiet type chip when author is missing.
- Progress track 3–4dp; percent/time is secondary metadata.
- Row tap opens/resumes. Overflow menu contains secondary actions only.
- 12–16dp vertical padding; 12–16dp gap from cover to text; divider uses `border`.

### 7.5 Book placeholder cover

- Preserve the original title and any real author metadata; do not fabricate a cover image.
- Use a restrained tint palette derived from the Folio green/warm neutral system, not the current saturated multi-color strip.
- Use one quiet visual motif: a thin border, a large initial, or abstract book-spine geometry. The placeholder should have identical dimensions to real covers so rows align.
- Ensure contrast in both themes and distinguish different books without relying on color alone.

### 7.6 Reader chrome

**Top bar**
- Compact; no horizontal scrolling.
- Back button and book/chapter title remain clear.
- Visible actions: Reading appearance, Search, and a consistent Overflow menu. Use TOC or Bookmark directly only if space and frequency justify it.
- Chrome surface is drawn from the selected reading theme, so it blends with the page rather than flashing app theme colors over the EPUB.

**Bottom reading dock**
- One slim progress control, chapter label, percent and optional time left.
- When the user drags, show the new percent/location immediately; commit navigation when the gesture ends, using existing behavior.
- Keep safe-area padding and a large enough slider touch target.
- If title is unavailable, still allow progress control where there is a valid reading location; don't show malformed separators like “— 0%”.

**Reading appearance sheet**
- Header: `Reading appearance`; close control; optional preview sample.
- Theme previews: five labelled swatches/cards with a strong selected outline/check.
- Text size: slider with minus/plus buttons and current value; avoid making the number the only feedback.
- Font: three clear options; if Publisher is selected, explain that the book's own typeface is used.
- Spacing/Layout grouped separately from Theme/Typeface to reduce scanning.
- Show “Applies to this book” and `Reset to default` only when relevant.
- Use live updates, but debounce persistence if needed; do not delay visual feedback while writing preferences.

### 7.7 Filter chips

- 32–36dp target height visually, but maintain a useful touch area around the chip.
- Unselected: transparent or subtle fill with border.
- Selected: `accentContainer` plus readable foreground, clear check/selected visual if necessary.
- Keep “All” first. Avoid an uncontrolled long horizontal rail of hundreds of tags; provide a filter sheet/search for larger collections.
- After filtering to zero items, expose a `Clear filter` action.

### 7.8 Modal bottom sheet

- One standard header pattern: title, optional subtitle, close button.
- 24dp horizontal content inset; 16–24dp vertical sections.
- 48dp list-row actions and controls.
- Consistent drag handle and corner shape using Material 3 sheet behavior.
- Keep the keyboard from obscuring input/Save actions.
- Use a full-height sheet or screen for forms that outgrow a compact dialog.

### 7.9 Notes / saved passage row

- Quote is the content hero; `bookTitle` is attribution; the optional note appears below the quote.
- Highlight color appears as a narrow rail/dot or small label, not a full saturated panel.
- Show type metadata (Highlight / Note / Bookmark) in a consistent position.
- Row tap returns to the passage. Edit/delete remain reachable but secondary.
- Truncate exceptionally long quotes with a graceful ellipsis and show the full passage when opening details or returning to the reader.

### 7.10 Settings row and sections

- Section title uses `sectionTitle`.
- Standard row: 20–24dp leading icon optional, title, optional one-line summary, trailing current value/chevron/switch/action.
- Minimum row height: 56dp; larger where a summary or selection preview is present.
- Group related rows with dividers or consistent vertical spacing, not separate floating cards for every setting.
- Destructive/reset actions are separated from normal preference choices.

### 7.11 Empty, loading, and error states

**Empty state**
- One simple icon; one clear heading; one concise explanation; one primary CTA where there is a useful next step.
- Keep total content compact so it remains above the fold on small screens.

**Loading state**
- Match final row geometry with skeletons for the library; use an inline progress indicator for a short action and determinate progress where available.

**Error state**
- State what failed in human terms; preserve the user's data; provide a next action.
- Do not show raw exception/stack details in the main message.

**Recovery state**
- Warning color and icon are semantic; message summary is concise; review/repair action is explicit.
- Do not silently discard broken or interrupted documents.

---

## 8. Motion and feedback

- Standard micro-interaction: 160–220ms ease-out.
- Sheet transition: follow Material 3 defaults unless a tested change improves clarity.
- Pressed state: subtle tonal surface change; no scaling bounce for ordinary controls.
- Progress updates: immediate. Search: preserve current debounce behavior but visually distinguish searching from no results.
- Focus and selection transitions should not move surrounding layout unexpectedly.
- Keep animation low-key; the content should remain the center of attention.

---

## 9. Accessibility baseline

- Normal text contrast target: at least 4.5:1 against the actual surface. Large text and meaningful UI graphics should meet applicable contrast criteria.
- All actionable targets: at least 48×48dp.
- Explicit content descriptions for icon-only actions.
- Meaningful labels for color swatches and selected states.
- Text is still understandable without color.
- Test dynamic font scales and narrow widths; no core control should disappear off-screen.
- Support keyboard/IME behavior in search and form flows.
- Use accessible progress semantics for the reader scrubber and reading progress.
- Check Android accessibility scanner/TalkBack on a real emulator/device after implementation.

---

## 10. Compose implementation map

Suggested structure; adapt to the current package layout rather than moving files for the sake of moving them.

```text
ui/
  theme/
    Color.kt                 // semantic app + reading tokens
    Theme.kt                 // complete light/dark Material 3 schemes
    Type.kt                  // explicit UI text styles
    Spacing.kt               // spacing and component dimension constants
    Shapes.kt                // canonical shape tokens
  components/
    FolioButton.kt
    FolioIconButton.kt
    FolioSearchField.kt
    FolioSectionHeader.kt
    FolioEmptyState.kt
    FolioStatusBanner.kt
    FolioSettingsRow.kt
    FolioBookRow.kt
    FolioQuoteRow.kt
    FolioSheetHeader.kt
  icons/
    FolioIcons.kt            // one coherent vector icon family
reader/
  ReaderChrome.kt
  ReadingAppearanceSheet.kt  // new extracted component for secondary controls
  ReaderThemePalette.kt      // reader-specific palette mapping
```

### Component rules in Compose

1. Define token values once. Avoid inlined `Color(0x...)`, inconsistent opacity values, and shape literals across unrelated screens.
2. Use `MaterialTheme.colorScheme` for app UI roles and a dedicated reader palette for the book's chrome. Do not overload `MaterialTheme` with a book-specific theme while the rest of the app is on another scheme.
3. Replace `val Typography = Typography()` with an explicit `Typography(...)` definition. Avoid the naming ambiguity in implementation by naming the instance `FolioTypography` if that improves readability.
4. Keep Material 3 components where their semantics and behavior are appropriate, but customize the tokens and Compose structure to match Folio. Avoid restyling a default component with unrelated ad hoc values on every call.
5. Keep `contentDescription` meaningful at the action call site. Decorative/duplicative icons should use `null` descriptions.
6. Use one chosen vector asset source throughout. If custom paths are authored in Compose, keep drawing definitions centralized and test RTL behavior for directional arrows.
7. Introduce a persistent **App appearance** preference only if needed. Keep reading preference serialization and per-book overrides compatible with existing stored data.
8. Preserve business/state logic in `LibraryManager`, importer, search services, annotations and Readium navigation; screen redesign should not duplicate it in composables.

---

## 11. Design tokens summary

```kotlin
// Naming illustration only — keep in one central source file.
object FolioSpacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp
}

object FolioDimensions {
    val touchTarget = 48.dp
    val icon = 24.dp
    val searchHeight = 50.dp
    val bookCoverWidth = 56.dp
    val bookCoverHeight = 78.dp
    val divider = 1.dp
    val progressTrack = 3.dp
}
```

Use these values as semantic tokens rather than hard-coded literals. Not every component must use the same radius or padding: consistency means following a few intentional rules, not making every element identical.

---

## 12. Visual QA matrix

| Surface | Light app UI | Dark app UI | Paper reader | True black reader | Large text / narrow width |
|---|---|---|---|---|---|
| Library | Required | Required | N/A | N/A | Required |
| Library empty/import | Required | Required | N/A | N/A | Required |
| Search/results/no-results | Required | Required | N/A | N/A | Required |
| Reader chrome shown/hidden | N/A | N/A | Required | Required | Required |
| Reading appearance sheet | Required | Required | All theme previews | All theme previews | Required |
| Contents and in-book search | N/A | N/A | Required | Required | Required |
| Highlight/note flow | N/A | N/A | Required | Required | Required |
| Notes list and empty state | Required | Required | N/A | N/A | Required |
| Settings/backup/restore | Required | Required | N/A | N/A | Required |
| Import failure/recovery | Required | Required | N/A | N/A | Required |

---

## 13. Definition of done

- The token system is used consistently across all major screens.
- There is a single coherent icon family with no ad hoc Unicode symbols for controls.
- Interface typography is explicit and reader typography remains independent.
- The library is calm and editorial, but import/search/sort remain discoverable.
- Reader controls are compact, labelled, and never depend on horizontal scrolling to reveal primary actions.
- App appearance and reading appearance are named and persisted separately if both exist.
- Light/dark app themes and all reading themes are tested with screenshots.
- Existing reading, import, progress, highlight, note, backup and restore behavior remains intact.
