# Folio — Complete User Guide

Welcome to **Folio**, a private, local-first reading library for your books and web articles. Folio runs as an installable Progressive Web App (PWA) on Android and Windows, accompanied by a browser extension for clipping clean articles.

---

## Table of Contents

1. [Core Philosophy & Architecture](#1-core-philosophy--architecture)
2. [Getting Started & Installation](#2-getting-started--installation)
   - [Running Locally](#running-locally)
   - [Installing on Android (PWA)](#installing-on-android-pwa)
   - [Installing on Windows (PWA / Edge / Chrome)](#installing-on-windows-pwa--edge--chrome)
3. [Importing Documents](#3-importing-documents)
   - [Supported Formats](#supported-formats)
   - [Drag & Drop Import](#drag--drop-import)
   - [Android Share Target](#android-share-target)
   - [Windows File Handler ("Open with Folio")](#windows-file-handler-open-with-folio)
   - [Duplicate Prevention & Integrity](#duplicate-prevention--integrity)
4. [Using the Web Article Clipper Extension](#4-using-the-web-article-clipper-extension)
   - [Installing the Extension in Chrome / Edge / Brave](#installing-the-extension-in-chrome--edge--brave)
   - [Clipping an Article](#clipping-an-article)
   - [The Clip Inbox & Packaging](#the-clip-inbox--packaging)
5. [Reading Experience & Controls](#5-reading-experience--controls)
   - [Navigation & Page Turning Gestures](#navigation--page-turning-gestures)
   - [Customizing Typography & Themes](#customizing-typography--themes)
   - [Table of Contents & Reading Progress](#table-of-contents--reading-progress)
   - [Screen Wake Lock](#screen-wake-lock)
6. [Highlights, Notes & Bookmarks](#6-highlights-notes--bookmarks)
   - [Highlighting & Adding Notes](#highlighting--adding-notes)
   - [Notes Screen & Filtering](#notes-screen--filtering)
   - [Exporting Notes to Markdown](#exporting-notes-to-markdown)
7. [Library Organization & Search](#7-library-organization--search)
   - [Collections & Tags](#collections--tags)
   - [Document Details & Editing](#document-details--editing)
   - [Multilingual Full-Text Search (English & Hindi)](#multilingual-full-text-search-english--hindi)
8. [Backup & Restore](#8-backup--restore)
   - [Exporting a Streaming ZIP](#exporting-a-streaming-zip)
   - [Restoring: Merge vs. Replace](#restoring-merge-vs-replace)
   - [Backup Reminder Banner & Storage Persistence](#backup-reminder-banner--storage-persistence)
9. [Google Drive Mirror & Sync](#9-google-drive-mirror--sync)
   - [How Drive Sync Works (Zero Servers)](#how-drive-sync-works-zero-servers)
   - [Google Cloud Console Setup (3 Minutes)](#google-cloud-console-setup-3-minutes)
   - [Connecting & Syncing in Folio](#connecting--syncing-in-folio)
   - [Two-Device Convergence](#two-device-convergence)
10. [Windows Folder Mode (Direct Filesystem)](#10-windows-folder-mode-direct-filesystem)
11. [Keyboard Shortcuts Cheat Sheet](#11-keyboard-shortcuts-cheat-sheet)

---

## 1. Core Philosophy & Architecture

- **Local-First & Offline**: Your books, articles, reading positions, notes, and search index live directly in your browser's **Origin Private File System (OPFS)**. No internet connection is needed to read, search, or organize your library.
- **Zero Intermediary Servers**: Folio has no user accounts, no telemetry, no tracking scripts, and no central servers.
- **Content Immutability**: Imported book files are stored once and never modified; notes, progress, and collections are saved in separate user files.
- **HTML Sanitization**: All web content imported or clipped passes through strict DOMPurify sanitization. Scripts and iframes are stripped out before saving.

---

## 2. Getting Started & Installation

### Running Locally

To run the web app and extension on your machine:

```bash
# Clone and install dependencies
pnpm install

# Start the web app dev server (default: http://localhost:5173)
pnpm --filter @folio/web run dev

# Build the clipper browser extension
pnpm --filter @folio/clipper run build
```

### Installing on Android (PWA)

1. Open Folio in **Google Chrome** on your Android device (e.g. `https://your-domain.pages.dev` or `http://<local-ip>:5173`).
2. Tap the Chrome menu (`⋮`) and select **Install app** or **Add to Home screen**.
3. Alternatively, navigate to **Settings** within Folio and tap **Install Folio App**.
4. Once installed:
   - Folio runs in full-screen standalone mode with no browser address bar.
   - The status bar automatically adapts to your active theme (Light, Warm, or Dark).
   - System back gestures cleanly close drawers, sheets, and open books before exiting the app.

### Installing on Windows (PWA / Edge / Chrome)

1. Open Folio in **Microsoft Edge** or **Google Chrome**.
2. Click the **App available / Install** icon in the address bar (or menu `...` > **Apps** > **Install this site as an app**).
3. Folio launches in its own native-style window with Windows taskbar integration, keyboard navigation, and file association support.

---

## 3. Importing Documents

### Supported Formats

Folio accepts:

- **EPUB** (`.epub`): Reflowable books and articles with table of contents, chapters, and CFI location tracking.
- **PDF** (`.pdf`): Canvas-rendered pages with selectable text layers and pinch-to-zoom.
- **HTML** (`.html`, `.htm`): Single-file web articles, documentation pages, or blog posts.
- **TXT** (`.txt`): Plain text documents (UTF-8, with or without BOM).

### Drag & Drop Import

1. Click **Import** in the navigation bar (or visit `#/import`).
2. Drag and drop any files or folders directly into the drop zone, or click **Browse Files**.
3. Folio inspects the content bytes (magic numbers), extracts the metadata (title, author, table of contents), builds the full-text search index, and adds them to your library.

### Android Share Target

When browsing files in your Android **Files** app or viewing a downloaded book:

1. Tap **Share** on any `.epub`, `.pdf`, `.html`, or `.txt` file.
2. Select **Folio** from the system share sheet.
3. Folio opens and automatically adds the document to your offline library.

### Windows File Handler ("Open with Folio")

When Folio is installed as a Windows app:

1. Right-click any `.epub` or `.pdf` file in Windows File Explorer.
2. Select **Open with > Folio**.
3. The app opens and adds the book directly to your library.

### Duplicate Prevention & Integrity

Every document has a SHA-256 cryptographic hash calculated from its content bytes. If you import a file that is already in your library, Folio detects it and skips re-importing to prevent duplicate clutter.

---

## 4. Using the Web Article Clipper Extension

The Folio Clipper is a Manifest V3 browser extension for desktop Chrome, Edge, and Brave that turns messy web articles into clean, sanitized offline EPUB books.

### Installing the Extension in Chrome / Edge / Brave

1. Build the extension:
   ```bash
   pnpm --filter @folio/clipper run build
   ```
2. Open your browser and navigate to `chrome://extensions` (or `edge://extensions`).
3. Toggle on **Developer mode** in the top right.
4. Click **Load unpacked** and select the built folder:
   `d:\folio\apps\clipper\.output\chrome-mv3`
5. The Folio Clipper icon appears in your browser toolbar.

### Clipping an Article

When reading any article or blog post:

- **Option 1**: Click the Folio Clipper toolbar icon and press **Clip to Folio**.
- **Option 2**: Right-click anywhere on the page and select **Clip to Folio**.
- **Option 3**: Press the keyboard shortcut (`Alt+Shift+C` on Windows, or custom configured).

### The Clip Inbox & Packaging

1. The clipper uses Mozilla Readability to extract the headline, author, publication date, and body text.
2. DOMPurify cleans the HTML: removing tracking pixels, ads, navigation headers, social buttons, and scripts.
3. Article images (up to 40) are saved with offline SHA-256 references.
4. The article opens in Folio's **Clip Inbox** (`#/inbox`), where you can:
   - Preview the cleaned reading copy.
   - Adjust the title and author.
   - Add tags or assign it to a collection.
   - Click **Save to Library** to package it into a permanent offline EPUB 3 document.

---

## 5. Reading Experience & Controls

### Navigation & Page Turning Gestures

| Action                            | Phone / Touch                          | Keyboard / Mouse                    |
| --------------------------------- | -------------------------------------- | ----------------------------------- |
| **Next Page**                     | Tap right 1/3 of screen, or swipe left | `Right Arrow`, `Page Down`, `Space` |
| **Previous Page**                 | Tap left 1/3 of screen, or swipe right | `Left Arrow`, `Page Up`             |
| **Toggle Chrome (Header/Footer)** | Tap middle 1/3 of screen               | Click middle 1/3 of screen          |
| **Open Table of Contents**        | Tap TOC icon in top bar                | Press `T`                           |
| **Close Sheets / Exit Selection** | Tap backdrop or back gesture           | Press `Escape`                      |
| **In-Book Search**                | Tap Search icon in header              | Press `/`                           |

### Customizing Typography & Themes

Tap the **Typography (Aa)** button in the top bar to open the reading preferences drawer:

- **Themes**:
  - **Light**: Crisp paper white (`#FAFAF7`) with dark charcoal text.
  - **Warm**: Sepia tint (`#F4ECD8`) reducing eye strain in daylight.
  - **Dark**: Low-emission dark charcoal (`#121214`) for night reading.
- **Font Family**:
  - _Literata_ (refined book serif)
  - _Noto Serif Devanagari_ (authentic typography for Hindi)
  - _Inter / System Sans_ (clean sans-serif)
  - _Monospace_ (code and technical articles)
- **Font Size**: Adjustable from 14 px to 32 px.
- **Line Spacing & Margins**: Compact, standard, or relaxed leading and screen gutters.
- **Alignment**: Left-aligned or justified text.
- **Reading Flow**:
  - _Paginated_: Classic horizontal book page turns.
  - _Scrolled_: Continuous vertical scroll (ideal for articles).

### Table of Contents & Reading Progress

- Open the **Table of Contents** to jump directly to any chapter.
- The footer bar displays your exact percentage progress and chapter title.
- Reading positions are automatically committed 1.5 seconds after page turns and instantly saved whenever you switch tabs or leave the reader.

### Screen Wake Lock

While an article or book is open, Folio engages the Screen Wake Lock API to prevent your phone or tablet display from going to sleep while you are reading.

---

## 6. Highlights, Notes & Bookmarks

### Highlighting & Adding Notes

1. Select any sentence or paragraph in a book.
2. A contextual floating toolbar appears:
   - **Colors**: Tap **Yellow**, **Green**, **Blue**, or **Pink** to highlight instantly.
   - **Add Note**: Tap the comment icon, type your thoughts, and save.
   - **Copy**: Copy clean text to your system clipboard.
3. _Mobile Haptic Feedback_: On Android, saving a highlight triggers a subtle 10 ms vibration.
4. _Robust Anchoring_: Highlights store the exact quote along with 32 characters of surrounding prefix and suffix text. Your annotations stay perfectly anchored even if you change font sizes or reflow the book.

### Notes Screen & Filtering

Visit the **Notes** tab in the main navigation (`#/notes`):

- View every highlight and annotation across your entire library in one place.
- Filter by document, color, or kind (Highlights vs. Notes).
- Tap any quote card to open the book and jump directly to that exact passage.

### Exporting Notes to Markdown

Click **Export Markdown** on the Notes screen. Folio downloads a formatted file named `folio-notes-YYYYMMDD.md` containing all your quotes, notes, and citations, categorized by book. Perfect for Obsidian, Logseq, or personal archives.

---

## 7. Library Organization & Search

### Collections & Tags

- Organize documents into custom collections (e.g., _To Read_, _Philosophy_, _Work Research_).
- Tag documents with descriptive topics (e.g., _history_, _tech_, _fiction_).
- Use the quick filter pills at the top of your library to browse by collection or tag.

### Document Details & Editing

Tap the `...` menu on any book card to open the **Document Details Sheet**:

- Edit title and author metadata.
- Manage assigned tags and collections.
- View capture date, original format, word count, and file size.
- Delete the document (removes all content files, annotations, reading progress, and search entries).

### Multilingual Full-Text Search (English & Hindi)

Click **Search** (`#/search`) for instant, offline discovery across every word in your books:

- **Non-blocking Web Worker**: Search runs in a background thread so typing never stutters.
- **Devanagari (Hindi) Support**:
  - Accurately searches Hindi and English simultaneously.
  - Crucial linguistic rule: Combining vowel marks (_matras_) and _viramas_ (`\u0900-\u097F`) are preserved rather than stripped.
  - Stemming automatically matches inflected noun forms (e.g. searching for `विद्यार्थी` matches `विद्यार्थियों`).
- **Snippets with Context**: Results show ~80 characters before and after the hit, with matching terms wrapped in `<mark>` highlights.
- **Jump to Hit**: Clicking a search hit opens the book directly to that chapter and position.

---

## 8. Backup & Restore

Folio ensures your library remains safe from browser cache purges and easy to move between devices.

### Exporting a Streaming ZIP

1. Go to **Settings > Library Backup & Recovery**.
2. Click **Export Backup ZIP**.
3. Folio streams your entire library (all books, articles, annotations, progress, and collections) into `folio-backup-YYYYMMDD-HHMM.zip`.
4. _Memory Safe_: Uses streaming compression (`fflate`) so even multi-gigabyte libraries never crash the browser.

### Restoring: Merge vs. Replace

Click **Restore from ZIP** and select a Folio backup archive:

- **Checksum Verification**: Folio inspects the ZIP and verifies every file's SHA-256 hash against `backup.json`. Any corrupted files are reported before proceeding.
- **Merge Mode (Default)**:
  - Adds any documents missing from your local library.
  - Skips duplicates if the content hash already exists.
  - Compares `updatedAt` timestamps to ensure newer notes and progress win.
  - _Never deletes existing documents._
- **Replace Mode**:
  - Wipes current storage and restores the exact archive state.
  - Requires typing `REPLACE` to confirm before proceeding.

### Backup Reminder Banner & Storage Persistence

- **Reminder**: A banner appears on your Library screen if more than 14 days or 20 changes have elapsed without a backup.
- **Eviction Protection**: In **Settings**, tap **Request Persistence** to grant browser storage persistence (`navigator.storage.persist()`), protecting your library from automatic disk cleanups.

---

## 9. Google Drive Mirror & Sync

Folio can mirror your library to your personal Google Drive for multi-device sync without requiring any intermediary servers or accounts.

### How Drive Sync Works (Zero Servers)

- Communicates directly between your browser and Google Drive API.
- Scope: `https://www.googleapis.com/auth/drive.file` only. Folio cannot view, read, or modify any other files on your Drive—only files created by Folio itself.
- All files are stored in a dedicated Drive folder named **Folio Library**.
- Resumable chunked uploads in 5 MB blocks with automatic retry and exponential backoff.
- Access tokens stay in memory only and expire automatically after an hour.
- Version 1 rule: Deletions made on one device are never automatically pushed to Drive, preventing accidental loss.

### Google Cloud Console Setup (3 Minutes)

Because Folio runs with zero servers, you provide your own free Google OAuth Client ID:

1. Visit [Google Cloud Console](https://console.cloud.google.com/) and create a project named **Folio**.
2. Go to **APIs & Services > Library**, search for **Google Drive API**, and click **Enable**.
3. In **APIs & Services > OAuth consent screen**:
   - Choose **External** and click **Create**.
   - Fill in App name (`Folio`) and your email address.
   - Under **Scopes**, add `https://www.googleapis.com/auth/drive.file`.
   - Under **Test users**, add your Google email address.
4. In **APIs & Services > Credentials**:
   - Click **Create Credentials > OAuth client ID**.
   - Application type: **Web application**.
   - Under **Authorized JavaScript origins**, add your app URL:
     - Local development: `http://localhost:5173`
     - Production: `https://your-domain.pages.dev` (no trailing slash).
   - Click **Create** and copy your **Client ID**.

### Connecting & Syncing in Folio

1. Open Folio and go to **Settings > Google Drive Mirror & Sync**.
2. Paste your Client ID and click **Sign In**.
3. Choose your Google account in the Google prompt.
4. Click **Sync Now**.
5. A progress sheet will show real-time progress:
   - Comparing local and remote manifests
   - Uploading new local books
   - Downloading new remote books
   - Reconciling annotations, reading progress, and collections
   - Rebuilding the local search index

### Two-Device Convergence

If you read on a phone and a laptop:

1. Sync on Device A (uploads your progress and notes).
2. Sync on Device B (downloads the updates and merges notes).
3. Both devices now match seamlessly.

---

## 10. Windows Folder Mode (Direct Filesystem)

If you are using Folio on a Windows desktop or laptop:

1. Open **Settings > Windows Folder Mode**.
2. Click **Select Local Library Folder**.
3. Pick any folder on your PC (for example, a folder inside `Google Drive for desktop` or `OneDrive`).
4. Folio now stores all books and data directly as real files in that directory using the File System Access API.
5. Windows handles cloud background synchronization automatically with **zero API requests** or OAuth setup needed!

---

## 11. Keyboard Shortcuts Cheat Sheet

| Key Shortcut                          | Screen  | Function                                 |
| ------------------------------------- | ------- | ---------------------------------------- |
| `Right Arrow` / `Page Down` / `Space` | Reader  | Next Page                                |
| `Left Arrow` / `Page Up`              | Reader  | Previous Page                            |
| `T`                                   | Reader  | Toggle Table of Contents                 |
| `/`                                   | Reader  | Focus in-book search                     |
| `Escape`                              | Reader  | Dismiss selection toolbar / Close sheets |
| `Ctrl+F`                              | Global  | Jump to Library Full-Text Search         |
| `Alt+Shift+C`                         | Browser | Clip current article to Folio            |
