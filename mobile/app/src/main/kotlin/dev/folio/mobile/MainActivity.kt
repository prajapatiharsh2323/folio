package dev.folio.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.importer.ImportDispatch
import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.importer.UnsupportedFormatException
import dev.folio.mobile.core.library.CoverLoader
import dev.folio.mobile.core.library.LibraryFileCorruptException
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.library.LibraryUi
import dev.folio.mobile.core.library.ReadResult
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.search.SearchService
import dev.folio.mobile.core.search.SearchUi
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.BuildConfig
import dev.folio.mobile.reader.ReaderActivity
import dev.folio.mobile.ui.components.FolioBookRow
import dev.folio.mobile.ui.components.CollectionPickerSheet
import dev.folio.mobile.ui.components.FolioDestructiveTextButton
import dev.folio.mobile.ui.components.FolioEmptyState
import dev.folio.mobile.ui.components.FolioIconButton
import dev.folio.mobile.ui.components.FolioSearchField
import dev.folio.mobile.ui.components.FolioStatusBanner
import dev.folio.mobile.ui.components.FolioFilledButton
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.FolioDimensions
import dev.folio.mobile.ui.theme.FolioSpacing
import dev.folio.mobile.ui.theme.FolioMobileTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Library screen (v1 library phase): manifest-backed list with reading progress,
 * content-sniffing SAF import, open/delete actions, and ACTION_VIEW import for
 * files opened via "open with" (parity with the PWA file handlers:
 * .epub/.pdf/.html/.htm/.txt — imported via a fresh task instance).
 */
class MainActivity : ComponentActivity() {

    companion object {
        // Audit EPUB-001: the pipeline refuses anything that is not EPUB/HTML/
        // TXT (PDF/mobi deliberately unsupported), so the picker must not
        // advertise application/octet-stream — selecting a PDF or .mobi there
        // always ended in the "Unsupported format" dead-end.
        val IMPORT_MIMES = arrayOf(
            "application/epub+zip",
            "text/html",
            "text/plain"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = sharedFileUri(intent)
        setContent {
            FolioMobileTheme {
                LibraryScreen(sharedFileUri = shared)
            }
        }
    }

    private fun sharedFileUri(intent: Intent?): Uri? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
}

/**
 * New LibraryManager per operation. Cheap (file store, no caches); callers
 * wrap the whole op in one call so every file access races identically. Also
 * used by the collections panels (LibraryCollections.kt).
 */
internal fun appLibrary(context: android.content.Context): LibraryManager =
    LibraryManager(AppFileStore(context.filesDir))

/** Honest copy for actions LibraryManager blocks while the index is corrupt (audit P0-01). */
internal fun corruptionMessage(purpose: String): String =
    "Library index needs repair — $purpose paused to keep your library safe"

/** Sample import kept for the blocked device-smoke flow (no files needed). */
private suspend fun runSample(context: android.content.Context) {
    withContext(Dispatchers.IO) {
        val sample = buildString {
            appendLine("The Reader's Lantern")
            appendLine()
            appendLine("A folio test chapter, kept deliberately short so the import runs fast.")
            appendLine()
            appendLine("Once the lantern was lit, the pages seemed to step out of the dark, one column at a time. The reader turned them slowly, watching the margins breathe.")
            appendLine()
            appendLine("She had carried this little library through three cities: a bag of old paperbacks, a folding stand, and one worry — that the words might someday stop fitting together. They never did.")
            appendLine()
            appendLine("The second chapter is where the reading comfort settings earn their keep: line height, margins, and the letters set free on the page.")
            appendLine()
            appendLine("By the third evening she was quoting the book to the mirror, which is, the lantern agreed, the whole point of owning one.")
        }
        val result = dev.folio.mobile.core.importer.TxtImporter.import(
            sample.toByteArray(Charsets.UTF_8),
            "sample.txt"
        )
        val library = appLibrary(context)
        if (library.findDuplicate(result.meta.contentHash) == null) {
            library.addDocument(result)
        }
    }
}

/** What the user chose when a batch contains an already-imported file. */
private enum class DuplicateChoice { Skip, Replace, KeepBoth }

/** Per-file progress for the batch import UI. [index] is zero-based. */
private data class ImportBatchProgress(val index: Int, val total: Int, val name: String)

/** Result of one file inside a single/multi-file import run. */
private data class ImportOutcome(
    val added: Boolean = false,
    val replaced: Boolean = false,
    val keptBoth: Boolean = false,
    val skipped: Boolean = false,
    val failure: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(sharedFileUri: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var entries by remember { mutableStateOf<List<LibraryUi.LibraryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LibraryUi.LibraryItem?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    var damagedNotice by remember { mutableStateOf<LibraryUi.DamagedNotice?>(null) }
    var damagedDialogOpen by remember { mutableStateOf(false) }
    var orphanNotice by remember { mutableStateOf<LibraryUi.OrphanNotice?>(null) }
    var orphanDialogOpen by remember { mutableStateOf(false) }
    var collections by remember { mutableStateOf<List<Collection>>(emptyList()) }
    var activeFilter by remember { mutableStateOf<LibraryUi.LibraryFilter?>(null) }
    var detailsTarget by remember { mutableStateOf<LibraryUi.LibraryItem?>(null) }
    var collectionsScreen by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var bulkAddPicker by remember { mutableStateOf(false) }
    var addToCollectionTarget by remember { mutableStateOf<LibraryUi.LibraryItem?>(null) }
    var sortBy by remember { mutableStateOf("recent") }
    var duplicateResult by remember { mutableStateOf<ImportResult?>(null) }
    var duplicateDialogOpen by remember { mutableStateOf(false) }
    var duplicateChoice by remember { mutableStateOf<CompletableDeferred<DuplicateChoice>?>(null) }
    var importProgress by remember { mutableStateOf<ImportBatchProgress?>(null) }
    var importFailures by remember { mutableStateOf<List<String>?>(null) }

    // Audit P0-01: a corrupt manifest must never render as a normal empty
    // library. Mutations are blocked in LibraryManager until repair/restore.
    var indexCorrupt by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }

    // Audit P0-02: corrupt progress/collections files must not masquerade as
    // "nothing saved" in the shelf. They are preserved untouched; this banner
    // explains the state and points at backup restore.
    var corruptUserState by remember { mutableStateOf<List<String>>(emptyList()) }
    var userStateDialogOpen by remember { mutableStateOf(false) }

    // Any library mutation bumps the version so the search index rebuilds.
    var libraryVersion by remember { mutableStateOf(0) }

    suspend fun refresh() {
        val library = appLibrary(context)
        try {
            val docs = withContext(Dispatchers.IO) { library.listDocuments() }
            val progressState = withContext(Dispatchers.IO) { library.readAllProgressState() }
            val progress = when (progressState) {
                is ReadResult.Loaded -> progressState.value
                is ReadResult.Missing -> emptyMap()
                is ReadResult.Corrupt -> emptyMap()
            }
            val collectionsState = withContext(Dispatchers.IO) { library.readCollectionsState() }
            val loadedCollections = when (collectionsState) {
                is ReadResult.Loaded -> collectionsState.value
                is ReadResult.Missing -> emptyList()
                is ReadResult.Corrupt -> emptyList()
            }
            corruptUserState = buildList {
                if (progressState is ReadResult.Corrupt) add("reading progress")
                if (collectionsState is ReadResult.Corrupt) add("collections")
            }
            entries = sortLibrary(docs.map { LibraryUi.item(it, progress[it.id]) }, sortBy, progress)
            // Recovery state (spec §4 Rule 2): damaged entries + interrupted imports.
            withContext(Dispatchers.IO) {
                damagedNotice = LibraryUi.damagedNotice(library.damagedDocuments())
                orphanNotice = LibraryUi.orphanNotice(library.scanInterruptedImports())
            }
            collections = loadedCollections
            indexCorrupt = false
        } catch (corrupt: LibraryFileCorruptException) {
            // Audit P0-01: never present index corruption as an empty library —
            // import/delete/recovery stay blocked by LibraryManager meanwhile.
            entries = emptyList()
            collections = emptyList()
            damagedNotice = null
            orphanNotice = null
            corruptUserState = emptyList()
            indexCorrupt = true
        }
        // Re-bind the active filter: renames keep it, deleted targets drop it.
        activeFilter = LibraryUi.rebind(activeFilter, LibraryUi.filters(collections, entries))
        libraryVersion += 1
        loading = false
    }

    /** Rebuilds a corrupt manifest from library/docs folders (audit P0-01). */
    fun repairIndex() {
        if (repairing) return
        scope.launch {
            repairing = true
            try {
                val result = withContext(Dispatchers.IO) { appLibrary(context).repairManifest() }
                refresh()
                snackbar.showSnackbar(
                    if (result.skipped.isEmpty()) {
                        "Library index repaired — ${result.registered} book(s) recovered"
                    } else {
                        "Index repaired (${result.registered} book(s)); " +
                            "${result.skipped.size} folder(s) still need attention"
                    }
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                snackbar.showSnackbar(
                    "Repair couldn't finish — the original index file is preserved unchanged"
                )
            } finally {
                repairing = false
            }
        }
    }

    // Refresh on every resume (initial open + return from the reader) so progress
    // bars and sort buckets reflect writes made in ReaderActivity.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun uriDisplayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "import"

    /**
     * Imports one file. Duplicates pause only this file while the user picks
     * Skip/Replace/Keep both; the caller keeps processing the remaining batch
     * afterwards (audit P1-03: one bad or duplicate file never blocks the rest).
     */
    suspend fun importOne(uri: Uri): ImportOutcome {
        val name = uriDisplayName(uri)
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Cannot read the selected file")
        }
        if (bytes.size > 50 * 1024 * 1024) {
            return ImportOutcome(failure = "File too large (max 50 MB)")
        }
        val result = try {
            ImportDispatch.import(bytes, name)
        } catch (e: UnsupportedFormatException) {
            return ImportOutcome(failure = "Unsupported format — EPUB, HTML or TXT only")
        }

        val library = appLibrary(context)
        val duplicate = withContext(Dispatchers.IO) {
            library.findDuplicate(result.meta.contentHash)
        }
        if (duplicate == null) {
            withContext(Dispatchers.IO) { library.addDocument(result) }
            return ImportOutcome(added = true)
        }

        val choice = CompletableDeferred<DuplicateChoice>()
        duplicateResult = result
        duplicateChoice = choice
        duplicateDialogOpen = true
        val picked = try {
            choice.await()
        } finally {
            duplicateDialogOpen = false
            duplicateResult = null
            duplicateChoice = null
        }
        return when (picked) {
            DuplicateChoice.Skip -> ImportOutcome(skipped = true)
            DuplicateChoice.KeepBoth -> {
                withContext(Dispatchers.IO) { library.addDocument(result) }
                ImportOutcome(keptBoth = true)
            }
            DuplicateChoice.Replace -> {
                val current = withContext(Dispatchers.IO) {
                    library.findDuplicate(result.meta.contentHash)
                }
                if (current != null) {
                    withContext(Dispatchers.IO) { library.replaceDocument(current.id, result) }
                    ImportOutcome(replaced = true)
                } else {
                    withContext(Dispatchers.IO) { library.addDocument(result) }
                    ImportOutcome(added = true)
                }
            }
        }
    }

    fun runImport(uris: List<Uri>) {
        if (uris.isEmpty() || importing) return
        scope.launch {
            importing = true
            importFailures = null
            var added = 0
            var replaced = 0
            var keptBoth = 0
            var skipped = 0
            val failures = mutableListOf<String>()
            try {
                uris.forEachIndexed { index, uri ->
                    val name = uriDisplayName(uri)
                    importProgress = ImportBatchProgress(index, uris.size, name)
                    try {
                        val outcome = importOne(uri)
                        if (outcome.added) added++
                        if (outcome.replaced) replaced++
                        if (outcome.keptBoth) keptBoth++
                        if (outcome.skipped) skipped++
                        outcome.failure?.let { failures += "$name: $it" }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (corrupt: LibraryFileCorruptException) {
                        failures += "$name: library index needs repair"
                    } catch (t: Throwable) {
                        failures += "$name: ${t.message ?: "Import failed"}"
                    }
                }
                refresh()

                if (uris.size == 1) {
                    when {
                        failures.isNotEmpty() -> snackbar.showSnackbar(failures.first().substringAfter(": "))
                        replaced == 1 -> snackbar.showSnackbar("Replaced — notes and progress kept")
                        keptBoth == 1 -> snackbar.showSnackbar("Imported as new copy")
                        skipped == 1 -> snackbar.showSnackbar("Already in your library — skipped")
                        else -> snackbar.showSnackbar("Imported")
                    }
                } else {
                    val summary = buildList {
                        if (added > 0) add("Imported $added")
                        if (replaced > 0) add("replaced $replaced")
                        if (keptBoth > 0) add("kept $keptBoth as new")
                        if (skipped > 0) add("skipped $skipped duplicate${if (skipped == 1) "" else "s"}")
                        if (failures.isNotEmpty()) add("${failures.size} couldn't be imported")
                    }.joinToString(" · ").ifBlank { "Nothing imported" }
                    if (failures.isNotEmpty()) importFailures = failures
                    snackbar.showSnackbar(summary)
                }
            } finally {
                importing = false
                importProgress = null
                duplicateDialogOpen = false
                duplicateResult = null
                duplicateChoice = null
            }
        }
    }

    // Shared file (ACTION_VIEW) handled once per composition instance.
    if (sharedFileUri != null) {
        LaunchedEffect(sharedFileUri) { runImport(listOf(sharedFileUri)) }
    }

    // Audit P1-03: the picker accepts several files; each is imported in turn
    // with per-file progress and isolated failures.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> runImport(uris) }

    val filterOptions = LibraryUi.filters(collections, entries)
    val visibleEntries = LibraryUi.applyFilter(entries, activeFilter)

    // Audit P1-14: recovery stays discoverable in Library search mode. The
    // full banners would crowd the results, so search shows one compact
    // "Needs attention" row that opens the matching recovery dialog.
    val recoveryCount =
        (if (indexCorrupt) 1 else 0) +
            (damagedNotice?.count ?: 0) +
            (orphanNotice?.count ?: 0) +
            (if (corruptUserState.isNotEmpty()) 1 else 0)

    // Long-press shelf: system back exits selection before leaving the screen.
    if (selectionMode) {
        BackHandler {
            selectionMode = false
            selectedIds = emptySet()
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    onClose = {
                        selectionMode = false
                        selectedIds = emptySet()
                    },
                    onSelectAll = {
                        selectedIds = visibleEntries.map { it.id }.toSet()
                    },
                    onClear = { selectedIds = emptySet() },
                )
            } else if (!collectionsScreen) {
                TopAppBar(
                title = {
                    Column {
                        Text(
                            "Library",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        val count = entries.size
                        Text(
                            when {
                                loading -> "Loading…"
                                count == 1 -> "1 book or article"
                                count > 1 -> "$count books and articles"
                                else -> "Your reading shelf"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = !importing && !loading,
                        onClick = { searchActive = true }
                    ) {
                        Icon(FolioIcons.Search, contentDescription = "Search")
                    }
                    Box {
                        var sortMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(FolioIcons.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false }
                        ) {
                            listOf("recent", "title", "author", "progress").forEach { key ->
                                val selected = sortBy == key
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (key) {
                                                "recent" -> "Recently read"
                                                "title" -> "Title"
                                                "author" -> "Author"
                                                else -> "Progress"
                                            }
                                        )
                                    },
                                    leadingIcon = {
                                        if (selected) {
                                            Icon(FolioIcons.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        sortMenuOpen = false
                                        sortBy = key
                                        scope.launch { refresh() }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(
                        enabled = !importing && !indexCorrupt,
                        onClick = { importLauncher.launch(MainActivity.IMPORT_MIMES) }
                    ) {
                        Icon(FolioIcons.Plus, contentDescription = "Import file")
                    }
                    Box {
                        var topMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { topMenuOpen = true }) {
                            Icon(FolioIcons.MoreVertical, contentDescription = "More actions")
                        }
                        DropdownMenu(
                            expanded = topMenuOpen,
                            onDismissRequest = { topMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Collections") },
                                leadingIcon = { Icon(FolioIcons.Library, contentDescription = null) },
                                onClick = {
                                    topMenuOpen = false
                                    collectionsScreen = true
                                }
                            )
                            if (entries.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Select books") },
                                    leadingIcon = { Icon(FolioIcons.Check, contentDescription = null) },
                                    onClick = {
                                        topMenuOpen = false
                                        selectedIds = emptySet()
                                        selectionMode = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Notes") },
                                leadingIcon = { Icon(FolioIcons.NotebookPen, contentDescription = null) },
                                onClick = {
                                    topMenuOpen = false
                                    context.startActivity(NotesActivity.createIntent(context))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(FolioIcons.Settings, contentDescription = null) },
                                onClick = {
                                    topMenuOpen = false
                                    context.startActivity(SettingsActivity.createIntent(context))
                                }
                            )
                        }
                    }
                }
            )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (importing) {
                val progress = importProgress
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2)
                ) {
                    if (progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = {
                                progress.index.toFloat() / progress.total.coerceAtLeast(1)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Importing ${progress.index + 1} of ${progress.total} · ${progress.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = FolioSpacing.x1),
                        )
                    }
                }
            }
            if (searchActive) {
                SearchPanel(
                    libraryVersion = libraryVersion,
                    initialLoading = loading,
                    recoveryCount = recoveryCount,
                    onReviewRecovery = {
                        when {
                            indexCorrupt -> repairIndex()
                            damagedNotice != null -> damagedDialogOpen = true
                            orphanNotice != null -> orphanDialogOpen = true
                            corruptUserState.isNotEmpty() -> userStateDialogOpen = true
                        }
                    },
                    onOpenHit = { docId, order ->
                        context.startActivity(
                            ReaderActivity.createIntent(context, docId, order)
                        )
                    },
                    onClose = { searchActive = false }
                )
            } else if (collectionsScreen) {
                CollectionsPanel(
                    collections = collections,
                    entries = entries,
                    snackbar = snackbar,
                    onBack = { collectionsScreen = false },
                    onLibraryChanged = { scope.launch { refresh() } },
                    onDocDetails = { detailsTarget = it },
                    onDocDelete = { deleteTarget = it },
                )
            } else {
                if (indexCorrupt) {
                    FolioStatusBanner(
                        icon = FolioIcons.AlertTriangle,
                        message = "The library index is unreadable — your books are untouched on disk. " +
                            "Import, delete and recovery are paused until the index is repaired.",
                        actionLabel = if (repairing) "Repairing…" else "Repair",
                        onAction = ::repairIndex,
                        modifier = Modifier.padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
                    )
                }
                damagedNotice?.let { notice ->
                    FolioStatusBanner(
                        icon = FolioIcons.AlertTriangle,
                        message = notice.bannerText,
                        actionLabel = "Review",
                        onAction = { damagedDialogOpen = true },
                        modifier = Modifier.padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
                    )
                }
                if (corruptUserState.isNotEmpty()) {
                    FolioStatusBanner(
                        icon = FolioIcons.AlertTriangle,
                        message = "Saved ${corruptUserState.joinToString(" and ")} can't be read — " +
                            "the file was left untouched. Nothing was reset.",
                        actionLabel = "Review",
                        onAction = { userStateDialogOpen = true },
                        modifier = Modifier.padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
                    )
                }
                orphanNotice?.let { notice ->
                    FolioStatusBanner(
                        icon = FolioIcons.HardDrive,
                        message = notice.bannerText,
                        actionLabel = "Review",
                        onAction = { orphanDialogOpen = true },
                        modifier = Modifier.padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
                    )
                }
                if (filterOptions.isNotEmpty() && !selectionMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = activeFilter == null,
                            onClick = { activeFilter = null },
                            label = { Text("All") }
                        )
                        for (option in filterOptions) {
                            FilterChip(
                                selected = activeFilter == option,
                                onClick = {
                                    activeFilter = if (activeFilter == option) null else option
                                },
                                label = { Text(filterLabel(option)) }
                            )
                        }
                    }
                }
                if (loading) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (indexCorrupt) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = FolioSpacing.x4),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FolioEmptyState(
                            icon = FolioIcons.AlertTriangle,
                            title = "Library index needs repair",
                            body = "The library's index file could not be read. Your books, notes and " +
                                "progress are safe on disk — Folio pauses changes until the index is " +
                                "rebuilt so nothing can be lost.",
                            note = if (repairing) "Repairing…" else null,
                        )
                        TextButton(
                            onClick = ::repairIndex,
                            enabled = !repairing,
                        ) {
                            Text(if (repairing) "Repairing…" else "Repair index")
                        }
                    }
                } else if (entries.isEmpty()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FolioEmptyState(
                            icon = FolioIcons.Library,
                            title = "Your reading life starts here",
                            body = "Import an EPUB, HTML or TXT file — highlights, notes and progress stay on this device.",
                            ctaLabel = if (importing) null else "Import a book",
                            onCta = if (importing) null else {
                                { importLauncher.launch(MainActivity.IMPORT_MIMES) }
                            },
                            note = if (importing) "Importing…" else null,
                        )
                        if (!importing && BuildConfig.DEBUG) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        importing = true
                                        try {
                                            runSample(context)
                                            refresh()
                                        } catch (t: Throwable) {
                                            snackbar.showSnackbar(t.message ?: "Import failed")
                                        } finally {
                                            importing = false
                                        }
                                    }
                                }
                            ) {
                                Text("Import sample text (debug)")
                            }
                        }
                    }
                } else if (visibleEntries.isEmpty()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FolioEmptyState(
                            icon = FolioIcons.Filter,
                            title = "Nothing matches this filter",
                            body = "Try a different collection or tag.",
                        )
                        TextButton(onClick = { activeFilter = null }) { Text("Clear filter") }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(top = FolioSpacing.x1, bottom = FolioSpacing.x6),
                    ) {
                        items(visibleEntries, key = { it.id }) { item ->
                            LibraryCard(
                                item = item,
                                onOpen = {
                                    context.startActivity(
                                        ReaderActivity.createIntent(context, item.id)
                                    )
                                },
                                onDetails = { detailsTarget = item },
                                onDelete = { deleteTarget = item },
                                onToggleSelect = if (selectionMode) {
                                    {
                                        selectedIds = if (item.id in selectedIds) {
                                            selectedIds - item.id
                                        } else {
                                            selectedIds + item.id
                                        }
                                    }
                                } else {
                                    null
                                },
                                selected = item.id in selectedIds,
                                onLongPress = if (!selectionMode) {
                                    {
                                        selectionMode = true
                                        selectedIds = setOf(item.id)
                                    }
                                } else {
                                    null
                                },
                                onAddToCollection = { addToCollectionTarget = item },
                            )
                        }
                    }
                }
                if (selectionMode) {
                    SelectionActionBar(
                        selectedIds = selectedIds,
                        showRemove = activeFilter is LibraryUi.LibraryFilter.CollectionFilter,
                        onAddToCollection = { bulkAddPicker = true },
                        onRemove = {
                            val collFilter = activeFilter as? LibraryUi.LibraryFilter.CollectionFilter
                            val coll = collFilter?.let { f -> collections.firstOrNull { it.id == f.id } }
                            if (coll != null) {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            appLibrary(context).removeDocsFromCollection(coll.id, selectedIds.toList())
                                        }
                                        refresh()
                                        selectionMode = false
                                        val count = selectedIds.size
                                        selectedIds = emptySet()
                                        snackbar.showSnackbar(
                                            if (count == 1) "Removed 1 book from \"${coll.name}\""
                                            else "Removed $count books from \"${coll.name}\""
                                        )
                                    } catch (t: Throwable) {
                                        snackbar.showSnackbar(t.message ?: "Remove failed")
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (userStateDialogOpen) {
        AlertDialog(
            onDismissRequest = { userStateDialogOpen = false },
            title = { Text("Saved data can't be read") },
            text = {
                Text(
                    "Folio couldn't decode your saved ${corruptUserState.joinToString(" and ")}. " +
                        "The original file has been kept unchanged, so restoring a backup from " +
                        "Settings can bring this data back. New saves are paused until it is repaired."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    userStateDialogOpen = false
                    context.startActivity(SettingsActivity.createIntent(context))
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { userStateDialogOpen = false }) { Text("Close") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete “${target.title}”?") },
            text = {
                Text(
                    "This removes the book's files, highlights, notes, bookmarks and " +
                        "reading progress. Collections stay, without this book."
                )
            },
            confirmButton = {
                FolioDestructiveTextButton(
                    text = "Delete",
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    appLibrary(context).deleteDocument(target.id)
                                }
                                refresh()
                                if (!result.manifestRemoved) {
                                    snackbar.showSnackbar(
                                        "Not deleted — the book's files couldn't be removed. " +
                                            "It stays in your library; try again."
                                    )
                                } else if (result.cleanupWarnings.isNotEmpty()) {
                                    snackbar.showSnackbar(
                                        "Deleted, but ${result.cleanupWarnings.joinToString(", ")} " +
                                            "couldn't be cleaned up"
                                    )
                                } else {
                                    snackbar.showSnackbar("Deleted “${target.title}”")
                                }
                            } catch (corrupt: LibraryFileCorruptException) {
                                snackbar.showSnackbar(
                                    corruptionMessage("Deleting")
                                )
                            } catch (t: Throwable) {
                                snackbar.showSnackbar(t.message ?: "Delete failed")
                            }
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Recovery dialog (spec §4 Rule 2): remove damaged docs or dismiss.
    damagedNotice?.takeIf { damagedDialogOpen }?.let { notice ->
        DamagedDocsDialog(
            notice = notice,
            onRemove = { item ->
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            appLibrary(context).deleteDocument(item.id)
                        }
                        refresh()
                        if (damagedNotice == null) damagedDialogOpen = false
                        snackbar.showSnackbar(
                            when {
                                !result.manifestRemoved ->
                                    "Couldn't remove “${item.label}” — it stays listed. Try again."
                                result.cleanupWarnings.isNotEmpty() ->
                                    "Removed “${item.label}”, but some saved data couldn't be cleaned up"
                                else -> "Removed “${item.label}”"
                            }
                        )
                    } catch (corrupt: LibraryFileCorruptException) {
                        snackbar.showSnackbar(corruptionMessage("Cleanup"))
                    } catch (t: Throwable) {
                        snackbar.showSnackbar(t.message ?: "Remove failed")
                    }
                }
            },
            onClose = { damagedDialogOpen = false }
        )
    }

    // Interrupted imports (spec §4 Rule 2): finish (re-register) or delete.
    orphanNotice?.takeIf { orphanDialogOpen }?.let { notice ->
        OrphanDocsDialog(
            notice = notice,
            onFinish = { item ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            appLibrary(context).finishInterruptedImport(item.slug)
                        }
                        refresh()
                        if (orphanNotice == null) orphanDialogOpen = false
                        snackbar.showSnackbar("Restored “${item.label}”")
                    } catch (t: Throwable) {
                        snackbar.showSnackbar(t.message ?: "Finish failed")
                    }
                }
            },
            onDelete = { item ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            appLibrary(context).discardInterruptedImport(item.slug)
                        }
                        refresh()
                        if (orphanNotice == null) orphanDialogOpen = false
                        snackbar.showSnackbar("Deleted leftover files")
                    } catch (t: Throwable) {
                        snackbar.showSnackbar(t.message ?: "Delete failed")
                    }
                }
            },
            onClose = { orphanDialogOpen = false }
        )
    }

    // Details dialog: tags + collections (PWA DocumentSheet parity).
    detailsTarget?.let { target ->
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "folio-collections",
                "details open: doc=${target.id} collectionIds=${target.collectionIds}"
            )
        }
        DetailsDialog(
            item = target,
            collections = collections,
            onSave = { tags, collectionIds ->
                scope.launch {
                    try {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "folio-collections",
                                "details save: doc=${target.id} tags=$tags collectionIds=$collectionIds"
                            )
                        }
                        withContext(Dispatchers.IO) {
                            val library = appLibrary(context)
                            library.setDocCollections(target.id, collectionIds)
                            library.updateDocMeta(target.id, tags = tags)
                        }
                        refresh()
                        detailsTarget = null
                        if (BuildConfig.DEBUG) {
                            val readback = withContext(Dispatchers.IO) { appLibrary(context) }
                            android.util.Log.d(
                                "folio-collections",
                                "save readback: memberships=" +
                                    readback.getCollections()
                                        .associate { it.name to it.docIds.size } +
                                    " docCollectionIds=" +
                                    (readback.listDocuments()
                                        .firstOrNull { it.id == target.id }?.collectionIds ?: "MISSING")
                            )
                        }
                        snackbar.showSnackbar("Saved")
                    } catch (t: Throwable) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.w("folio-collections", "details save failed", t)
                        }
                        snackbar.showSnackbar(t.message ?: "Save failed")
                    }
                }
            },
            onCreateCollection = { name ->
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("folio-collections", "details create collection: $name")
                }
                val created = withContext(Dispatchers.IO) {
                    try {
                        appLibrary(context).createCollection(name)
                    } catch (t: Throwable) {
                        // Surface it (the dialog swallows the rethrow as before).
                        snackbar.showSnackbar(t.message ?: "Create collection failed")
                        throw t
                    }
                }
                refresh()
                created
            },
            onDismiss = { detailsTarget = null }
        )
    }

    // Duplicate import prompt (spec §4 Rule 3). The waiting import coroutine
    // applies the choice and then continues with the rest of a batch.
    if (duplicateDialogOpen) {
        val choose: (DuplicateChoice) -> Unit = { choice ->
            val pending = duplicateChoice
            if (pending != null) pending.complete(choice) else duplicateDialogOpen = false
        }
        AlertDialog(
            onDismissRequest = { choose(DuplicateChoice.Skip) },
            title = { Text("Already in library") },
            text = {
                Column {
                    Text(
                        "“${duplicateResult?.meta?.title ?: "This file"}” is already in your library. " +
                            "Replace keeps this copy's highlights, notes, reading progress and collections."
                    )
                    if ((importProgress?.total ?: 0) > 1) {
                        Text(
                            "The other selected files continue after you choose.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = FolioSpacing.x2),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choose(DuplicateChoice.KeepBoth) }) { Text("Keep both") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { choose(DuplicateChoice.Skip) }) { Text("Skip") }
                    TextButton(onClick = { choose(DuplicateChoice.Replace) }) { Text("Replace") }
                }
            }
        )
    }

    // A multi-file import reports every failure after the batch finishes; a
    // single failed file stays a snackbar (no dialog for one item).
    importFailures?.let { failures ->
        AlertDialog(
            onDismissRequest = { importFailures = null },
            title = { Text("Some files weren't imported") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.x2)) {
                    Text("The rest of the batch was imported normally.")
                    failures.forEach { failure ->
                        Text(
                            "• $failure",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { importFailures = null }) { Text("Close") }
            }
        )
    }

    // Single book → collections (row overflow "Add to collection…"): exact
    // membership sync per spec §5 (like the details sheet), inline create.
    addToCollectionTarget?.let { target ->
        CollectionPickerSheet(
            collections = collections,
            initialChecked = target.collectionIds.toSet(),
            selectionLabel = target.title,
            onApply = { ids ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            appLibrary(context).setDocCollections(target.id, ids.toList())
                        }
                        refresh()
                        snackbar.showSnackbar("Saved")
                    } catch (t: Throwable) {
                        snackbar.showSnackbar(t.message ?: "Save failed")
                    }
                }
            },
            onCreate = { name, onCreated ->
                scope.launch {
                    try {
                        val created = withContext(Dispatchers.IO) {
                            appLibrary(context).createCollection(name)
                        }
                        refresh()
                        // Membership is applied by the picker's Add action.
                        onCreated(created)
                    } catch (t: Throwable) {
                        snackbar.showSnackbar(t.message ?: "Create collection failed")
                    }
                }
            },
            onDismiss = { addToCollectionTarget = null },
        )
    }

    // Bulk multi-select → collections: additions only (membership Add), one
    // atomic write per collection, documents shared across picks.
    if (bulkAddPicker) {
        val selection = selectedIds
        val preChecked = collections
            .filter { selection.all { docId -> docId in it.docIds } }
            .map { it.id }
            .toSet()
        CollectionPickerSheet(
            collections = collections,
            initialChecked = preChecked,
            selectionLabel = when {
                selection.size == 1 -> "1 selected book"
                else -> "${selection.size} selected books"
            },
            onApply = { ids ->
                scope.launch {
                    var touched = 0
                    for (collId in ids) {
                        val name = collections.firstOrNull { it.id == collId }?.name ?: "collection"
                        try {
                            withContext(Dispatchers.IO) {
                                appLibrary(context).addDocsToCollection(collId, selection.toList())
                            }
                            touched += 1
                            snackbar.showSnackbar("Added to \"$name\"")
                        } catch (t: Throwable) {
                            snackbar.showSnackbar(t.message ?: "Add failed")
                        }
                    }
                    if (touched > 0) {
                        refresh()
                        selectionMode = false
                        selectedIds = emptySet()
                    }
                }
            },
            onCreate = { name, onCreated ->
                scope.launch {
                    try {
                        val created = withContext(Dispatchers.IO) {
                            appLibrary(context).createCollection(name)
                        }
                        if (selection.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                appLibrary(context).addDocsToCollection(created.id, selection.toList())
                            }
                        }
                        refresh()
                        onCreated(created)
                    } catch (t: Throwable) {
                        snackbar.showSnackbar(t.message ?: "Create collection failed")
                    }
                }
            },
            onDismiss = { bulkAddPicker = false },
        )
    }
}

private fun filterLabel(filter: LibraryUi.LibraryFilter): String = when (filter) {
    is LibraryUi.LibraryFilter.TagFilter -> "#${filter.tag}"
    is LibraryUi.LibraryFilter.CollectionFilter -> filter.name
}

/**
 * Details sheet (PWA DocumentSheet parity): tag chips + collection checkboxes +
 * create. A tall bottom sheet instead of a cramped AlertDialog (P1-07): the
 * content scrolls, the keyboard can't trap Save, and the sheet header/close is
 * the shared one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsDialog(
    item: LibraryUi.LibraryItem,
    collections: List<Collection>,
    onSave: (tags: List<String>, collectionIds: List<String>) -> Unit,
    onCreateCollection: suspend (String) -> Collection,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tags by remember(item.id) { mutableStateOf(item.tags) }
    var selected by remember(item.id) { mutableStateOf(item.collectionIds.toSet()) }
    var tagInput by remember { mutableStateOf("") }
    var newCollName by remember { mutableStateOf("") }
    var creatingCollection by remember { mutableStateOf(false) }
    var localCollections by remember(item.id) { mutableStateOf(collections) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = FolioSpacing.x6)
        ) {
            dev.folio.mobile.ui.components.FolioSheetHeader(
                title = "Details",
                subtitle = item.title,
                onClose = onDismiss,
            )
            Column(
                modifier = Modifier.padding(horizontal = FolioSpacing.x6),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
            ) {
                Text("Tags", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (tag in tags) {
                            InputChip(
                                selected = false,
                                onClick = { tags = tags - tag },
                                label = { Text("#$tag") },
                                trailingIcon = {
                                    Icon(
                                        FolioIcons.Close,
                                        contentDescription = "Remove tag"
                                    )
                                }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    dev.folio.mobile.ui.components.FolioTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        placeholder = "Add a tag…",
                        label = "Tag",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        LibraryUi.normalizeTag(tagInput, tags)?.let { clean ->
                            tags = tags + clean
                            tagInput = ""
                        }
                    }) { Text("Add") }
                }

                Text(
                    "Collections",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = FolioSpacing.x3),
                )
                if (localCollections.isEmpty()) {
                    Text(
                        "No collections yet — create one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column {
                        for (coll in localCollections) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    // Whole-row tap: on the PWA the entire row
                                    // toggles the checkbox, on the phone only the
                                    // bare Checkbox did — read as "unable to add
                                    // to collection" when tapping the label.
                                    .fillMaxWidth()
                                    .clickable {
                                        val nowChecked = coll.id !in selected
                                        selected = if (nowChecked) {
                                            selected + coll.id
                                        } else {
                                            selected - coll.id
                                        }
                                        if (BuildConfig.DEBUG) {
                                            android.util.Log.d(
                                                "folio-collections",
                                                "details toggle: ${coll.name} -> $nowChecked"
                                            )
                                        }
                                    }
                            ) {
                                Checkbox(
                                    checked = coll.id in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + coll.id
                                        else selected - coll.id
                                    }
                                )
                                Text(
                                    coll.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    dev.folio.mobile.ui.components.FolioTextField(
                        value = newCollName,
                        onValueChange = { newCollName = it },
                        placeholder = "New collection…",
                        label = "New collection name",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = !creatingCollection,
                        onClick = {
                            val name = newCollName.trim()
                            if (name.isNotEmpty() && !creatingCollection) {
                                creatingCollection = true
                                scope.launch {
                                    try {
                                        val created = onCreateCollection(name)
                                        localCollections = localCollections + created
                                        selected = selected + created.id
                                        newCollName = ""
                                    } catch (_: Throwable) {
                                        // Surfaced upstream via the snackbar in onCreateCollection.
                                    } finally {
                                        creatingCollection = false
                                    }
                                }
                            }
                        }
                    ) { Text("Create") }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FolioSpacing.x5),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel") }
                    dev.folio.mobile.ui.components.FolioFilledButton(
                        text = "Save",
                        onClick = {
                            onSave(tags, localCollections.filter { it.id in selected }.map { it.id })
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/* (dialog input removed — shared FolioTextField in ui/components covers it) */
/** Lists damaged docs; Remove deletes the entry's remaining files (reuses deleteDocument). */
@Composable
private fun DamagedDocsDialog(
    notice: LibraryUi.DamagedNotice,
    onRemove: (LibraryUi.DamagedItem) -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Damaged documents") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "These documents are missing files or metadata. Removing one deletes all of its remaining files.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                for (item in notice.items) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onRemove(item) }) { Text("Remove") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        }
    )
}

/** Lists interrupted imports; Finish re-registers the folder, Delete discards it. */
@Composable
private fun OrphanDocsDialog(
    notice: LibraryUi.OrphanNotice,
    onFinish: (LibraryUi.OrphanItem) -> Unit,
    onDelete: (LibraryUi.OrphanItem) -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Interrupted imports") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "These folders were left behind by an import that never finished. " +
                        "Finish adds the book back only when its reading file is present. " +
                        "Delete removes only that unfinished import's leftover files — " +
                        "your books, notes and progress are not affected.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                for (item in notice.items) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.problem?.let { problem ->
                                Text(
                                    "Can't finish — $problem",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (item.finishable) {
                            TextButton(onClick = { onFinish(item) }) { Text("Finish") }
                        }
                        TextButton(onClick = { onDelete(item) }) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        }
    )
}

/**
 * Search mode (M4): in-memory MiniSearchPort over per-chapter searchText;
 * results grouped by document, chapter rows jump into the reader at the hit's
 * spine position. Index rebuilds when [libraryVersion] changes.
 */
@Composable
private fun SearchPanel(
    libraryVersion: Int,
    initialLoading: Boolean,
    recoveryCount: Int,
    onReviewRecovery: () -> Unit,
    onOpenHit: (docId: String, targetOrder: Int) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<SearchService.Session?>(null) }
    var building by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchService.DocResult>>(emptyList()) }

    // Rebuild whenever the library changed (or on first open).
    var buildError by remember { mutableStateOf(false) }
    LaunchedEffect(libraryVersion) {
        building = true
        buildError = false
        // Audit P0-01: a corrupt manifest surfaces as an honest empty/error
        // state here instead of crashing the search coroutine.
        session = try {
            withContext(Dispatchers.IO) {
                SearchService(appLibrary(context)).build()
            }
        } catch (corrupt: LibraryFileCorruptException) {
            buildError = true
            null
        }
        building = false
    }

    LaunchedEffect(query, session) {
        searching = true
        delay(250) // debounce keystrokes
        val s = session
        results = if (query.isBlank() || s == null) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) { s.search(query) }
        }
        searching = false
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = FolioSpacing.x4, end = FolioSpacing.x2)
                .padding(vertical = FolioSpacing.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolioSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search title, author or text…",
                modifier = Modifier.weight(1f),
                focusRequester = focusRequester,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            FolioIconButton(
                icon = FolioIcons.Close,
                contentDescription = "Close search",
                onClick = onClose,
            )
        }

        if (recoveryCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onReviewRecovery)
                    .padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
            ) {
                Icon(
                    FolioIcons.AlertTriangle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(FolioDimensions.iconSmall),
                )
                Text(
                    "Needs attention · $recoveryCount",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = FolioSpacing.x2),
                )
                Text(
                    "Review",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        when {
            building || initialLoading -> {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            buildError -> {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Library index needs repair — search is unavailable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
            query.isBlank() -> {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Find chapters across your whole library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
            results.isEmpty() && !searching -> {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No results for “$query”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (result in results) {
                        item(key = result.group.docId) {
                            Column {
                                Text(
                                    result.group.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (result.group.author.isNotBlank()) {
                                    Text(
                                        result.group.author,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        for (hit in result.group.hits) {
                            item(key = "${result.group.docId}:${hit.chapterId}") {
                                SearchHitRow(
                                    chapterTitle = hit.chapterTitle,
                                    snippetHtml = hit.snippet,
                                    onClick = {
                                        onOpenHit(result.group.docId, result.chapterOrders[hit.chapterId] ?: 0)
                                    }
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

/** One chapter hit: chapter title + highlighted snippet, spans via SearchUi.parts. */
@Composable
private fun SearchHitRow(
    chapterTitle: String,
    snippetHtml: String,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val snippet = remember(snippetHtml, primary) {
        buildAnnotatedString {
            for (part in SearchUi.parts(snippetHtml)) {
                append(part.text)
                if (part.match) {
                    addStyle(
                        SpanStyle(
                            fontWeight = FontWeight.SemiBold,
                            color = primary
                        ),
                        length - part.text.length,
                        length
                    )
                }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(
            chapterTitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            snippet,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Selection mode top bar (long-press shelf): count as the title, close stops
 * selecting, overflow offers select-all/clear over the *visible* list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(FolioIcons.Close, contentDescription = "Stop selecting")
            }
        },
        title = { Text("$selectedCount selected") },
        actions = {
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(FolioIcons.MoreVertical, contentDescription = "Selection actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Select all") }, onClick = {
                        menuOpen = false
                        onSelectAll()
                    })
                    DropdownMenuItem(text = { Text("Clear selection") }, onClick = {
                        menuOpen = false
                        onClear()
                    })
                }
            }
        },
    )
}

/**
 * Selection action bar: Add to collection always; Remove from the filtered
 * collection only when the current filter is a collection (removal is only
 * meaningful relative to a shelf — books stay in the library either way).
 */
@Composable
private fun SelectionActionBar(
    selectedIds: Set<String>,
    showRemove: Boolean,
    onAddToCollection: () -> Unit,
    onRemove: () -> Unit,
) {
    if (showRemove) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
        ) {
            dev.folio.mobile.ui.components.FolioOutlinedButton(
                text = "Remove",
                enabled = selectedIds.isNotEmpty(),
                onClick = onRemove,
                modifier = Modifier.weight(1f),
            )
            dev.folio.mobile.ui.components.FolioFilledButton(
                text = "Add to collection",
                enabled = selectedIds.isNotEmpty(),
                onClick = onAddToCollection,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        dev.folio.mobile.ui.components.FolioFilledButton(
            text = "Add to collection",
            enabled = selectedIds.isNotEmpty(),
            onClick = onAddToCollection,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
        )
    }
}

/** Sorts library items by the chosen key. */
private fun sortLibrary(    items: List<LibraryUi.LibraryItem>,
    sortBy: String,
    progress: Map<String, dev.folio.mobile.core.model.Progress>
): List<LibraryUi.LibraryItem> = when (sortBy) {
    "title" -> items.sortedBy { it.title.lowercase() }
    "author" -> items.sortedBy { it.subtitle.lowercase() }
    "progress" -> items.sortedByDescending { it.percent ?: -1.0 }
    else -> LibraryUi.sort(items) // recent = in-progress first, then untouched, then finished
}

/** Stable placeholder spine tints (Folio palette-adjacent). */private val SPINE_TINTS = listOf(
    0xFF715CFC, 0xFF2F6F5E, 0xFF8A5A2B, 0xFF7FA8D0,
    0xFFD08FA8, 0xFF5B9E86, 0xFF9D86C8, 0xFFC88A5B
)

/**
 * Library row: flat editorial row (FolioBookRow) with a decoded cover (LRU-
 * cached in CoverLoader) or the designed placeholder. Menu: Details / Delete
 * only — opening is the row's tap (design §7.4); delete confirms upstream.
 */
@Composable
fun LibraryCard(
    item: LibraryUi.LibraryItem,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelect: (() -> Unit)? = null,
    selected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onAddToCollection: (() -> Unit)? = null,
    onRemoveFromCollection: (() -> Unit)? = null,
) {
    FolioBookRow(
        title = item.title,
        subtitle = item.subtitle,
        kindLabel = item.kindLabel,
        colorSeed = item.colorSeed,
        coverPath = item.cover,
        percent = item.percent,
        percentLabel = item.percentLabel,
        onOpen = onOpen,
        onDetails = onDetails,
        onDelete = onDelete,
        onToggleSelect = onToggleSelect,
        selected = selected,
        onLongPress = onLongPress,
        onAddToCollection = onAddToCollection,
        onRemoveFromCollection = onRemoveFromCollection,
    )
}

