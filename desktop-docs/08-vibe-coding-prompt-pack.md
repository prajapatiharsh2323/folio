# Folio Desktop — Vibe-Coding Prompt Pack

Ordered build phases for an AI coding agent. Feed prompts one phase at a time; each
phase assumes the previous is merged. Cross-reference the other docs in this folder and
the mobile source paths given. Phase lengths are calibrated so each prompt produces a
green, runnable repo (mobile parity: ~253 unit tests kept green per phase).

Locked stack: **Tauri 2 + TypeScript/React · Vite · Zustand · fflate · @readium/*
web toolkit · Rust thin core.**

---

## Phase 0 — Scaffold (bootstrap the repo)

```
Create a Tauri 2 app named "Folio" in ./desktop following desktop-docs/02 sections 1–3:
- pnpm monorepo layout: desktop/{src (frontend), src-tauri (Rust core)}
- Frontend: Vite + React 18 + TypeScript strict, plain CSS with custom properties,
  no component library.
- Rust core implements exactly the command set in desktop-docs/02 §2 (fs_read,
  fs_write_atomic, fs_delete, fs_move, fs_stat, fs_list_dir, sha256_file, zip_read,
  zip_write, open_external_url, prevent_display_sleep, dict_sqlite_query stub).
  All commands take paths as arguments; no Folio-specific logic in Rust; normalized
  absolute path guard on every path command; atomic-write with Windows rename retry
  (3 retries at 10/50/250 ms).
- Frontend FileStore interface per 02 §4 backed by these commands; every call
  serialized per-path by a mutex map.
- Vitest green on: FileStore atomicity, temp-dir round-trip, traversal guard.
- Window: "Library", min 720×560, default 1100×760, remembers size/position in
  user/window.json.
```

## Phase 1 — Design system + shell

```
Implement desktop-docs/03 entirely as CSS variables + React components, porting
mobile/app/src/main/kotlin/dev/folio/mobile/ui/theme/{Color,FolioTokens,Type}.kt
and .docs/design-system.md:
- tokens.css: full light+dark app palettes (03 §2), spacing scale, radii, type scale.
- theme.ts: OS-following light/dark switching with Light/Dark/System setting.
- Components: FolioButton, FolioChip, FolioRow, FolioPanel (right sheet), FolioDialog,
  FolioSectionHeader, FolioSettingsRow, FolioEmptyState, FolioBookRow, FolioIcons
  (24/20 px inline SVG components ported from ui/icons/FolioIcons.kt).
- Single-window shell with left sidebar (Library/Collections/Notes/Settings) per
  03 §5.1; router = simple Zustand route stack (no react-router needed).
- Check: contrast and spacing values equal the tables (unit test asserting token map
  equality against a checked-in JSON fixture).
```

## Phase 2 — Stores, model & library data layer

```
Port the schema-1 data model byte-compatibly (desktop-docs/05):
- types.ts ported exactly from mobile core/model/Types.kt (same field names/optionality).
- store/ = FileStore-backed repositories: ManifestRepo, ProgressRepo, AnnotationsRepo,
  CollectionsRepo, PrefsRepo (global + doc-settings), with the LibraryManager sticky
  rules from 05 §4 (dual-write invariant, manifest-as-commit-point, deletion cascade,
  tmp→rename writes).
- Slug generation + uuid via crypto.randomUUID.
- Vitest fixtures: take the JSON fixtures from mobile/app/src/test (port any that are
  language-neutral) and assert byte-level round-trip equality.
```

## Phase 3 — Import pipeline

```
Port the importer + EpubBuilder (desktop-docs/02 §6):
- importer/{fileDetector, epubImporter, htmlImporter, txtImporter, importDispatch}.ts
  mirroring mobile core/importer/* behavior exactly (format detect, DRM refuse,
  PDF refuse with copy "PDF is not supported", 50 MB cap).
- html sanitizer with the mobile HtmlImporter allow-list + up to 40 inline data-URI
  image extraction.
- clip/epubBuilder.ts: TXT/HTML → EPUB 3 (mimetype first stored uncompressed, nav.
  xhtml, deterministic uuid from contentHash), writing docs/<slug>/reading/book.epub.
- cover extraction (EPUB3 cover-image property + EPUB2 meta heuristic) and default
  cover art render (8-tint family by colorSeed + ink initials).
- index/text/<docId>.json generation for search.
- Duplicate dialog (Skip/Keep both/Replace) per 06 §1.6 incl. replaceDoc preserving
  annotations + progress.
- UI: import button + drag-drop + progress snackbar; empty state; damaged-doc and
  interrupted-import recovery banners/dialogs (06 §1.9).
- Vitest: every importer path + epub builder determinism.
```

## Phase 4 — Reader core

```
Implement desktop-docs/04 using @readium/* web toolkit:
- Reader route with splash (cover + spinner), 8-second failsafe, error state w/
  Retry/Back (04 §1, §11).
- navigator instantiated with ReadingPrefs; themes with exact hex map (04 §2);
  typeface stacks (04 §4); flow paginated/scrolled; brightness scrim (04 §6).
- Appearance right-panel: theme picker, size −/slider/+, typeface, line spacing,
  margins, alignment, flow, brightness, "Reset to defaults" + per-book scope note
  (04 §3); writes user/doc-settings.json override; reset removes only the override.
- chrome.ts overlay: top bar (back/title/Aa/search/⋯→TOC+bookmark), bottom pillar
  (chapter · percent · time-left + scrubber), auto-hide rules + 1.2 s scrub grace
  (03 §5.3), chrome surface per resolved theme.
- progress.ts: locator debounced persistence with 0.4%/500 ms gates + flush-on-close;
  resume cascade exactly desktop-docs/04 §8 (CFI first; fraction recompute fallback).
- pace.ts: port mobile/core/reader/ReadingPaceStats.kt math 1:1 + Vitest cases.
- Keep-screen-on via prevent_display_sleep command while reader active (04 §11).
```

## Phase 5 — Annotations

```
Port selection→annotation flow (04 §7):
- selection events → floating toolbar (4 swatches, Add note, Copy, Meaning placeholder).
- create/update/remove Annotation records with quote/prefix32/suffix32/location/kind/
  color/note/timestamps; highlight painting on navigator ready; toggle-off on same
  color+quote; bookmarks via ⋯ and Ctrl+B toggle semantics.
- annotation edit/delete menus; note editor panel; Notes screen per 06 §3 (filter
  pills All/Highlights/Notes/Bookmarks, color rail rows, tap→open at annotation via
  chapter + text-quote two-step jump), "[missing document]" rows still editable.
- Quote card (P1): canvas render per desktop-docs/09 §10.3 → Save PNG / Copy image.
```

## Phase 6 — Search (library + in-book)

```
Port search per 06 §6 and 04 §10:
- search/miniSearch.ts: use the `minisearch` npm package with mobile parity defaults
  (k1=1.2, b=0.7, fuzzy 0.2 latin >4 chars, prefix, AND) — OR port mobile's
  core/search/MiniSearch.kt if the vite bundle size dislikes it.
- search/normalise.ts: exact normalization order from SearchNormalise.kt (NFC,
  lowercase, strip ZWJ/ZWNJ, latin-only accent folding, preserve Devanagari marks)
  + Vitest with Hindi fixtures.
- Library search panel: build index on open per 06 §6, 250 ms debounce, grouped
  results (doc → chapter hits with bolded-term snippets), tap → reader open with
  chapterOverride then anchored text jump.
- In-book search: Readium search service + streaming results dropdown + jump.
```

## Phase 7 — Collections, filters, multi-select

```
Port UI behaviors per 03 §5.2/§5.5 and 06 §1/§2:
- filter chips (All/#tags/collections) + sort menu with the 4 orders (persisted).
- multi-select (hover checkboxes + Ctrl+click + Ctrl+A), add/remove-to-collection flows.
- Collections gallery (fan covers, counts, updated dates) + detail route with
  add-via-search picker, rename, delete-with-confirm, remove-member.
- details panel (tags editor + collection checkboxes + inline create + source url).
```

## Phase 8 — Settings, backup, keyboard round-off

```
- Settings screen per 03 §5.7 + 06 §4 (all six groups; app theme row; open-library-
  folder button via shell).
- backup/: port core/backup/* (streaming zip via fflate, backup.json meta with
  manifestSha256, restore flow Merge/Replace with summary counts and merge collision
  rules from desktop-docs/05 §5). Progress + cancel + confirm dialogs.
- keyboard.ts wiring per desktop-docs/07 (app + reader + library tables) incl. focus
  rings/trap behavior; volume keys NOT bound (P0).
```

## Phase 9 — Drive sync (P1)

```
- sync/: port core/sync/* semantics (desktop-docs/05 §6): Folio Library/ layout,
  state.json drive/sha map, newest-wins merge, deletions-never-propagate, docId
  remap on identical contentHash, manifest uploaded last, failure classification
  UI copy (06 §7).
- auth via loopback OAuth desktop client (09 §2): system browser, PKCE, ephemeral
  127.0.0.1 listener, refresh token in Windows Credential Manager, scope drive.file
  only. Consent card = mobile copy. CloudSyncSection uses it; "Sync now", auto-sync
  timer 15 min while app open, single-flight lock.
```

## Phase 10 — Dictionary (P2)

```
- bundle the SQLite pack produced by mobile/tools/build_dict.py; rust dict_sqlite_query
  command; install-time sha256 verify; headword normalization (DictionaryNormalizer.kt
  order); meaning card re-themed to the READING theme + GPL-2.0 attribution in the
  card footer and Settings→About.
```

## Phase 11 — Polish & platform

```
- File associations + single-instance + drop-open per 09 §3; jump list P1.
- window.json remember + restore-clamp; fullscreen reader (F11) custom chrome.
- CI/desktop-ci per 09 §9; NSIS installer artifact.
- Performance budget checks per 04 §12; a11y pass per 03 §7.
```

---

## Prompt-writing tips for this project

1. Always paste the relevant doc section into the prompt — the agent can't see this
  repo unless working in it; cite mobile paths like
   `mobile/app/src/main/kotlin/dev/folio/mobile/core/reader/ReaderResume.kt`.
2. Ask for parity tests first when porting a `core/*` module: "first port the mobile
   test fixtures, then implement until green."
3. Byte-compat is non-negotiable: any field rename must be rejected in review.
4. Keep the reader engine behind a thin `ReaderEngine` interface (see `docs/spec.md`
   §7) so a future engine swap doesn't reopen the app.
5. UI tuning should always happen against `.docs/design-system.md`, not against taste.
