# Mobile cloud sync (Android)

The Android app mirrors the library to the user's own Google Drive, using the
same protocol as the web app (spec §8, `packages/core/src/sync`). The device
library stays the source of truth; Drive holds a visible `Folio Library` folder.
Another **Android** install signed into the same Google account can read that
folder. The **PWA** uses a separate OAuth grant and cannot see it; use a backup
ZIP to move a library between web and Android.

## What it does

- **Settings → Cloud sync → Connect Google Drive** opens Android's system
  Google account chooser (no password ever reaches Folio). Choosing an account
  and granting Drive access are two separate steps: consent is requested on the
  first sync, and the UI only shows "Synced" after a real sync succeeds.
- **Sync now** is bidirectional: it uploads what is new on this device and
  downloads what the other devices have, then merges notes, reading progress
  and collections (newer timestamp wins).
- Files are mirrored one by one into a Drive folder named **Folio Library**,
  so the books are visible and downloadable outside Folio.
- **Auto-sync** (opt-in) mirrors the library every six hours, on an unmetered
  network, while the battery is not low. It is off until you turn it on.
- Deletions never propagate (version-one rule, same as the web app).

## Layout in Drive

```
Folio Library/
  manifest.json                 (uploaded last — the commit point)
  meta.json
  read.html
  book.epub
  ...
```

Drive addresses files by id, not by path, so each mirrored file records its
library-relative path and SHA-256 in the Drive `appProperties`
(`relPath`, `sha256`). The device keeps its own `sync/state.json` mapping
path → `{driveId, sha256, remoteModifiedAt}`; that file is local only and is
never uploaded.

## One-time Google Cloud setup

The app ships no client secret: the OAuth client id is derived by Android from
the package name (`dev.folio.mobile`) and the signing certificate. You still
need to create the client once:

1. **Google Cloud project** — <https://console.cloud.google.com> (a project is
   enough; no billing).
2. **Enable the Google Drive API** — *APIs & Services → Library → Google Drive
   API → Enable*.
3. **OAuth consent screen** — user type *External*, publishing status
   *Testing*, app name `Folio`, your own Google account under *Test users*.
4. **Scopes** — add `https://www.googleapis.com/auth/drive.file`
   (*See, edit, create and delete only the specific Google Drive files you
   use with this app*). The PWA uses the same scope name but is a different
   OAuth client, so each client only sees the files it created.
5. **Android OAuth client** — *Credentials → Create Credentials → OAuth client
   ID → Android*:
   - package name: `dev.folio.mobile`
   - SHA-1: the debug fingerprint is enough for local builds:
     ```
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```
   - add a second client for the release keystore's SHA-1 before shipping.
6. In *Testing* mode only the listed test users can authorise. Moving to
   *Production* needs Google's OAuth verification (free, but it has to be
   applied for).

## Known limitation: `drive.file` is per-app

`drive.file` only grants access to files the app itself created. The web app
and the Android app are separate OAuth clients, so each sees the mirror files
*it* uploaded, not the other's. Practically:

- Two Android devices (or the Android app on its own) sync perfectly — they
  share the same account and the same app, hence the same folder.
- Using the web app and the Android app side by side keeps two mirrors,
  because neither can see the other's files.
- The manual bridge between the two remains the backup ZIP
  (Settings → Backup → Export / Restore), which is byte-compatible in both
  directions.

Widening the scope to `drive` (full Drive access) would let both apps share
one folder, but that is a *restricted* scope: it needs a Google security
assessment before a public release, and it would give Folio access to the
user's entire Drive. The narrow scope is the deliberate trade-off.

## Not implemented yet

- Deletion propagation and tombstones.
- The mirror folder name is fixed (`Folio Library`).
