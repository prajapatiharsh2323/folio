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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.importer.ImportDispatch
import dev.folio.mobile.core.importer.UnsupportedFormatException
import dev.folio.mobile.core.library.CoverLoader
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.library.LibraryUi
import dev.folio.mobile.core.search.SearchService
import dev.folio.mobile.core.search.SearchUi
import dev.folio.mobile.core.store.AppFileStore
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
        val IMPORT_MIMES = arrayOf(
            "application/epub+zip",
            "application/pdf",
            "application/octet-stream",
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

/**
 * Reads the SAF uri, routes through [ImportDispatch] (content-based detection),
 * and commits unless the same bytes are already in the library.
 * Returns false ONLY for the duplicate case; throws for read/import failures.
 */
private suspend fun importFromUri(
    context: android.content.Context,
    uri: Uri
): Boolean = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IOException("Cannot read the selected file")
    val displayName = uri.lastPathSegment?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() } ?: "import"
    val result = ImportDispatch.import(bytes, displayName)
    val library = appLibrary(context)
    if (library.findDuplicate(result.meta.contentHash) != null) {
        false
    } else {
        library.addDocument(result)
        true
    }
}

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
        appLibrary(context).addDocument(
            dev.folio.mobile.core.importer.TxtImporter.import(
                sample.toByteArray(Charsets.UTF_8),
                "sample.txt"
            )
        )
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

    // Any library mutation bumps the version so the search index rebuilds.
    var libraryVersion by remember { mutableStateOf(0) }

    suspend fun refresh() {
        val library = appLibrary(context)
        val docs = withContext(Dispatchers.IO) { library.listDocuments() }
        val progress = withContext(Dispatchers.IO) { library.getAllProgress() }
        entries = LibraryUi.sort(docs.map { LibraryUi.item(it, progress[it.id]) })
        libraryVersion += 1
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    fun runImport(uri: Uri) {
        scope.launch {
            importing = true
            try {
                val added = importFromUri(context, uri)
                if (added) {
                    refresh()
                    snackbar.showSnackbar("Imported")
                } else {
                    snackbar.showSnackbar("Already in library — skipped")
                }
            } catch (e: UnsupportedFormatException) {
                snackbar.showSnackbar("Unsupported format — EPUB, PDF, HTML or TXT only")
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folio") },
                actions = {
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
            } else if (loading) {
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
                        if (!importing) {
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
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries, key = { it.id }) { item ->
                        LibraryCard(
                            item = item,
                            onOpen = {
                                context.startActivity(
                                    ReaderActivity.createIntent(context, item.id)
                                )
                            },
                            onDelete = { deleteTarget = item }
                        )
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

/** Stable placeholder spine tints (Folio palette-adjacent). */private val SPINE_TINTS = listOf(
    0xFF715CFC, 0xFF2F6F5E, 0xFF8A5A2B, 0xFF7FA8D0,
    0xFFD08FA8, 0xFF5B9E86, 0xFF9D86C8, 0xFFC88A5B
)

/**
 * Library card: tinted spine placeholder keyed off the title, title/author/kind
 * and (when previously opened) a progress bar with a % label. Three-dot menu:
 * Open / Delete (delete waits for a confirm dialog upstream).
 */
@Composable
fun LibraryCard(
    item: LibraryUi.LibraryItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Cover thumbnail: decoded off the main thread, LRU-cached in CoverLoader.
    val context = LocalContext.current
    val store = remember { AppFileStore(context.filesDir) }
    val coverBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        item.cover
    ) {
        value = item.cover?.let { rel ->
            withContext(Dispatchers.IO) {
                CoverLoader.decode(store, rel)?.asImageBitmap()
            }
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
                            progress = { p.toFloat() },
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
