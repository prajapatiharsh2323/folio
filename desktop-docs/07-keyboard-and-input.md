# Folio Desktop — Keyboard, Pointer and Input Spec

Port source: `mobile/.../core/reader/PageTurnKeys.kt` semantics + the PWA-era Windows
shortcut guidance in `docs/spec.md`/`docs/guide.md`.

**Rule: the reader is keyboard-complete, and any mouse path has an equivalent.**

---

## 1. Global (app, any route)

| Key / action | Behavior |
|---|---|
| `Ctrl+F` | in-reader: open in-book search; in-library: open library search panel |
| `Ctrl+,` | open Settings |
| `Ctrl+I` | import file (library routes) |
| `Esc` | close topmost panel/dialog/selection mode (same as mobile back) |
| `Ctrl+1/2/3/4` | Library / Collections / Notes / Settings (when not in reader) |
| `F11` | enter/exit fullscreen |
| `Ctrl+-` / `Ctrl+=` / `Ctrl+0` | UI zoom of the whole webview (80/90/100/110/…%), not reader text size |
| `Ctrl+Q` | quit (confirm only if a reader has unsaved progress) |

## 2. Reader keys

Mobile premise (one keystroke, no key-repeat) carries over:

| Key | Behavior |
|---|---|
| `→` / `PageDown` | page turn forward (paginated) / scroll down a step (scrolled) |
| `←` / `PageUp` | page turn backward / scroll up a step |
| `Space` / `Shift+Space` | forward / backward (paginated: next/prev page; scrolled: ~90% viewport) |
| `↓` / `↑` | scrolled flow: smooth scroll line-step; paginated: same as Space/Shift+Space |
| `Home` / `End` | jump to book start / end (with the progress write gate) |
| `Enter` | (chapter list/TOC focus) follow focused item |
| `t` | toggle TOC panel |
| `Ctrl+B` | add/remove bookmark at current position |
| `m` | toggle chrome (top bar + pillar) |
| `MediaNext`/`MediaPrev`, third-party page-turner HID page keys | page-turn forward/backward (mobile accepts MEDIA_NEXT/PREVIOUS + PAGE keys + DPAD; desktop accepts the first two) |
| Volume keys | **not** bound on desktop P0 (keyboard is always present); P2 optional setting |

Chrome toggle equivalents for mouse: middle-third click (parity), `m`, hovering the
bottom 40 px reveals the pillar for 3 s.

Selection: standard desktop selection with mouse (or double-click word / Ctrl-Shift-←/→
word-wise). On selection end, the floating toolbar appears exactly like mobile's
selection sheet; keyboard `Esc` dismisses it.

## 3. Mouse/trackpad

- Click zones (parity with mobile tap zones, both flow modes):
  - click left/`right` third of the canvas = page turn / scroll step;
  - click middle third = toggle chrome.
- Horizontal swipe/wheel = page turn in paginated flow (navigator's native handling);
  two-finger scroll = page turn with momentum, no refresh navigation.
- Pinch-zoom: forward nav zoom for the webview at OS level; reader canvas text size is
  NOT zoom — text size only via the Appearance panel (Ctrl +/- UI zoom applies to
  chrome only).
- Slider: drag the pillar scrubber for % jumps; thumb enlarges while dragging; chrome
  grace 1.2 s after release.
- Right-click (reader canvas): standard content menu suppressed; instead show
  Folio's anchored menu with the same actions as the selection toolbar when there is a
  selection, otherwise a reduced menu (chrome toggle, TOC, bookmark).
- Drag-and-drop of files: accepted in Library routes (adds import); in reader it's ignored.

## 4. Library keys

| Key | Behavior |
|---|---|
| `/` | focus library search panel |
| `↑/↓` | move row focus; `Enter` opens book; `Space` selects the row (multi-select) |
| `Ctrl+A` | select all (in-list focus) |
| `Del` | open delete-confirm for selected rows |
| `Filters` arrows | chips navigable with ←/→ when focus is on chip row |

## 5. Window management

- Window posed/remembers size+position in `user/window.json` (restore-clamped to worker area).
- Minimum window size 720×560; resize is fully reflow (list rails, canvas, panels respond).
- Closing the window while reading: flush progress write (04 §8) then quit — always
  write, never prompt.
- "Prevent display sleep while reading" honored while a locator fired within 45 s and
  the reader is the active route (04 §11).
- Taskbar jump list: "Open Folio" / "Import file…"
- Single instance: second launch with path args → sends them to first instance
  (tauri-plugin-single-instance) and then either imports or opens the doc.
- File associations: `.epub`, `.html`, `.txt` → opening Folio from Explorer opens that
  doc (or imports if not already in the library, then opens). Register in installer.
