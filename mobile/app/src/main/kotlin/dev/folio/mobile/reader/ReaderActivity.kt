package dev.folio.mobile.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.mutableStateOf
import dev.folio.mobile.R
import dev.folio.mobile.core.annotation.SelectionBridge
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.reader.ProgressBridge
import dev.folio.mobile.core.reader.ReaderPrefsMapper
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.store.PrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.LocatorService
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
 * R1 core reader: opens the document's `reading/book.epub` through Readium's
 * streamer, attaches an [EpubNavigatorFragment], and mirrors reading progression
 * into the schema-1 Progress store (locator → CFI via [ProgressBridge]).
 *
 * Prefs: [ReadingPrefs] are loaded from user/settings.json via [PrefsStore],
 * mapped to Readium by [ReaderPrefsMapper]; chrome edits re-submit preferences
 * live ([EpubNavigatorFragment.submitPreferences]) and persist back to the same
 * file — byte-compatible with the schema-1 backup contract.
 *
 * Restore-by-CFI (exact) is an open R1 item; v1 restores by saved percent via
 * the publication's LocatorService.
 */
class ReaderActivity : FragmentActivity() {

    companion object {
        const val EXTRA_DOC_ID = "dev.folio.mobile.READER_DOC_ID"
        private const val NAV_TAG = "EpubNavigatorFragment"

        fun createIntent(context: Context, docId: String): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_DOC_ID, docId)
    }

    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null
    private var navigatorRef: EpubNavigatorFragment? = null
    private var highlightManager: HighlightManager? = null
    private var currentDocId: String = ""
    private lateinit var prefsStore: PrefsStore

    /** Reading prefs live state (chrome edits, navigator consumes). */
    private val prefsState = mutableStateOf(ReadingPrefs())

    /** Chrome overlay visibility — toggled by tapping the page's middle third. */
    private val chromeVisible = mutableStateOf(true)

    /** Bottom pillar: current chapter title + total percent. */
    private val pillarTitle = mutableStateOf("")
    private val pillarPercent = mutableStateOf(0.0)

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
        setContentView(R.layout.activity_reader)
        prefsStore = PrefsStore(AppFileStore(filesDir))
        currentDocId = docId

        // Chrome overlay (Compose hosting on a FragmentActivity via ComposeView)
        val chrome = findViewById<ComposeView>(R.id.reader_chrome)
        chrome.setContent {
            ReaderChrome(
                visible = chromeVisible.value,
                prefs = prefsState.value,
                onBack = { onBackPressedDispatcher.onBackPressed() },
                onPrefsChange = { updated -> onPrefsChanged(updated) },
                onHighlight = { onHighlightTapped() },
                pillarTitle = pillarTitle.value,
                pillarPercent = pillarPercent.value
            )
        }

        loadScope.launch {
            // Prefs FIRST — the navigator gets the mapped values at attach time
            prefsState.value = prefsStore.loadReadingPrefs()
            loadAndAttach(docId)
        }
    }

    override fun onDestroy() {
        progressJob?.cancel()
        loadScope.cancel()
        super.onDestroy()
    }

    /** Chrome → navigator → persistence (single source of truth: prefsState). */
    private fun onPrefsChanged(updated: ReadingPrefs) {
        prefsState.value = updated
        navigatorRef?.submitPreferences(
            ReaderPrefsAndroid.toEpubInitialPreferences(
                ReaderPrefsMapper.mapPrefs(updated)
            )
        )
        loadScope.launch(Dispatchers.IO) {
            prefsStore.saveReadingPrefs(updated)
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
        val file = store.resolve("library/docs/${meta.slug}/$readingRel")

        val publication = openPublication(file)
        if (publication == null) {
            withContext(Dispatchers.Main) { finish() }
            return
        }

        // v1 restore: chapter-accurate via the saved CFI's spine component;
        // percent fallback when the location has no CFI (TXT-era/href fallback).
        val savedProgress: Progress? = library.getProgress(docId)
        var initialLocator: Locator? = null
        if (savedProgress != null && savedProgress.location.startsWith(ProgressBridge.CFI_PREFIX)) {
            val spineIdx = ProgressBridge.cfiSpineIndex(savedProgress.location)
            if (spineIdx != null) {
                publication.readingOrder.getOrNull(spineIdx)?.let { link ->
                    initialLocator = chapterLocator(link)
                }
            }
        }
        if (initialLocator == null && savedProgress != null) {
            publication.findService(LocatorService::class)
                ?.locateProgression(savedProgress.percent)
                ?.let { initialLocator = it }
        }
        if (initialLocator == null) {
            publication.readingOrder.firstOrNull()?.let { link ->
                initialLocator = chapterLocator(link)
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
        val mappedPrefs = ReaderPrefsMapper.mapPrefs(currentPrefs)
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
        )
        supportFragmentManager.fragmentFactory = factoryFragment
        supportFragmentManager.commitNow {
            add(R.id.navigator_container, EpubNavigatorFragment::class.java, null, NAV_TAG)
        }
        val navigator = supportFragmentManager.findFragmentByTag(NAV_TAG)
            as? EpubNavigatorFragment ?: return
        navigatorRef = navigator
        navigator.addInputListener(chromeTapListener())
        highlightManager = HighlightManager(navigator, docId).also { it.installListener() }
        observeProgress(docId, navigator)
    }

    /**
     * R3 groundwork: capture the current selection as a schema-1 highlight.
     * `currentSelection()` is a suspend read (no flow in 3.0.0), so the chrome
     * button fetches it on tap; a null result means no active selection.
     */
    private fun onHighlightTapped() {
        val navigator = navigatorRef ?: return
        val docId = currentDocId
        val manager = highlightManager ?: return
        loadScope.launch {
            val selection = navigator.currentSelection()
            if (selection == null) return@launch
            val now = java.time.Instant.now().toString()
            val annotation = SelectionBridge.annotationFromSelection(
                docId = docId,
                locator = selection.locator,
                id = java.util.UUID.randomUUID().toString(),
                updatedAt = now
            )
            withContext(Dispatchers.IO) {
                LibraryManager(AppFileStore(filesDir)).saveAnnotation(annotation)
            }
            manager.add(annotation, selection.locator)
            navigator.clearSelection()
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

    /** Navigator wires external links only via this listener (v1: no-op). */
    private fun readerListener() = object : EpubNavigatorFragment.Listener {
        override fun onExternalLinkActivated(absoluteUrl: AbsoluteUrl) {
            // R-phase TODO: open a browser / share sheet; blocked for v1 reader
        }
    }

    private fun observeProgress(docId: String, navigator: EpubNavigatorFragment) {
        val library = LibraryManager(AppFileStore(filesDir))
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { locator ->
                    // Pillar: chapter title + total percent (cheap, no IO)
                    pillarTitle.value = locator.title.orEmpty()
                    pillarPercent.value = ProgressBridge.totalPercent(locator)

                    val p = ProgressBridge.buildProgress(
                        docId, locator, java.time.Instant.now().toString()
                    )
                    // Collapse trailing scroll noise but keep meaningful movement
                    val last = lastSavedProgress
                    val changed = last == null ||
                        last.location != p.location ||
                        abs(last.percent - p.percent) >= 0.004
                    if (changed) {
                        lastSavedProgress = p
                        withContext(Dispatchers.IO) { library.saveProgress(p) }
                    }
                }
            }
        }
    }

    /** Last persisted progress — guards the write throttling above. */
    private var lastSavedProgress: Progress? = null
}
