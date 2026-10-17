# L1 residue — reader splash (session M-16, Oct 17 2026)

Commit `e2f8181`. The last L1-residue item: opening a document now shows the
extracted cover + spinner instead of a blank WebView while Readium builds the
publication and the navigator renders its first page.

## Mechanics

- **Decode**: `CoverLoader.decode(store, path, reqW, reqH)` — new `reqW/reqH`
  params (defaults stay the 132×174 card thumb). Splash asks for 660×990 px
  (~200dp box at 3x). LRU cache key is now size-scoped (`path@WxH`), so the
  card thumb and the splash can decode the same file at their own sizes
  without fighting over one cache slot.
- **Splash layer**: `ReaderSplash` — full-screen theme-surface Box, centered
  cover (200×300dp, `ContentScale.Crop`, 12dp radius) + `CircularProgressIndicator`,
  wrapped in `AnimatedVisibility(exit = fadeOut(450ms))`. It sits ABOVE the
  chrome (clean takeover while loading) and below the annotation menu.
- **Cover source**: `meta.files.cover` (covers contract, doc-dir-relative),
  decoded on the loader's IO scope right after the meta/reading checks —
  i.e. concurrently with `openPublication`.
- **Ready signal**: Readium 3.0's `EpubNavigatorFragment.Listener` has no
  page-loaded callback (verified via javap: the hierarchy only exposes
  `onExternalLinkActivated`, `shouldJumpToLink`, tap/drag, `onResourceLoadFailed`,
  `onJumpToLocator`), so the splash drops on the navigator's **first
  `currentLocator` emission** (the same flow that feeds the progress pillar).
- **Safety net**: an 8s timeout (loadScope job) also drops the splash, so a
  silently failed render can never trap the user on it. Job dies with the
  activity (`loadScope.cancel()` in `onDestroy`).
- **Touch behavior**: the splash has no pointerInput, so taps fall through to
  the navigator exactly like the existing brightness scrim (tap still toggles
  chrome during load).

## Tests

No new pure logic (view-layer + decode plumbing only) → count stands at
**163/163 green**; `assembleDebug` green.

## Verified already / not verified

- Verified: compiles, unit suite green, diff reviewed against the covers
  contract (`files.cover` path shape matches `LibraryUi.item`'s).
- Not verified: on-device timing/look — still blocked on the missing AVD
  system image (see tracking NEXT SESSION).
