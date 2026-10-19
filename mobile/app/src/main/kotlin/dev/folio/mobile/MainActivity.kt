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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.importer.ImportDispatch
import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.importer.UnsupportedFormatException
import dev.folio.mobile.core.library.CoverLoader
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.library.LibraryUi
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.search.SearchService
import dev.folio.mobile.core.search.SearchUi
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.BuildConfig
import dev.folio.mobile.reader.ReaderActivity
import dev.folio.mobile.ui.theme.FolioMobileTheme
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

private fun appLibrary(context: android.content.Context): LibraryManager =
    LibraryManager(AppFileStore(context.filesDir))

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
    var manageCollectionsOpen by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("recent") }
    var duplicateResult by remember { mutableStateOf<ImportResult?>(null) }
    var duplicateDialogOpen by remember { mutableStateOf(false) }

    // Any library mutation bumps the version so the search index rebuilds.
    var libraryVersion by remember { mutableStateOf(0) }

    suspend fun refresh() {
        val library = appLibrary(context)
        val docs = withContext(Dispatchers.IO) { library.listDocuments() }
        val progress = withContext(Dispatchers.IO) { library.getAllProgress() }
        entries = sortLibrary(docs.map { LibraryUi.item(it, progress[it.id]) }, sortBy, progress)
        // Recovery state (spec §4 Rule 2): damaged entries + interrupted imports.
        withContext(Dispatchers.IO) {
            damagedNotice = LibraryUi.damagedNotice(library.damagedDocuments())
            orphanNotice = LibraryUi.orphanNotice(library.scanInterruptedImports())
        }
        collections = withContext(Dispatchers.IO) { library.getCollections() }
        // Re-bind the active filter: renames keep it, deleted targets drop it.
        activeFilter = LibraryUi.rebind(activeFilter, LibraryUi.filters(collections, entries))
        libraryVersion += 1
        loading = false
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

    fun runImport(uri: Uri) {
        scope.launch {
            importing = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("Cannot read the selected file")
                }
                if (bytes.size > 50 * 1024 * 1024) {
                    throw IOException("File too large (max 50 MB)")
                }
                val displayName = uri.lastPathSegment?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() } ?: "import"
                val result = ImportDispatch.import(bytes, displayName)
                val library = appLibrary(context)
                val dup = withContext(Dispatchers.IO) { library.findDuplicate(result.meta.contentHash) }
                if (dup != null) {
                    duplicateResult = result
                    duplicateDialogOpen = true
                } else {
                    withContext(Dispatchers.IO) { library.addDocument(result) }
                    refresh()
                    snackbar.showSnackbar("Imported")
                }
            } catch (e: UnsupportedFormatException) {
                snackbar.showSnackbar("Unsupported format — EPUB, HTML or TXT only")
            } catch (t: Throwable) {
                snackbar.showSnackbar(t.message ?: "Import failed")
            } finally {
                importing = false
            }
        }
    }

    // Shared file (ACTION_VIEW) handled once per composition instance.
    if (sharedFileUri != null) {
        LaunchedEffect(sharedFileUri) { runImport(sharedFileUri) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::runImport) }

    val filterOptions = LibraryUi.filters(collections, entries)
    val visibleEntries = LibraryUi.applyFilter(entries, activeFilter)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folio") },
                actions = {
                    Box {
                        var sortMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false }
                        ) {
                            listOf("recent", "title", "author", "progress").forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(if (sortBy == key) "• $key" else key) },
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
                        enabled = !importing && !loading,
                        onClick = { searchActive = true }
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(
                        enabled = !importing,
                        onClick = { importLauncher.launch(MainActivity.IMPORT_MIMES) }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Import file")
                    }
                    Box {
                        var topMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { topMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(
                            expanded = topMenuOpen,
                            onDismissRequest = { topMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Manage collections") },
                                onClick = {
                                    topMenuOpen = false
                                    manageCollectionsOpen = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    topMenuOpen = false
                                    context.startActivity(SettingsActivity.createIntent(context))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Notes") },
                                onClick = {
                                    topMenuOpen = false
                                    context.startActivity(NotesActivity.createIntent(context))
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchActive) {
                SearchPanel(
                    libraryVersion = libraryVersion,
                    initialLoading = loading,
                    onOpenHit = { docId, order ->
                        context.startActivity(
                            ReaderActivity.createIntent(context, docId, order)
                        )
                    },
                    onClose = { searchActive = false }
                )
            } else {
                damagedNotice?.let { notice ->
                    RecoveryBanner(notice.bannerText, onReview = { damagedDialogOpen = true })
                }
                orphanNotice?.let { notice ->
                    RecoveryBanner(notice.bannerText, onReview = { orphanDialogOpen = true })
                }
                if (filterOptions.isNotEmpty()) {
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
                } else if (entries.isEmpty()) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (importing) {
                                    "Importing…"
                                } else {
                                    "Library is empty.\nAdd a book or article with the + button."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 32.dp)
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
                    }
                } else if (visibleEntries.isEmpty()) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No documents match this filter.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                onDelete = { deleteTarget = item }
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete “${target.title}”?") },
            text = {
                Text("Highlights and reading progress for this document are removed too.")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                appLibrary(context).deleteDocument(target.id)
                            }
                            refresh()
                        } catch (t: Throwable) {
                            snackbar.showSnackbar(t.message ?: "Delete failed")
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Recovery dialog (spec §4 Rule 2): remove damaged docs or dismiss.
    damagedNotice?.takeIf { damagedDialogOpen }?.let { notice ->
        DamagedDocsDialog(
            notice = notice,
            onRemove = { item ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            appLibrary(context).deleteDocument(item.id)
                        }
                        refresh()
                        if (damagedNotice == null) damagedDialogOpen = false
                        snackbar.showSnackbar("Removed “${item.label}”")
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
        DetailsDialog(
            item = target,
            collections = collections,
            onSave = { tags, collectionIds ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val library = appLibrary(context)
                            library.setDocCollections(target.id, collectionIds)
                            library.updateDocMeta(target.id, tags = tags)
                        }
                        detailsTarget = null
                        refresh()
                        snackbar.showSnackbar("Saved")
                    } catch (t: Throwable) {
                        snackbar.showSnackbar(t.message ?: "Save failed")
                    }
                }
            },
            onCreateCollection = { name ->
                withContext(Dispatchers.IO) {
                    try {
                        appLibrary(context).createCollection(name)
                    } catch (t: Throwable) {
                        // Surface it (the dialog swallows the rethrow as before).
                        snackbar.showSnackbar(t.message ?: "Create collection failed")
                        throw t
                    }
                }
            },
            onDismiss = { detailsTarget = null }
        )
    }

    // Duplicate import prompt (spec §4 Rule 3).
    if (duplicateDialogOpen) {
        AlertDialog(
            onDismissRequest = {
                duplicateDialogOpen = false
                duplicateResult = null
            },
            title = { Text("Already in library") },
            text = { Text("This document's content already exists. What do you want to do?") },
            confirmButton = {
                TextButton(onClick = {
                    duplicateDialogOpen = false
                    val r = duplicateResult
                    duplicateResult = null
                    if (r != null) {
                        scope.launch {
                            try {
                                val library = appLibrary(context)
                                withContext(Dispatchers.IO) { library.addDocument(r) }
                                refresh()
                                snackbar.showSnackbar("Imported as new copy")
                            } catch (t: Throwable) {
                                snackbar.showSnackbar(t.message ?: "Import failed")
                            }
                        }
                    }
                }) { Text("Keep both") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        duplicateDialogOpen = false
                        duplicateResult = null
                    }) { Text("Skip") }
                    TextButton(onClick = {
                        duplicateDialogOpen = false
                        val r = duplicateResult
                        duplicateResult = null
                        if (r != null) {
                            scope.launch {
                                try {
                                    val library = appLibrary(context)
                                    val dup = withContext(Dispatchers.IO) { library.findDuplicate(r.meta.contentHash) }
                                    if (dup != null) {
                                        withContext(Dispatchers.IO) { library.deleteDocument(dup.id) }
                                        withContext(Dispatchers.IO) { library.addDocument(r) }
                                    }
                                    refresh()
                                    snackbar.showSnackbar("Replaced")
                                } catch (t: Throwable) {
                                    snackbar.showSnackbar(t.message ?: "Import failed")
                                }
                            }
                        }
                    }) { Text("Replace") }
                }
            }
        )
    }

    // Collections manager: rename + delete (creation lives in the details dialog).
    if (manageCollectionsOpen) {
        ManageCollectionsDialog(
            collections = collections,
            onRename = { id, name ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            appLibrary(context).renameCollection(id, name)
                        }
                        refresh()
                        snackbar.showSnackbar("Collection renamed")
                    } catch (t: Throwable) {
                        snackbar.showSnackbar(t.message ?: "Rename failed")
                    }
                }
            },
            onDelete = { id ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            appLibrary(context).deleteCollection(id)
                        }
                        refresh()
                        snackbar.showSnackbar("Collection deleted")
                    } catch (t: Throwable) {
                        snackbar.showSnackbar(t.message ?: "Delete failed")
                    }
                }
            },
            onDismiss = { manageCollectionsOpen = false }
        )
    }
}

/** Collections manager: per-row rename/delete (delete keeps doc meta ids — PWA parity). */
@Composable
private fun ManageCollectionsDialog(
    collections: List<Collection>,
    onRename: (id: String, name: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onDismiss: () -> Unit
) {
    var renameTarget by remember { mutableStateOf<Collection?>(null) }
    var deleteTarget by remember { mutableStateOf<Collection?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            if (collections.isEmpty()) {
                Text(
                    "No collections yet — add one from a book's Details dialog.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column {
                    for (coll in collections) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    coll.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val count = coll.docIds.size
                                Text(
                                    if (count == 1) "1 document" else "$count documents",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            IconButton(onClick = { renameTarget = coll }) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "Rename ${coll.name}"
                                )
                            }
                            IconButton(onClick = { deleteTarget = coll }) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "Delete ${coll.name}"
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )

    renameTarget?.let { target ->
        RenameCollectionDialog(
            collection = target,
            onSave = { name ->
                renameTarget = null
                onRename(target.id, name)
            },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete collection?") },
            text = {
                Text(
                    "\"${target.name}\" will be removed. Its documents stay in the library."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(target.id)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

/** Rename sub-dialog: prefilled field; blank or unchanged names are rejected. */
@Composable
private fun RenameCollectionDialog(
    collection: Collection,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(collection.id) { mutableStateOf(collection.name) }
    val clean = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename collection") },
        text = {
            DialogTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Collection name…",
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = clean.isNotEmpty() && clean != collection.name,
                onClick = { onSave(clean) }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun filterLabel(filter: LibraryUi.LibraryFilter): String = when (filter) {
    is LibraryUi.LibraryFilter.TagFilter -> "#${filter.tag}"
    is LibraryUi.LibraryFilter.CollectionFilter -> filter.name
}

/** Details dialog (PWA DocumentSheet parity): tag chips + collection checkboxes + create. */
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
    var localCollections by remember(collections) { mutableStateOf(collections) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text("Tags", style = MaterialTheme.typography.labelLarge)
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
                                        Icons.Filled.Close,
                                        contentDescription = "Remove tag"
                                    )
                                }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DialogTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        placeholder = "Add a tag…",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        LibraryUi.normalizeTag(tagInput, tags)?.let { clean ->
                            tags = tags + clean
                            tagInput = ""
                        }
                    }) { Text("Add") }
                }

                Text("Collections", style = MaterialTheme.typography.labelLarge)
                if (localCollections.isEmpty()) {
                    Text(
                        "No collections yet — create one below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                } else {
                    Column {
                        for (coll in localCollections) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = coll.id in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + coll.id
                                        else selected - coll.id
                                    }
                                )
                                Text(
                                    coll.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DialogTextField(
                        value = newCollName,
                        onValueChange = { newCollName = it },
                        placeholder = "New collection…",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val name = newCollName.trim()
                        if (name.isNotEmpty()) {
                            scope.launch {
                                try {
                                    val created = onCreateCollection(name)
                                    localCollections = localCollections + created
                                    selected = selected + created.id
                                    newCollName = ""
                                } catch (_: Throwable) {
                                    // Surfaced upstream via the snackbar in onCreateCollection.
                                }
                            }
                        }
                    }) { Text("Create") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(tags, localCollections.filter { it.id in selected }.map { it.id })
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Small filled input used inside dialogs (BasicTextField + placeholder). */
@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

/** Recovery banner: damaged entries / interrupted imports, never silently dropped (spec §4 Rule 2). */
@Composable
private fun RecoveryBanner(bannerText: String, onReview: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                bannerText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            )
            TextButton(onClick = onReview) { Text("Review") }
        }
    }
}

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
                    "These folders were left behind by an import that never finished.",
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
    LaunchedEffect(libraryVersion) {
        building = true
        session = withContext(Dispatchers.IO) {
            SearchService(appLibrary(context)).build()
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                "Search title, author or text…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                        inner()
                    }
                }
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close search")
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

/** Sorts library items by the chosen key. */
private fun sortLibrary(
    items: List<LibraryUi.LibraryItem>,
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
 * Library card: tinted spine placeholder keyed off the title, title/author/kind
 * and (when previously opened) a progress bar with a % label. Three-dot menu:
 * Open / Details (tags + collections) / Delete (delete waits for a confirm
 * dialog upstream).
 */
@Composable
fun LibraryCard(
    item: LibraryUi.LibraryItem,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Cover thumbnail: decoded off the main thread, LRU-cached in CoverLoader.
    // remember(item.cover) resets to null and LaunchedEffect restarts per key,
    // producing the same reset-and-redecode semantics produceState(key1 =…)
    // would give. (Written as remember+LaunchedEffect because the Kotlin-2
    // UAST bridge keeps failing to resolve `value =` in produceState producer
    // lambdas, which produceState's own lint check then wrongly flags.)
    val context = LocalContext.current
    val store = remember { AppFileStore(context.filesDir) }
    var coverBitmap by remember(item.cover) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(item.cover) {
        val rel = item.cover ?: return@LaunchedEffect
        coverBitmap = withContext(Dispatchers.IO) {
            CoverLoader.decode(store, rel)?.asImageBitmap()
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bmp = coverBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(44.dp)
                        .height(58.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(58.dp)
                        .background(
                            Color(SPINE_TINTS[item.colorSeed and 7]),
                            RoundedCornerShape(3.dp)
                        )
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.percent?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { (p / 100.0).toFloat() },
                            modifier = Modifier.weight(1f).height(4.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.percentLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Details") },
                        leadingIcon = {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onDetails()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
