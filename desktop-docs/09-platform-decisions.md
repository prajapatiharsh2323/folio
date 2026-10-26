# Folio Desktop — Platform Decisions (Windows)

Decision record for everything mobile has an Android-only construct for.

---

## 1. Stack decisions (locked)

- Tauri 2 + TypeScript/React frontend, Rust thin core.
- Reader engine: Readium web toolkit (`@readium/*`).
- Data: schema-1 byte-compatible JSON (see `05`).
- No PDF, TTS, OPDS in v1 (parity).

## 2. Google Drive on desktop

Mobile uses `play-services-auth` + `google-api-client-android` (Android-only). Desktop:

- **OAuth**: installed-app flow with loopback redirect
  (`http://127.0.0.1:<ephemeral-port>`) per current Google policy — open system browser
  to the consent URL, run a local HTTP listener bound to `127.0.0.1`, exchange code
  server-side-less. Desktop OAuth Client ID of type "Desktop app" with
  `verifier`/PKCE support is required by Google Cloud settings.
- Scope: `https://www.googleapis.com/auth/drive.file` ONLY (parity; same per-client
  limitation as mobile — Folio sees only its own `Folio Library` file/folder items).
- Token storage: access token in memory only; refresh token in Windows Credential
  Manager (via OS API directly or Tauri keyring plugin). Never in plaintext JSON.
- Revoke/disconnect: delete refresh token, and (parity with mobile disconnect) leave
  Drive contents intact.
- Consent flow copy = mobile's first-sync consent card.
- See `docs/mobile-cloud-sync.md` + `docs/decisions.md` phase 6/7 header for the
  Drive layout constraints Folio must respect (`Folio Library/` root).

## 3. File associations & shell integration

- Installer registers: `Folio.epub`, `.html`, `.txt` (ProgID `Folio.Document1/2/3`) —
  icon: Folio mark, command: `open <path>`.
- Second-launch path args go through single-instance plugin → run the import/Open pipeline.
- Jump list (optional P1): Open Folio / Import file.
- Tray icon: v1 = NO tray (keep quiet; mobile has no background resident). Revisit only
  if auto-sync needs to outlive the window (not planned).

## 4. Storage location & Folder Mode

- Default library root: `%APPDATA%/Folio/library`.
- "Windows Folder Mode": optional user-picked root (e.g. a Google Drive for desktop
  folder) — the same capability the original PWA planned (`docs/spec.md` §6);
  implemented by swapping the FileStore root (02 §4).
- File locking: Windows rename-over-open-handle retry (02 §4) + never hold handles
  while a Drive upload reads the file (stream & close).
- Free space query: `GetDiskFreeSpaceEx` equivalent (Rust).

## 5. Display & windows

- Reader fullscreen (F11 or max) uses custom window: hide native decorations; the Folio
  canvas covers the screen (mobile edge-to-edge parity), Esc/F11 exits.
- "Prevent sleep while reading": call `SetThreadExecutionState(ES_CONTINUOUS |
  ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED)` while the reader is active (04 §11);
  release on route exit.
- Dark-mode detection: `prefers-color-scheme` in webview tracks OS automatically; the
  app theme setting default = System.
- Display scaling: webview zoom respects OS scale; settings note about scaling.

## 6. Background work

- No WorkManager. Auto-sync = in-app timer (every 15 min, while app open) — v1 scope.
- A real OS auto-start / Task-Scheduler integration is NOT in v1 scope (parity with
  mobile's opt-in periodic worker is satisfied by the in-app timer).
- Dictionary pack install: verified at app start (async); sha256 before touching user notes.

## 7. Update strategy (v1 scope)

- Ship as NSIS installer via Tauri bundler. Updates: manual-download in v1 (link in
  About). Tauri updater with a signing key evaluates as P2 — only enable with
  signed artifacts + a version manifest; defer the policy decision to a later phase.
- Authenticated code signing is optional-for-dev builds; document the
  `-dev-unsigned` suffix convention for test builds.

## 8. Security & privacy guardrails (port of `docs/spec.md` §10)

- All network: only Drive REST, only when sync enabled; user agents must not call 3p APIs.
- No telemetry, no analytics, no crash reporting in v1 (mini crash reporter within app
  logs only, offline).
- External URL opening restricted to `http/https` via the shell plugin (mobile parity).
- The webview is configured with no remote origins; CSP set to disallow remote scripts.
- HTML sanitization must drop scripts/styles/major event handlers (same allow-list
  semantics as mobile HtmlImporter — port its allow-list precisely).

## 9. CI / release pipeline

- GH Actions: `desktop-ci` — install pnpm + Rust toolchain, build on Windows runner,
  run Vitest + cargo test, produce NSIS artifact for each tag + main builds.
- Same fixture JSON files as mobile's test suite where portable (`mobile/app/src/test`
  fixtures) — keep parity tests drift-free (byte-compat policy).

## 10. Open items to resolve during build (not blockers)

1. Readium web toolkit maturity: confirm decoration/search APIs in the chosen
   version; if a gap exists, implement the same primitives (decoration painter +
   search over spine text) as a thin compatibility layer (mobile did the same
   bridging — `core/reader/ProgressBridge.kt` / `HighlightManager.kt`).
2. `maxWidth` (column cap) honoring — originally a PWA desktop reader feature; on
   desktop, prefer honoring it in scrolled flow via injected CSS var (mobile kept it
   inert). Decide after the first reader build.
3. Quote-card canvas rendering with the exact brand plate (charcoal `#23242F`,
   mark `#715CFC`) — needs one manual design pass against mobile's PNG output.
