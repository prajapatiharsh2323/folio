package dev.folio.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import dev.folio.mobile.core.library.LibraryUi
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.reader.ReaderActivity
import dev.folio.mobile.ui.components.CoverSlot
import dev.folio.mobile.ui.components.FAN_DEPTH
import dev.folio.mobile.ui.components.FolioBookRow
import dev.folio.mobile.ui.components.FolioCollectionRow
import dev.folio.mobile.ui.components.FolioCoverFan
import dev.folio.mobile.ui.components.FolioDestructiveTextButton
import dev.folio.mobile.ui.components.FolioEmptyState
import dev.folio.mobile.ui.components.FolioIconButton
import dev.folio.mobile.ui.components.FolioOutlinedButton
import dev.folio.mobile.ui.components.PickableBook
import dev.folio.mobile.ui.components.BookPickerSheet
import dev.folio.mobile.ui.components.CreateCollectionSheet
import dev.folio.mobile.ui.components.RenameCollectionSheet
import dev.folio.mobile.ui.components.relativeUpdateLabel
import dev.folio.mobile.ui.theme.FolioDimensions
import dev.folio.mobile.ui.theme.FolioSpacing
import dev.folio.mobile.ui.icons.FolioIcons
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Collections gallery (v2 collections phase): one panel, two levels —
 *  1. the list: fan preview + count + "Updated …" per collection;
 *  2. the detail: name/count header, Add books (search + multi-select
 *     picker), and the member rows with Remove/Details/Delete.
 *
 * Persistence goes through [appLibrary] exactly like the library screen: no
 * repository split, but every write keeps the PWA dual-write invariant
 * (collection.docIds ↔ doc meta.collectionIds), which LibraryManager owns.
 */
@Composable
fun CollectionsPanel(
    collections: List<Collection>,
    entries: List<LibraryUi.LibraryItem>,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onLibraryChanged: () -> Unit,
    onDocDetails: (LibraryUi.LibraryItem) -> Unit,
    onDocDelete: (LibraryUi.LibraryItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var openCollectionId by remember { mutableStateOf<String?>(null) }
    var createSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Collection?>(null) }
    var deleteCollectionTarget by remember { mutableStateOf<Collection?>(null) }
    var addBooksTarget by remember { mutableStateOf<Collection?>(null) }

    val open = openCollectionId?.let { id -> collections.firstOrNull { it.id == id } }
    // A collection deleted while its detail is open pops back to the list.
    LaunchedEffect(openCollectionId, collections) {
        if (openCollectionId != null && open == null) openCollectionId = null
    }

    suspend fun createCollection(name: String): Collection? {
        return try {
            val created = withIo { appLibrary(context).createCollection(name) }
            onLibraryChanged()
            snackbar.showSnackbar("Collection created")
            created
        } catch (t: Throwable) {
            snackbar.showSnackbar(t.message ?: "Create collection failed")
            null
        }
    }

    fun rename(id: String, name: String) {
        scope.launch {
            try {
                withIo { appLibrary(context).renameCollection(id, name) }
                onLibraryChanged()
                snackbar.showSnackbar("Collection renamed")
            } catch (t: Throwable) {
                snackbar.showSnackbar(t.message ?: "Rename failed")
            }
        }
    }

    fun deleteCollectionConfirm(target: Collection) {
        scope.launch {
            try {
                withIo { appLibrary(context).deleteCollection(target.id) }
                onLibraryChanged()
                snackbar.showSnackbar("Deleted \"${target.name}\"")
            } catch (t: Throwable) {
                snackbar.showSnackbar(t.message ?: "Delete failed")
            }
        }
    }

    fun addBooksTo(collection: Collection, docIds: List<String>) {
        if (docIds.isEmpty()) return
        scope.launch {
            try {
                withIo { appLibrary(context).addDocsToCollection(collection.id, docIds) }
                onLibraryChanged()
                snackbar.showSnackbar(
                    if (docIds.size == 1) "Added 1 book to \"${collection.name}\""
                    else "Added ${docIds.size} books to \"${collection.name}\""
                )
            } catch (t: Throwable) {
                snackbar.showSnackbar(t.message ?: "Add failed")
            }
        }
    }

    fun removeDoc(collection: Collection, docId: String) {
        scope.launch {
            try {
                val removedFrom = withIo {
                    appLibrary(context).removeDocsFromCollection(collection.id, listOf(docId))
                }
                onLibraryChanged()
                // Undo re-adds membership (picker Add semantics) — never deletes.
                val result = snackbar.showSnackbar(
                    "Removed from \"${collection.name}\"",
                    actionLabel = "Undo",
                )
                if (result == SnackbarResult.ActionPerformed) {
                    try {
                        withIo { appLibrary(context).addDocsToCollection(collection.id, listOf(docId)) }
                        onLibraryChanged()
                    } catch (t: Throwable) {
                        snackbar.showSnackbar(t.message ?: "Undo failed")
                    }
                }
            } catch (t: Throwable) {
                snackbar.showSnackbar(t.message ?: "Remove failed")
            }
        }
    }

    BackHandler {
        if (open != null) {
            openCollectionId = null
        } else {
            onBack()
        }
    }

    val byId = remember(entries) { entries.associateBy { it.id } }

    if (open == null) {
        CollectionsListLevel(
            collections = collections,
            entries = entries,
            onOpen = { openCollectionId = it.id },
            onCreate = { createSheet = true },
            onRename = { renameTarget = it },
            onDelete = { deleteCollectionTarget = it },
            onBack = onBack,
        )
    } else {
        CollectionDetailLevel(
            collection = open,
            memberEntries = open.docIds.mapNotNull(byId::get),
            libraryEmpty = entries.isEmpty(),
            onBack = { openCollectionId = null },
            onOpenDoc = { docId ->
                context.startActivity(ReaderActivity.createIntent(context, docId))
            },
            onDocDetails = onDocDetails,
            onDocDelete = onDocDelete,
            onAddBooks = { addBooksTarget = open },
            onRename = { renameTarget = open },
            onDelete = { deleteCollectionTarget = open },
            onRemoveDoc = { docId -> removeDoc(open, docId) },
        )
    }

    if (createSheet) {
        CreateCollectionSheet(
            onCreate = ::createCollection,
            onDismiss = { createSheet = false },
        )
    }

    renameTarget?.let { target ->
        RenameCollectionSheet(
            currentName = target.name,
            onSave = { clean -> rename(target.id, clean) },
            onDismiss = { renameTarget = null },
        )
    }

    deleteCollectionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteCollectionTarget = null },
            title = { Text("Delete collection?") },
            text = {
                Text("\"${target.name}\" will be removed. Its documents stay in the library.")
            },
            confirmButton = {
                FolioDestructiveTextButton(
                    text = "Delete",
                    onClick = {
                        deleteCollectionTarget = null
                        deleteCollectionConfirm(target)
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteCollectionTarget = null }) { Text("Cancel") }
            },
        )
    }

    addBooksTarget?.let { target ->
        BookPickerSheet(
            books = entries
                .sortedBy { it.title.lowercase() }
                .map { item ->
                    PickableBook(
                        id = item.id,
                        title = item.title,
                        author = item.subtitle,
                        coverPath = item.cover,
                        seed = item.colorSeed,
                        alreadyAdded = item.id in target.docIds,
                    )
                },
            onConfirm = { bookIds -> addBooksTo(target, bookIds) },
            onDismiss = { addBooksTarget = null },
        )
    }
}

private suspend fun <T> withIo(block: suspend () -> T): T =
    withContext(Dispatchers.IO) { block() }

@Composable
private fun CollectionsListLevel(
    collections: List<Collection>,
    entries: List<LibraryUi.LibraryItem>,
    onOpen: (Collection) -> Unit,
    onCreate: () -> Unit,
    onRename: (Collection) -> Unit,
    onDelete: (Collection) -> Unit,
    onBack: () -> Unit,
) {
    val byId = remember(entries) { entries.associateBy { it.id } }
    Column(Modifier.fillMaxSize()) {
        CollectionsHeader(
            subtitle = when (collections.size) {
                0 -> "Group books your way"
                1 -> "1 collection"
                else -> "${collections.size} collections"
            },
            onCreate = onCreate,
            onClose = onBack,
        )
        if (collections.isEmpty() && entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FolioEmptyState(
                    icon = FolioIcons.Library,
                    title = "No books yet",
                    body = "Import books into your library first, then group them into collections.",
                )
            }
        } else if (collections.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FolioEmptyState(
                    icon = FolioIcons.Library,
                    title = "Group books into collections",
                    body = "Collections are your own shelves — reading lists, genres, courses. Books stay in the library either way.",
                    ctaLabel = "New collection",
                    onCta = onCreate,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = FolioSpacing.x1, bottom = FolioSpacing.x6),
            ) {
                itemsIndexed(collections, key = { _, coll -> coll.id }) { index, coll ->
                    FolioCollectionRow(
                        name = coll.name,
                        bookCount = coll.docIds.size,
                        updatedLabel = relativeUpdateLabel(coll.updatedAt),
                        covers = coverSlots(coll, byId),
                        onOpen = { onOpen(coll) },
                        onRename = { onRename(coll) },
                        onDelete = { onDelete(coll) },
                        divider = index != collections.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionDetailLevel(
    collection: Collection,
    memberEntries: List<LibraryUi.LibraryItem>,
    libraryEmpty: Boolean,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onDocDetails: (LibraryUi.LibraryItem) -> Unit,
    onDocDelete: (LibraryUi.LibraryItem) -> Unit,
    onAddBooks: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRemoveDoc: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = FolioSpacing.x1, end = FolioSpacing.x2, top = FolioSpacing.x3),
        ) {
            FolioIconButton(
                icon = FolioIcons.ArrowLeft,
                contentDescription = "Back to collections",
                onClick = onBack,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = FolioSpacing.x2),
            ) {
                Text(
                    collection.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (collection.docIds.size == 1) "1 book" else "${collection.docIds.size} books",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                FolioIconButton(
                    icon = FolioIcons.MoreVertical,
                    contentDescription = "Collection actions",
                    onClick = { menuOpen = true },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(FolioIcons.PenLine, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                FolioIcons.Trash,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }

        if (libraryEmpty) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FolioEmptyState(
                    icon = FolioIcons.Library,
                    title = "No books yet",
                    body = "Import books into your library first, then add them here.",
                )
            }
        } else if (memberEntries.isEmpty()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FolioEmptyState(
                    icon = FolioIcons.Library,
                    title = "This shelf is empty",
                    body = "Add books from your library — the collection keeps its add order.",
                    ctaLabel = "Add books",
                    onCta = onAddBooks,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = FolioSpacing.x1, bottom = FolioSpacing.x4),
            ) {
                item(key = "add-books") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x3),
                    ) {
                        FolioOutlinedButton(
                            text = "Add books",
                            onClick = onAddBooks,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                itemsIndexed(memberEntries, key = { _, item -> item.id }) { index, item ->
                    FolioBookRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        kindLabel = item.kindLabel,
                        colorSeed = item.colorSeed,
                        coverPath = item.cover,
                        percent = item.percent,
                        percentLabel = item.percentLabel,
                        onOpen = { onOpenDoc(item.id) },
                        onDetails = { onDocDetails(item) },
                        onDelete = { onDocDelete(item) },
                        onRemoveFromCollection = { onRemoveDoc(item.id) },
                        divider = index != memberEntries.lastIndex,
                    )
                }
            }
        }
    }
}

/** Covers of a collection's first members; list order = add order. */
private fun coverSlots(
    coll: Collection,
    byId: Map<String, LibraryUi.LibraryItem>,
): List<CoverSlot> =
    coll.docIds
        .mapNotNull(byId::get)
        .take(FAN_DEPTH)
        .map { CoverSlot(coverPath = it.cover, seed = it.colorSeed, title = it.title) }

@Composable
private fun CollectionsHeader(
    subtitle: String,
    onCreate: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = FolioDimensions.screenInset,
                end = FolioSpacing.x2,
                top = FolioSpacing.x3,
            ),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Collections",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FolioIconButton(
            icon = FolioIcons.Plus,
            contentDescription = "New collection",
            onClick = onCreate,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FolioIconButton(
            icon = FolioIcons.Close,
            contentDescription = "Back to library",
            onClick = onClose,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
