# R2b — Reading comfort, slice 1 (mobile)

Session M-8 (Oct 17, 2026). Commit `01ce6e5`. 100/100 tests green (93 → 100).

## What ships

1. **Hardware/Bluetooth page turning** — `PageTurnKeys` (pure, 4 tests):
   volume up = backward, volume down = forward (Play Books / Moon+ Reader
   convention), `MEDIA_NEXT/PREVIOUS` (Bluetooth page-turners), `PAGE_UP/DOWN`,
   `DPAD_LEFT/RIGHT`. `ReaderActivity.onKeyDown` consumes the events **before
   the WebView** (first press only, no key-repeat flooding) and calls the
   non-suspend `OverflowableNavigator.goForward/goBackward(false)`.
   - HOT FACT: `KEYCODE_VOLUME_UP` = 24, `KEYCODE_VOLUME_DOWN` = 25 — inverted
     from intuition; I swapped them on the first try and the JVM test caught it.

2. **Brightness overlay** — dark scrim layered under the reader chrome (controls
   stay crisp); a `br` stepper (30–100, 10% steps) sits in the typography row.
   Saved through the existing schema-1 `ReadingPrefs.brightness` field — the
   settings.json contract is unchanged. `brightnessAlpha` mapping (100..30 →
   0..0.7 scale, never fully black) is pure + tested. The scrim `Box` has no
   pointer input, so taps fall through to the navigator (same mechanism the
   chrome spacer uses).

3. **Interactive scrubbing pillar** — the bottom pillar's percentage became a
   Slider bound to the publication's `totalProgression`. Dragging keeps a local
   thumb value (so live relocation updates don't fight the drag) and shows the
   dragged % in the label; on release `ReaderActivity.scrubTo` asks
   `LocatorService.locateProgression(p)` and jumps via `navigator.go(locator`
   `, false)` **on the main thread** (it mutates the view pager).

4. **Predictive back** — `android:enableOnBackInvokedCallback="true"` in the
   manifest (opt-in; no-op below API 33).

## Constraints / notes

- Touch plumbing fact (R2): the chrome `ComposeView` overlays the navigator in
  a plain `FrameLayout`; Compose nodes without pointer input do not consume
  touches, so events continue to the WebView siblings below — this is why the
  scrim can safely cover the whole screen.
- No new schema-1 fields.
- Device smoke still blocked — volume keys, scrub, brightness, highlight UI are
  all verified at the code/test level only.

## Deferred (R2b remainder)

Time-left in the pillar (needs the ReadingPace/stats port from
`packages/core/src/reading/stats.ts` — credit rule + relocation gaps), page-turn
animations (Readium-internal), paragraph-snap, tap-zone config, per-book
typography overrides, image zoom (FXL), volume-key on/off setting (a new
settings.json field would be a schema contract change — needs decision).
