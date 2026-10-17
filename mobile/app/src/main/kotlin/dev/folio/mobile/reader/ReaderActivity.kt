package dev.folio.mobile.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.folio.mobile.R
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.reader.ProgressBridge
import dev.folio.mobile.core.reader.ReaderPrefsMapper
import dev.folio.mobile.core.store.AppFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.shared.publication.Link
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

        loadScope.launch { loadAndAttach(docId) }
    }

    override fun onDestroy() {
        progressJob?.cancel()
        loadScope.cancel()
        super.onDestroy()
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

        // v1 restore: by saved percent (see class KDoc for the restore-by-CFI note).
        val savedProgress: Progress? = library.getProgress(docId)
        var initialLocator: Locator? = null
        if (savedProgress != null) {
            publication.findService(LocatorService::class)
                ?.locateProgression(savedProgress.percent)
                ?.let { initialLocator = it }
        }
        if (initialLocator == null) {
            publication.readingOrder.firstOrNull()?.let { link ->
                // Link.href is an Href object; resolve against root into a plain
                // Url for the Locator (mediaType = chapter is xhtml)
                initialLocator = Locator(
                    href = link.href.resolve(Url("/")),
                    mediaType = MediaType.XHTML
                )
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
        // the HTML/TXT/PDF reading editions route through different activities.
        val parser = EpubParser()
        val opener = PublicationOpener(publicationParser = parser)
        return opener.open(asset, allowUserInteraction = true).getOrNull()
    }

    private fun attachNavigator(publication: Publication, docId: String, initialLocator: Locator) {
        val mappedPrefs = ReaderPrefsMapper.mapPrefs(ReadingPrefs())
        val factory = EpubNavigatorFactory(
            publication = publication,
            configuration = EpubNavigatorFactory.Configuration(
                defaults = ReaderPrefsAndroid.toEpubDefaults(mappedPrefs)
            )
        )
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = ReaderPrefsAndroid.toEpubInitialPreferences(mappedPrefs),
            listener = readerListener(),
        )

        supportFragmentManager.commitNow {
            add(R.id.navigator_container, EpubNavigatorFragment::class.java, null, NAV_TAG)
        }
        val navigator = supportFragmentManager.findFragmentByTag(NAV_TAG)
            as? EpubNavigatorFragment ?: return
        observeProgress(docId, navigator)
    }

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
                navigator.currentLocator
                    .map {
                        ProgressBridge.buildProgress(
                            docId, it, java.time.Instant.now().toString()
                        )
                    }
                    .distinctUntilChanged { old, new ->
                        // CFI changes per scroll/turn; collapse trailing noise but
                        // keep saving once meaningful progression happened
                        old.location == new.location && abs(old.percent - new.percent) < 0.004
                    }
                    .collect { p ->
                        withContext(Dispatchers.IO) { library.saveProgress(p) }
                    }
            }
        }
    }
}
