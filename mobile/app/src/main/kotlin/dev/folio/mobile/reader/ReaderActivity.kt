package dev.folio.mobile.reader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.folio.mobile.R
import dev.folio.mobile.core.annotation.SelectionBridge
import dev.folio.mobile.core.annotation.AnnotationLocators
import dev.folio.mobile.core.library.CoverLoader
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.model.HighlightColor
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.ProgressPace
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.reader.PageTurnKeys
import dev.folio.mobile.core.reader.ProgressBridge
import dev.folio.mobile.core.reader.ReaderPrefsMapper
import dev.folio.mobile.core.reader.ReaderResume
import dev.folio.mobile.core.reader.ReaderSearch
import dev.folio.mobile.core.reader.ReadingPaceStats
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.store.DocPrefsStore
import dev.folio.mobile.core.store.PrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.LocatorService
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser
import java.io.File
import kotlin.math.abs

/**
 * Process-lifetime IO scope for writes that must outlive the reader Activity
 * (the onDestroy progress flush) — an app without a Scene-level application
 * scope keeps this singleton instead of blocking the main thread.
 */
internal val flushScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * R1 core reader: opens the document's `reading/book.epub` through Readium's
 * streamer, attaches an [EpubNavigatorFragment], and mirrors reading progression
 * into the schema-1 Progress store (locator → CFI via [ProgressBridge]).
 *
 * Prefs: [ReadingPrefs] are loaded from user/settings.json via [PrefsStore],
 * mapped to Readium by [ReaderPrefsMapper]; chrome edits re-submit preferences
 * live ([EpubNavigatorFragment.submitPreferences]) and persist back to the same
 * file — byte-compatible with the schema-1 backup contract.
 *
 * Restore: jumps beat saved progress (library-search spine order, then the
 * notes/bookmarks location extra); with saved progress, the CFI's spine
 * component picks the CHAPTER and the within-chapter offset is recomputed
 * from the saved total percent over PositionsService (EPUB-010 partial —
 * exact text-anchor CFI restore is gated on a Readium upgrade, EPUB-012).
 */
class ReaderActivity : FragmentActivity() {

    companion object {
        const val EXTRA_DOC_ID = "dev.folio.mobile.READER_DOC_ID"
        /** 1-based spine order for a search-hit jump; 0 = resume normally. */
        const val EXTRA_TARGET_ORDER = "dev.folio.mobile.READER_TARGET_ORDER"
        /** Saved location string (epubcfi or href fallback) to jump to on open. */
        const val EXTRA_TARGET_LOCATION = "dev.folio.mobile.READER_TARGET_LOCATION"
        /**
         * Text-quote context for the notes-tab jump: with a non-blank
         * highlight + context the reader issues a second `go()` on a
         * text-quote locator once the initial chapter renders, landing on the
         * highlighted text itself (the same loadLocator text-highlight
         * priority the in-book search-hit jumps use, EPUB-015).
         */
        const val EXTRA_TEXT_PREFIX = "dev.folio.mobile.READER_TEXT_PREFIX"
        const val EXTRA_TEXT_HIGHLIGHT = "dev.folio.mobile.READER_TEXT_HIGHLIGHT"
        const val EXTRA_TEXT_SUFFIX = "dev.folio.mobile.READER_TEXT_SUFFIX"
        private const val NAV_TAG = "EpubNavigatorFragment"

        /** Custom floating selection-bar item ids (PWA parity). */
        private const val SEL_ITEM_HIGHLIGHT = 1
        private const val SEL_ITEM_COPY = 2

        /** Reader splash cover decode size (~200dp box at ~3x). */
        private const val SPLASH_COVER_W = 660
        private const val SPLASH_COVER_H = 990

        fun createIntent(context: Context, docId: String): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_DOC_ID, docId)

        /** Search-hit path (M4): open the chapter by spine order. */
        fun createIntent(context: Context, docId: String, targetOrder: Int): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_DOC_ID, docId)
                .putExtra(EXTRA_TARGET_ORDER, targetOrder)

        /**
         * Notes/bookmarks jump (EPUB-018 tie-in): open at the annotation's
         * saved location string (Progress.location format).
         */
        fun createIntent(context: Context, docId: String, targetLocation: String): Intent =
            createIntent(context, docId, targetLocation, null, null, null)

        /**
         * Notes-tab jump with the annotation's quote+context: the quote snaps
         * the jump to the exact highlighted text (see [EXTRA_TEXT_HIGHLIGHT]).
         * Null/blank quote → chapter-accurate only (bookmark behavior).
         */
        fun createIntent(
            context: Context,
            docId: String,
            targetLocation: String,
            textPrefix: String?,
            textHighlight: String?,
            textSuffix: String?
        ): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_DOC_ID, docId)
                .putExtra(EXTRA_TARGET_LOCATION, targetLocation)
                .putExtra(EXTRA_TEXT_PREFIX, textPrefix)
                .putExtra(EXTRA_TEXT_HIGHLIGHT, textHighlight)
                .putExtra(EXTRA_TEXT_SUFFIX, textSuffix)
    }

    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null
    private var saveJob: Job? = null
    private var navigatorRef: EpubNavigatorFragment? = null
    private var publicationRef: Publication? = null
    private var highlightManager: HighlightManager? = null
    private var currentDocId: String = ""

    private lateinit var prefsStore: PrefsStore

    /** Per-book typography overrides (Batch 2); null get() = global prefs. */
    private lateinit var docPrefsStore: DocPrefsStore

    /** Reading prefs live state (chrome edits, navigator consumes). */
    private val prefsState = mutableStateOf(ReadingPrefs())

    /** True when the current doc has its own override (chrome reset chip). */
    private val hasDocOverride = mutableStateOf(false)

    /** Chrome overlay visibility — toggled by tapping the page's middle third. */
    private val chromeVisible = mutableStateOf(true)

    /**
     * Bottom pillar is auto-hide (it otherwise covers the page's last lines):
     * follows the chrome toggle, and is pinned up while the scrubber is in
     * use, dropping a short grace period after the drag ends.
     */
    private val pillarScrubbing = mutableStateOf(false)
    private var pillarHideJob: Job? = null

    /** Bottom pillar: current chapter title + total percent. */
    private val pillarTitle = mutableStateOf("")
    private val pillarPercent = mutableStateOf(0.0)

    /** Time-to-finish estimate shown in the pillar (null = not enough data). */
    private val timeLeftText = mutableStateOf<String?>(null)

    /** Accumulated reading pace (PWA stats.ts port) — persisted as Progress.pace. */
    private var paceState: ProgressPace? = null

    /** Last relocation for pace deltas (percent on the 0..100 scale + elapsed ms). */
    private var prevRelocatePercent: Double? = null
    private var prevRelocateElapsedMs: Long? = null

    /** Quote-card attribution: the document's author from its meta.json. */
    private var docAuthor: String? = null

    /** Annotation context menu target (R3) — null when closed. */
    private val annotationMenu = mutableStateOf<Annotation?>(null)

    /** Reader splash: the document cover shown while the navigator mounts. */
    private val splashCover = mutableStateOf<ImageBitmap?>(null)

    /** Cleared once the navigator emits its first locator (or after a timeout). */
    private val readerReady = mutableStateOf(false)

    /** TOC sheet visibility + flattened entries (title, depth, locator). */
    private val tocVisible = mutableStateOf(false)
    private val tocEntries = mutableStateOf<List<TocEntry>>(emptyList())

    /** Captured selection for the floating selection toolbar. */
    private val selectionToolbarVisible = mutableStateOf(false)
    private val selectionAnnotation = mutableStateOf<Annotation?>(null)
    private val selectionLocator = mutableStateOf<Locator?>(null)

    // ---- In-book search state (EPUB-015) ----

    /** Search sheet visibility + live query text. */
    private val searchVisible = mutableStateOf(false)
    private val searchQuery = mutableStateOf("")

    /** Streaming results (painted batch-by-batch as the iterator yields). */
    private val searchHits = mutableStateOf<List<ReaderSearch.Hit>>(emptyList())

    /** Sheet footer state: running spinner / terminal message / ran-at-least-once. */
    private val searchBusy = mutableStateOf(false)
    private val searchError = mutableStateOf<String?>(null)
    private val searchCompleted = mutableStateOf(false)

    /** Locators parallel to [searchHits] — tap index → navigator.go. */
    private var searchLocators: List<Locator> = emptyList()

    /**
     * Notes-tab exact-place jump (EPUB-018): held between the restore and the
     * navigator's first locator emission, then fired as a text-quote anchor
     * `go()` (see [exactTextTarget]).
     */
    private var pendingExactJump: Locator? = null
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            // The OS killed the process mid-session: the navigator can only be
            // rebuilt from a live Publication, so exit gracefully (per the
            // Readium guide's dummy-factory recipe).
            supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        }
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val docId = intent?.getStringExtra(EXTRA_DOC_ID) ?: run {
            finish()
            return
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_reader)
        // Keep the screen awake while reading (PWA spec §7).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefsStore = PrefsStore(AppFileStore(filesDir))
        docPrefsStore = DocPrefsStore(AppFileStore(filesDir))
        currentDocId = docId

        // Edge-to-edge: Readium only pads for display cutouts, so handle the
        // system bars (status + nav/gesture) at this container level instead.
        val navContainer = findViewById<android.view.View>(R.id.navigator_container)
        ViewCompat.setOnApplyWindowInsetsListener(navContainer) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Chrome overlay (Compose hosting on a FragmentActivity via ComposeView)
        val chrome = findViewById<ComposeView>(R.id.reader_chrome)
        chrome.setContent {
            Box {
                // Brightness scrim sits UNDER the chrome so controls stay crisp;
                // no pointerInput, touches fall through to the navigator.
                BrightnessOverlay(prefsState.value.brightness)
                ReaderChrome(
                    visible = chromeVisible.value,
                    prefs = prefsState.value,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onPrefsChange = { updated -> onPrefsChanged(updated) },
                    onHighlight = { onHighlightTapped() },
                    onBookmark = { onBookmarkTapped() },
                    onToc = { tocVisible.value = true },
                    onSearch = { searchVisible.value = true },
                    onScrub = { p ->
                        // Pin the pillar for the whole drag + a short settle.
                        pillarHideJob?.cancel()
                        pillarScrubbing.value = true
                        pillarPercent.value = p
                    },
                    onScrubFinished = { p ->
                        scrubTo(p)
                        pillarHideJob = lifecycleScope.launch {
                            delay(1200)
                            pillarScrubbing.value = false
                        }
                    },
                    pillarVisible = chromeVisible.value || pillarScrubbing.value,
                    pillarTitle = pillarTitle.value,
                    pillarPercent = pillarPercent.value,
                    timeLeft = timeLeftText.value,
                    systemDark = isSystemDark(),
                    perBookOverride = hasDocOverride.value,
                    onResetPerBook = { onResetPerBookPrefs() }
                )
                if (tocVisible.value) {
                    TocSheet(
                        entries = tocEntries.value,
                        onEntry = { onTocEntryTapped(it) },
                        onDismiss = { tocVisible.value = false }
                    )
                }
                if (searchVisible.value) {
                    SearchSheet(
                        query = searchQuery.value,
                        busy = searchBusy.value,
                        error = searchError.value,
                        completed = searchCompleted.value,
                        hits = searchHits.value,
                        onQueryChange = { searchQuery.value = it },
                        onSubmit = { onSearchSubmit() },
                        onHit = { index -> onSearchResultTapped(index) },
                        onDismiss = { onSearchClose() }
                    )
                }
                if (selectionToolbarVisible.value) {
                    SelectionToolbarSheet(
                        annotation = selectionAnnotation.value,
                        onColor = { color -> saveSelectionHighlight(color) },
                        onNote = { note -> saveSelectionHighlight(HighlightColor.Yellow, note, openMenuAfter = true) },
                        onCopy = { copySelectionToClipboard() },
                        onDismiss = {
                            navigatorRef?.clearSelection()
                            selectionToolbarVisible.value = false
                            selectionAnnotation.value = null
                            selectionLocator.value = null
                        }
                    )
                }
                // Splash sits ABOVE the chrome so loading is a clean takeover;
                // it fades out on the navigator's first locator.
                AnimatedVisibility(
                    visible = !readerReady.value,
                    exit = fadeOut(animationSpec = tween(450))
                ) {
                    ReaderSplash(splashCover.value)
                }
                annotationMenu.value?.let { annotation ->
                    AnnotationMenuSheet(
                        annotation = annotation,
                        onColor = { color -> onAnnotationColor(color) },
                        onSaveNote = { note -> onAnnotationNote(note) },
                        onDelete = { onAnnotationDelete() },
                        onShare = { onAnnotationShare() },
                        onDismiss = { annotationMenu.value = null }
                    )
                }
            }
        }

        loadScope.launch {
            // Prefs FIRST — the navigator gets the mapped values at attach time.
            // Per-book override (Batch 2) wins over the global defaults.
            val global = prefsStore.loadReadingPrefs()
            val docOverride = docPrefsStore.get(docId)
            prefsState.value = docOverride ?: global
            hasDocOverride.value = docOverride != null
            withContext(Dispatchers.Main) {
                applySystemBarAppearance(prefsState.value)
            }
            loadAndAttach(docId)
        }
    }

    override fun onDestroy() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressJob?.cancel()
        // Flush the last debounced progress write before the scope goes away —
        // fire-and-forget on a process-lifetime IO scope (audit EPUB-006):
        // no main-thread runBlocking, and LibraryManager.saveProgress is an
        // idempotent, mutexed map put so a lost activity is never lost data.
        saveJob?.cancel()
        lastSavedProgress?.let { p ->
            val appContext = applicationContext
            flushScope.launch {
                runCatching {
                    LibraryManager(AppFileStore(appContext.filesDir)).saveProgress(p)
                }
            }
        }
        loadScope.cancel()
        super.onDestroy()
    }

    /**
     * Chrome → navigator → persistence. Per-book semantics (Batch 2): while a
     * document is open, chrome edits update THAT document's override only —
     * the global library-wide defaults (Settings screen) stay untouched.
     */
    private fun onPrefsChanged(updated: ReadingPrefs) {
        applyPrefs(updated)
        hasDocOverride.value = true
        loadScope.launch(Dispatchers.IO) {
            docPrefsStore.set(currentDocId, updated)
        }
    }

    /** Applies prefs to state/system bars/navigator without persisting. */
    private fun applyPrefs(updated: ReadingPrefs) {
        prefsState.value = updated
        applySystemBarAppearance(updated)
        findViewById<android.view.View>(R.id.navigator_container)
            ?.setBackgroundColor(ReaderPrefsMapper.mapPrefs(updated, isSystemDark()).backgroundArgb)
        navigatorRef?.submitPreferences(
            ReaderPrefsAndroid.toEpubInitialPreferences(
                ReaderPrefsMapper.mapPrefs(updated, isSystemDark())
            )
        )
    }

    /** "reset": drop the doc override and fall back to the global defaults. */
    private fun onResetPerBookPrefs() {
        loadScope.launch(Dispatchers.IO) {
            docPrefsStore.set(currentDocId, null)
            val global = prefsStore.loadReadingPrefs()
            withContext(Dispatchers.Main) {
                hasDocOverride.value = false
                applyPrefs(global)
            }
        }
    }

    private suspend fun loadAndAttach(docId: String) {
        val store = AppFileStore(filesDir)
        val library = LibraryManager(store)

        val meta = library.listDocuments().firstOrNull { it.id == docId }
        val readingRel = meta?.files?.reading
        if (meta == null || readingRel == null) {
            withContext(Dispatchers.Main) { finish() }
            return
        }
        docAuthor = meta.author
        val file = store.resolve("library/docs/${meta.slug}/$readingRel")

        // Splash cover: display-only, decoded before the navigator mounts so the
        // reading screen opens with something recognizable instead of a blank
        // WebView (skipped when the import found no cover).
        meta.files?.cover?.takeIf { it.isNotBlank() }?.let { cover ->
            CoverLoader.decode(
                store,
                "library/docs/${meta.slug}/$cover",
                SPLASH_COVER_W,
                SPLASH_COVER_H
            )?.let { bitmap -> splashCover.value = bitmap.asImageBitmap() }
        }

        val publication = openPublication(file)
        if (publication == null) {
            withContext(Dispatchers.Main) { finish() }
            return
        }

        // Restore: explicit jumps (library-search spine, notes/bookmark
        // location) beat saved progress; saved CFI → chapter from its spine
        // component with the within-chapter offset recomputed from the saved
        // total percent (EPUB-010 partial); saved percent → deep locator;
        // nothing → first spine item.
        val savedProgress: Progress? = library.getProgress(docId)
        // Restore accumulated pace from previous sessions (optional field).
        paceState = savedProgress?.pace
        var initialLocator: Locator? = null
        val targetOrder = intent?.getIntExtra(EXTRA_TARGET_ORDER, 0) ?: 0
        val targetSpine = targetOrder - 1
        val targetLocation = intent?.getStringExtra(EXTRA_TARGET_LOCATION)
        if (targetOrder > 0 && publication.readingOrder.getOrNull(targetSpine) != null) {
            publication.readingOrder[targetSpine].let { link ->
                initialLocator = chapterLocator(link)
            }
        } else {
            if (!targetLocation.isNullOrBlank()) {
                // Notes/bookmarks jump (EPUB-018): land chapter-accurate at the
                // saved location's resource first, then — when the annotation
                // carries quote+context (highlights/notes always do) — the
                // collector fires the exact text-quote jump once this chapter
                // has rendered (see pendingExactJump / exactTextTarget).
                initialLocator = locationTarget(publication, targetLocation)
                pendingExactJump = exactTextTarget(
                    publication,
                    targetLocation,
                    intent?.getStringExtra(EXTRA_TEXT_PREFIX),
                    intent?.getStringExtra(EXTRA_TEXT_HIGHLIGHT),
                    intent?.getStringExtra(EXTRA_TEXT_SUFFIX)
                )
            }
            if (initialLocator == null && savedProgress != null &&
                savedProgress.location.startsWith(ProgressBridge.CFI_PREFIX)
            ) {
                val spineIdx = ProgressBridge.cfiSpineIndex(savedProgress.location)
                if (spineIdx != null) {
                    publication.readingOrder.getOrNull(spineIdx)?.let { link ->
                        // EPUB-010 partial: refines chapter-start into an
                        // in-chapter fraction from the saved percent.
                        initialLocator = refinedChapterLocator(
                            publication, savedProgress.percent, spineIdx, link
                        )
                    }
                }
            }
            if (initialLocator == null && savedProgress != null) {
                // Progress.percent is the PWA 0..100 scale; Readium wants 0..1.
                // locateProgression computes over the publication's Position
                // services (DefaultLocatorService → PositionsService — no
                // navigator/WebView involvement, verified against Readium 3.0.0
                // bytecode); we stay on Main for coroutine etiquette and because
                // the navigator attach that follows is main-bound.
                withContext(Dispatchers.Main) {
                    publication.findService(LocatorService::class)
                        ?.locateProgression(savedProgress.percent / 100.0)
                }?.let { initialLocator = it }
            }
            if (initialLocator == null) {
                publication.readingOrder.firstOrNull()?.let { link ->
                    initialLocator = chapterLocator(link)
                }
            }
        }
        val initial = initialLocator ?: run {
            withContext(Dispatchers.Main) { finish() }
            return
        }

        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            attachNavigator(publication, docId, initial)
        }
    }

    private suspend fun openPublication(file: File): Publication? {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(
            contentResolver = contentResolver,
            httpClient = httpClient
        )
        val asset = assetRetriever.retrieve(file).getOrNull() ?: return null
        // EPUB-only v1: the pdfium adapter (PDF navigator) is a later R-phase;
        // TXT/HTML reading editions are EpubBuilder-built EPUBs, PDF later.
        val parser = EpubParser()
        val opener = PublicationOpener(publicationParser = parser)
        return opener.open(asset, allowUserInteraction = true).getOrNull()
    }

    private fun attachNavigator(publication: Publication, docId: String, initialLocator: Locator) {
        val currentPrefs = prefsState.value
        val mappedPrefs = ReaderPrefsMapper.mapPrefs(currentPrefs, isSystemDark())
        applySystemBarAppearance(currentPrefs)
        findViewById<android.view.View>(R.id.navigator_container)
            ?.setBackgroundColor(mappedPrefs.backgroundArgb)
        val factory = EpubNavigatorFactory(
            publication = publication,
            configuration = EpubNavigatorFactory.Configuration(
                defaults = ReaderPrefsAndroid.toEpubDefaults(mappedPrefs)
            )
        )
        val factoryFragment = factory.createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = ReaderPrefsAndroid.toEpubInitialPreferences(mappedPrefs),
            listener = readerListener(),
            configuration = EpubNavigatorFragment.Configuration(
                shouldApplyInsetsPadding = false,
                // PWA parity: long-press gets the app's OWN selection bar instead
                // of the system Copy/Share/Read-aloud menu. Selection state and
                // `currentSelection()` are unaffected. Items route into the
                // same handlers as the chrome brush button (selection sheet)
                // and clipboard copy.
                selectionActionModeCallback = object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(
                        mode: android.view.ActionMode,
                        menu: android.view.Menu
                    ): Boolean {
                        menu.add(0, SEL_ITEM_HIGHLIGHT, 0, "Highlight")
                            ?.setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                        menu.add(0, SEL_ITEM_COPY, 1, "Copy")
                            ?.setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                        return true
                    }

                    override fun onPrepareActionMode(
                        mode: android.view.ActionMode,
                        menu: android.view.Menu
                    ): Boolean = false

                    override fun onActionItemClicked(
                        mode: android.view.ActionMode,
                        item: android.view.MenuItem
                    ): Boolean {
                        when (item.itemId) {
                            SEL_ITEM_HIGHLIGHT -> onHighlightTapped()
                            SEL_ITEM_COPY -> onSelectionCopyTapped()
                        }
                        mode.finish()
                        return true
                    }

                    override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                }
            )
        )
        supportFragmentManager.fragmentFactory = factoryFragment
        supportFragmentManager.commitNow {
            add(R.id.navigator_container, EpubNavigatorFragment::class.java, null, NAV_TAG)
        }
        val navigator = supportFragmentManager.findFragmentByTag(NAV_TAG)
            as? EpubNavigatorFragment ?: return
        navigatorRef = navigator
        publicationRef = publication
        // Flatten the publication's table of contents (nested → flat with depth).
        tocEntries.value = flattenToc(publication.tableOfContents, 0)
        navigator.addInputListener(chromeTapListener())
        val manager = HighlightManager(navigator, publication, docId).also { it.installListener() }
        highlightManager = manager
        manager.onDecorationActivated = { event ->
            runOnUiThread {
                annotationMenu.value = manager.annotationById(event.decoration.id)
            }
        }
        observeProgress(docId, navigator)
        // Safety net: an unexpectedly silent navigator (render failure) must
        // never trap the user on the splash — chrome/tap still work above it.
        loadScope.launch {
            delay(8000)
            readerReady.value = true
        }
        // Hydrate persisted annotations (R3): rebuild Locators from the saved
        // schema-1 annotations and paint them right after the navigator mounts.
        lifecycleScope.launch {
            val annotations = withContext(Dispatchers.IO) {
                LibraryManager(AppFileStore(filesDir)).getAnnotations(docId)
            }
            manager.boot(annotations)
        }
    }

    // ---- Annotation context-menu actions (R3) ----

    private fun onAnnotationColor(color: HighlightColor) {
        applyAnnotationEdit { it.copy(color = color) }
    }

    private fun onAnnotationNote(note: String) {
        applyAnnotationEdit { it.copy(note = note.ifBlank { null }) }
    }

    private fun onAnnotationDelete() {
        val annotation = annotationMenu.value ?: return
        annotationMenu.value = null
        highlightManager ?: return
        loadScope.launch {
            withContext(Dispatchers.IO) {
                LibraryManager(AppFileStore(filesDir)).removeAnnotation(annotation.id)
            }
            // Decoration routing drives the WebView — main only.
            withContext(Dispatchers.Main) {
                highlightManager?.remove(annotation.id)
            }
        }
    }

    /** Placeholder kept for API stability — the share path is implemented below. */
    private fun onAnnotationShare() {
        val annotation = annotationMenu.value ?: return
        shareQuoteCard(annotation)
    }

    /** Menu → updatedAt bump → persist → re-apply decorations. */
    private fun applyAnnotationEdit(
        transform: (Annotation) -> Annotation
    ) {
        val annotation = annotationMenu.value ?: return
        val updated = transform(annotation).copy(
            updatedAt = java.time.Instant.now().toString()
        )
        annotationMenu.value = updated
        loadScope.launch {
            withContext(Dispatchers.IO) {
                LibraryManager(AppFileStore(filesDir)).saveAnnotation(updated)
            }
            // Decoration routing drives the WebView — main only.
            withContext(Dispatchers.Main) {
                highlightManager?.update(updated)
            }
        }
    }

    private fun applySystemBarAppearance(prefs: ReadingPrefs) {
        val dark = when (prefs.theme) {
            "dark", "true-black" -> true
            "system" -> isSystemDark()
            else -> false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    /** "system" theme → current OS dark-mode setting. */
    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun shareQuoteCard(annotation: Annotation) {
        loadScope.launch {
            val uri = QuoteCard.share(
                context = this@ReaderActivity,
                annotation = annotation,
                bookTitle = pillarTitle.value.ifBlank { null },
                author = docAuthor
            ) ?: return@launch
            withContext(Dispatchers.Main) {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(
                        android.content.Intent.EXTRA_TEXT,
                        listOfNotNull(annotation.quote, annotation.note)
                            .joinToString("\n\n")
                    )
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(
                    android.content.Intent.createChooser(send, "Share quote")
                )
            }
        }
    }

    /**
     * R3 groundwork: capture the current selection as a schema-1 highlight.
     * `currentSelection()` is a suspend read (no flow in 3.0.0), so the chrome
     * button fetches it on tap; a null result means no active selection.
     */
    private fun onHighlightTapped() {
        val navigator = navigatorRef ?: return
        val docId = currentDocId
        // currentSelection() drives the WebView — it must run on the MAIN
        // thread (a Dispatchers.IO resume here crashes with
        // "A WebView method was called on the wrong thread").
        lifecycleScope.launch(Dispatchers.Main) {
            val selection = navigator.currentSelection()
            if (selection == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@ReaderActivity, "Select text first", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            val now = java.time.Instant.now().toString()
            val annotation = SelectionBridge.annotationFromSelection(
                docId = docId,
                locator = selection.locator,
                id = java.util.UUID.randomUUID().toString(),
                updatedAt = now
            )
            selectionAnnotation.value = annotation
            selectionLocator.value = selection.locator
            withContext(Dispatchers.Main) {
                selectionToolbarVisible.value = true
            }
        }
    }

    /** Saves the captured selection as a highlight with the chosen color and optional note. */
    private fun saveSelectionHighlight(
        color: HighlightColor,
        note: String? = null,
        openMenuAfter: Boolean = false
    ) {
        val annotation = selectionAnnotation.value ?: return
        val locator = selectionLocator.value ?: return
        val manager = highlightManager ?: return
        if (manager.annotationById(annotation.id) != null) return
        val updated = annotation.copy(color = color, note = note?.ifBlank { null })
        loadScope.launch {
            withContext(Dispatchers.IO) {
                LibraryManager(AppFileStore(filesDir)).saveAnnotation(updated)
            }
            withContext(Dispatchers.Main) {
                manager.add(updated, locator)
                navigatorRef?.clearSelection()
                selectionToolbarVisible.value = false
                selectionAnnotation.value = null
                selectionLocator.value = null
                if (openMenuAfter) annotationMenu.value = updated
            }
        }
    }

    /** Copies the captured selection's quote to the clipboard. */
    private fun copySelectionToClipboard() {
        val annotation = selectionAnnotation.value ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Folio", annotation.quote))
        selectionToolbarVisible.value = false
        selectionAnnotation.value = null
        selectionLocator.value = null
        android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Floating selection-bar "Copy": captures the LIVE selection (main thread —
     * WebView access) and puts its quote on the clipboard, no sheet involved.
     */
    private fun onSelectionCopyTapped() {
        val navigator = navigatorRef ?: return
        val docId = currentDocId
        lifecycleScope.launch(Dispatchers.Main) {
            val selection = navigator.currentSelection() ?: return@launch
            val annotation = SelectionBridge.annotationFromSelection(
                docId = docId,
                locator = selection.locator,
                id = java.util.UUID.randomUUID().toString(),
                updatedAt = java.time.Instant.now().toString()
            )
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Folio", annotation.quote))
            android.widget.Toast.makeText(this@ReaderActivity, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Tapping the page's middle third toggles the chrome (Readium's own page
     * turns live in the left/right thirds). Consumes the middle tap so the
     * navigator doesn't also treat it as a page turn.
     */
    private fun chromeTapListener() = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            val width = resources.displayMetrics.widthPixels
            val x = event.point.x
            val inMiddleThird = x > width / 3f && x < width * 2f / 3f
            if (inMiddleThird) {
                chromeVisible.value = !chromeVisible.value
            }
            return inMiddleThird
        }

        override fun onDrag(event: DragEvent): Boolean = false
        override fun onKey(event: KeyEvent): Boolean = false
    }

    /** Link.href is an Href object; resolve against root for a plain-Url Locator. */
    private fun chapterLocator(link: org.readium.r2.shared.publication.Link): Locator =
        Locator(
            href = link.href.resolve(Url("/")),
            mediaType = MediaType.XHTML
        )

    /**
     * Notes/bookmark jump target (EPUB-018 tie-in): resolves a saved
     * Progress-format location string to a chapter-accurate locator. CFI
     * locations decompose through [ProgressBridge.cfiSpineIndex]; the
     * TXT-era href fallback matches against the reading order. Null → the
     * caller keeps its normal restore.
     */
    private fun locationTarget(publication: Publication, target: String): Locator? {
        if (target.startsWith(ProgressBridge.CFI_PREFIX)) {
            val spineIdx = ProgressBridge.cfiSpineIndex(target) ?: return null
            return publication.readingOrder.getOrNull(spineIdx)?.let { chapterLocator(it) }
        }
        val href = target.substringBefore('#').trim()
        if (href.isBlank()) return null
        return publication.readingOrder
            .lastOrNull { link -> link.href.toString().endsWith(href) }
            ?.let { chapterLocator(it) }
    }

    /**
     * Text-quote locator for the notes-tab exact-place jump: same shape as the
     * decoration locators HighlightManager boots persisted highlights with
     * (spine anchored via AnnotationLocators, quote+context carried in the
     * Locator's Text) — i.e. the
     * same `text.highlight` anchor the in-book search-hit jumps land on.
     * Null (blank quote, unresolvable location) → the caller keeps the
     * chapter-start landing — the pre-fix behavior.
     */
    private fun exactTextTarget(
        publication: Publication,
        target: String,
        prefix: String?,
        highlight: String?,
        suffix: String?
    ): Locator? {
        if (highlight.isNullOrBlank()) return null
        val hrefIdx = publication.readingOrder.map { it.url().toString() }
        val spineIdx = AnnotationLocators.anchorIndex(hrefIdx, target) ?: return null
        val link = publication.readingOrder.getOrNull(spineIdx) ?: return null
        return Locator(
            href = link.url(),
            mediaType = link.mediaType ?: MediaType.XHTML,
            text = Locator.Text(
                before = prefix?.takeIf { it.isNotBlank() },
                highlight = highlight,
                after = suffix?.takeIf { it.isNotBlank() }
            )
        )
    }

    /**
     * EPUB-010 partial: turns the chapter-start locator into an in-chapter
     * progression. The saved CFI carries no resolvable text anchor (Readium
     * 3.0.0 has no CFI parser, and the reflowable scripts contain no
     * epubcfi handling — verified via the AAR), but the reflowable page's
     * loadLocator honors `locations.progression`. We recompute that offset
     * from the saved total percent minus the chapter's total-progression
     * span (PositionsService — the same service locateProgression uses;
     * left on Main per the coroutine etiquette noted at open).
     *
     * Falls back to the plain chapter-start locator whenever the positions
     * span is unusable (no positions service, empty chapter, degenerate
     * span, or the saved percent doesn't fall inside the chapter).
     */
    private suspend fun refinedChapterLocator(
        publication: Publication,
        savedPercent: Double,
        spineIndex: Int,
        link: Link
    ): Locator = withContext(Dispatchers.Main) {
        val spans = runCatching {
            publication.findService(org.readium.r2.shared.publication.services.PositionsService::class)
                ?.positionsByReadingOrder()
        }.getOrNull()
        val start = spans?.getOrNull(spineIndex)
            ?.firstOrNull()
            ?.locations?.totalProgression
        if (start == null) return@withContext chapterLocator(link)
        val nextStart = spans.orEmpty()
            .drop(spineIndex + 1)
            .firstNotNullOfOrNull { later -> later.firstOrNull()?.locations?.totalProgression }
            ?: 1.0
        val fraction = ReaderResume.inChapterFraction(
            percentTotal = savedPercent / 100.0,
            startTotal = start,
            nextStartTotal = nextStart
        ) ?: return@withContext chapterLocator(link)
        Locator(
            href = link.href.resolve(Url("/")),
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(progression = fraction)
        )
    }

    /** Flattens a (possibly nested) TOC into a depth-annotated list. */
    private fun flattenToc(links: List<Link>, depth: Int): List<TocEntry> {
        val out = mutableListOf<TocEntry>()
        for (link in links) {
            val title = link.title?.takeIf { it.isNotBlank() } ?: link.href.toString()
            out.add(TocEntry(title, depth, link))
            if (link.children.isNotEmpty()) {
                out.addAll(flattenToc(link.children, depth + 1))
            }
        }
        return out
    }

    /** Jumps the navigator to a TOC entry. */
    private fun onTocEntryTapped(entry: TocEntry) {
        val navigator = navigatorRef ?: return
        val publication = publicationRef ?: return
        tocVisible.value = false
        loadScope.launch {
            val locator = chapterLocator(entry.link)
            withContext(Dispatchers.Main) {
                navigator.go(locator, false)
            }
        }
    }

    /** Adds a bookmark at the current reading position. */
    private fun onBookmarkTapped() {
        val navigator = navigatorRef ?: return
        val docId = currentDocId
        loadScope.launch {
            val selection = navigator.currentLocator.value
            val now = java.time.Instant.now().toString()
            val annotation = Annotation(
                id = java.util.UUID.randomUUID().toString(),
                docId = docId,
                quote = "",
                prefix = "",
                suffix = "",
                location = ProgressBridge.buildProgress(docId, selection, now).location,
                kind = AnnotationKind.Bookmark,
                createdAt = now,
                updatedAt = now
            )
            withContext(Dispatchers.IO) {
                LibraryManager(AppFileStore(filesDir)).saveAnnotation(annotation)
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    this@ReaderActivity, "Bookmark added", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---- In-book search (EPUB-015) ----

    /**
     * Runs the PWA-style full-text search through the publication's
     * Readium `SearchService` (`StringSearchService`, auto-registered by the
     * parser). The iterator streams per-resource batches, so the sheet repaints
     * while long books are still walking. Iteration over publication
     * resources is plain IO (no WebView), so this runs on IO; hit jumps hop to
     * Main like every other navigator call.
     */
    private fun onSearchSubmit() {
        val publication = publicationRef ?: return
        val query = searchQuery.value.trim()
        if (!ReaderSearch.isRunnableQuery(query)) return
        searchJob?.cancel()
        searchHits.value = emptyList()
        searchLocators = emptyList()
        searchError.value = null
        searchCompleted.value = false
        searchBusy.value = true
        searchJob = loadScope.launch(Dispatchers.IO) {
            val iterator = try {
                publication.findService(SearchService::class)
                    ?.search(query, SearchService.Options())
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                null // "search failed" surfaces post-run; never crash the scope.
            }
            if (iterator == null) {
                searchError.value = "Search failed."
                searchBusy.value = false
                searchCompleted.value = true
                return@launch
            }
            val hits = mutableListOf<ReaderSearch.Hit>()
            val locators = mutableListOf<Locator>()
            val outcome = iterator.forEach { collection ->
                for (locator in collection.locators) {
                    if (hits.size >= ReaderSearch.MAX_HITS) break
                    hits.add(ReaderSearch.hitFromLocator(locator))
                    locators.add(locator)
                }
                // Paint after each resource batch (streaming results).
                searchHits.value = hits.toList()
                searchLocators = locators.toList()
            }
            searchBusy.value = false
            searchCompleted.value = true
            searchError.value = when {
                hits.isNotEmpty() -> null // partial results are still useful
                outcome.isFailure -> "Search failed."
                else -> null // a clean zero-result walk
            }
            iterator.close()
        }
    }

    /** Closes the sheet and stops any in-flight walk. */
    private fun onSearchClose() {
        searchVisible.value = false
        searchJob?.cancel()
        searchJob = null
        searchBusy.value = false
    }

    /**
     * Jump at the hit's text anchor. `go()` reuses the reflowable page's
     * `loadLocator` priority (text.highlight → htmlId → progression), so this
     * lands on the matched text, not the chapter head.
     */
    private fun onSearchResultTapped(index: Int) {
        val locator = searchLocators.getOrNull(index) ?: return
        val navigator = navigatorRef ?: return
        searchJob?.cancel()
        searchVisible.value = false
        lifecycleScope.launch(Dispatchers.Main) {
            navigator.go(locator, false)
        }
    }

    /** Navigator wires external links to a browser. */
    private fun readerListener() = object : EpubNavigatorFragment.Listener {
        override fun onExternalLinkActivated(absoluteUrl: AbsoluteUrl) {
            runCatching {
                val uri = android.net.Uri.parse(absoluteUrl.toString())
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
    }

    private fun observeProgress(docId: String, navigator: EpubNavigatorFragment) {
        val library = LibraryManager(AppFileStore(filesDir))
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { locator ->
                    // First locator = the navigator has a rendered position:
                    // drop the splash (idempotent for later emissions).
                    if (!readerReady.value) {
                        readerReady.value = true
                        pendingExactJump?.let { target ->
                            // Notes-tab exact-place jump: the initial chapter
                            // has rendered, so this go() runs the same
                            // loadLocator text-highlight pipeline an in-book
                            // search-hit tap does. Swallowed failures degrade
                            // to the chapter-start landing.
                            pendingExactJump = null
                            lifecycleScope.launch(Dispatchers.Main) {
                                runCatching { navigator.go(target, false) }
                            }
                        }
                    }

                    // Pillar: chapter title + total percent (cheap, no IO).
                    // The pillar slider works in Readium 0..1 progression.
                    pillarTitle.value = locator.title.orEmpty()
                    val total = locator.locations.totalProgression ?: 0.0
                    pillarPercent.value = total

                    // Pace accumulation (time-to-finish): wall-clock + forward
                    // percent deltas only — ReadingPaceStats credits nothing for
                    // jumps/back-scrolls so the estimate stays conservative
                    // (imports packages/core reading/stats.ts semantics).
                    val p0 = ProgressBridge.buildProgress(
                        docId, locator, java.time.Instant.now().toString()
                    )
                    val nowElapsed = android.os.SystemClock.elapsedRealtime()
                    val prevPct = prevRelocatePercent
                    val prevMs = prevRelocateElapsedMs
                    if (prevPct != null && prevMs != null) {
                        paceState = ReadingPaceStats.updatePace(
                            paceState, nowElapsed - prevMs, p0.percent - prevPct
                        )
                    }
                    prevRelocatePercent = p0.percent
                    prevRelocateElapsedMs = nowElapsed
                    timeLeftText.value = ReadingPaceStats.formatTimeLeft(
                        ReadingPaceStats.estimateTimeLeftSeconds(
                            paceState, 100.0 - p0.percent
                        )
                    )

                    val p = p0.copy(pace = paceState)
                    // Collapse trailing scroll noise but keep meaningful movement
                    // (gate on the 0..100 percent scale = 0.4%).
                    val last = lastSavedProgress
                    val changed = last == null ||
                        last.location != p.location ||
                        abs(last.percent - p.percent) >= 0.4
                    if (changed) {
                        lastSavedProgress = p
                        // Debounce disk writes: coalesce rapid locator emissions.
                        saveJob?.cancel()
                        saveJob = loadScope.launch {
                            delay(500)
                            withContext(Dispatchers.IO) { library.saveProgress(p) }
                        }
                    }
                }
            }
        }
    }

    /** Last persisted progress — guards the write throttling above. */
    private var lastSavedProgress: Progress? = null

    /**
     * R2b hardware page-turner: volume keys, Bluetooth media buttons, hardware
     * page keys and DPAD. First press only (no key-repeat flooding). Consumed
     * keys never reach the WebView, so no double-turn.
     */
    @OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val direction = PageTurnKeys.fromKeyCode(keyCode)
        if (event?.action == android.view.KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            direction != null
        ) {
            val navigator = navigatorRef
            when (direction) {
                // Animated: matches the navigator's own tap-zone turns
                // (ViewPager2 smooth scroll) — audit Batch-2 polish item.
                PageTurnKeys.Direction.FORWARD -> navigator?.goForward(true)
                PageTurnKeys.Direction.BACKWARD -> navigator?.goBackward(true)
            }
            if (navigator != null) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** R2b scrubber: jump to a total-publication progression (0..1).
     *  Locator computation + go() both reach the navigator's WebView — main. */
    @OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
    private fun scrubTo(percent: Double) {
        val navigator = navigatorRef ?: return
        val publication = publicationRef ?: return
        lifecycleScope.launch(Dispatchers.Main) {
            val locator = publication.findService(LocatorService::class)
                ?.locateProgression(percent.coerceIn(0.0, 1.0))
                ?: return@launch
            navigator.go(locator, false)
        }
    }
}

/** A flattened table-of-contents entry. */
data class TocEntry(val title: String, val depth: Int, val link: Link)

/**
 * Floating selection toolbar (PWA parity): 4 color chips + Add note + Copy.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SelectionToolbarSheet(
    annotation: Annotation?,
    onColor: (HighlightColor) -> Unit,
    onNote: (String) -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    var noteEditing by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.padding(16.dp)
        ) {
            Text("Highlight", style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Spacer(
                androidx.compose.ui.Modifier.height(8.dp)
            )
            if (annotation != null) {
                Text(
                    "\u201C${annotation.quote.take(80)}\u201D",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                androidx.compose.foundation.layout.Spacer(
                    androidx.compose.ui.Modifier.height(12.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ANNOTATION_CHIP_ARGBS.forEach { (color, argb) ->
                    Box(
                        modifier = androidx.compose.ui.Modifier
                            .size(36.dp)
                            .background(androidx.compose.ui.graphics.Color(argb), CircleShape)
                            .clickable { onColor(color) }
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { noteEditing = true }) { Text("Add note") }
                TextButton(onClick = onCopy) { Text("Copy") }
            }
            if (noteEditing) {
                androidx.compose.foundation.layout.Spacer(
                    androidx.compose.ui.Modifier.height(8.dp)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { Text("Note\u2026") },
                    singleLine = false,
                    minLines = 2,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                )
                androidx.compose.foundation.layout.Row(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { noteEditing = false }) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            val note = noteText.trim()
                            if (note.isNotEmpty()) onNote(note)
                        }
                    ) { Text("Save") }
                }
            }
        }
    }
}

/**
 * Table of contents sheet (R1). Lists flattened TOC entries; tapping jumps.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TocSheet(
    entries: List<TocEntry>,
    onEntry: (TocEntry) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier
                .padding(16.dp)
        ) {
            Text("Contents", style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Spacer(
                Modifier.height(8.dp)
            )
            if (entries.isEmpty()) {
                Text(
                    "No table of contents.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(entries.size) { i ->
                        val e = entries[i]
                        TextButton(
                            onClick = { onEntry(e) },
                            modifier = androidx.compose.ui.Modifier
                                .padding(start = (e.depth * 16).dp)
                        ) {
                            Text(
                                e.title,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * In-book search sheet (EPUB-015): query box + streamed hit list. Tapping a
 * cell jumps to the matched text anchor and dismisses the sheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    query: String,
    busy: Boolean,
    error: String?,
    completed: Boolean,
    hits: List<ReaderSearch.Hit>,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHit: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.padding(16.dp)
        ) {
            Text("Search in book", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Find in book\u2026") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { onSubmit() }
                ),
                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
            )
            androidx.compose.foundation.layout.Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        error != null -> error
                        busy -> "Searching\u2026"
                        hits.isNotEmpty() -> "${hits.size} result${if (hits.size == 1) "" else "s"}"
                        completed -> "No results."
                        else -> "Two characters minimum."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = androidx.compose.ui.Modifier.weight(1f)
                )
                TextButton(
                    onClick = onSubmit,
                    enabled = !busy && ReaderSearch.isRunnableQuery(query)
                ) { Text("Search") }
            }
            if (busy) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = androidx.compose.ui.Modifier.heightIn(max = 340.dp)
            ) {
                items(hits.size) { i ->
                    val hit = hits[i]
                    TextButton(
                        onClick = { onHit(i) },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.foundation.layout.Column {
                            Text(
                                hit.snippet,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            hit.sectionTitle?.let { title ->
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reader splash: centered cover (when the doc has one) over the theme surface
 * with a spinner, shown until the navigator reports its first locator.
 */
@Composable
private fun ReaderSplash(cover: ImageBitmap?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(200.dp)
                        .height(300.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(28.dp))
            }
            CircularProgressIndicator(strokeWidth = 3.dp)
        }
    }
}
