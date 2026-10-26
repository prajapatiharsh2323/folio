package dev.folio.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.annotation.AnnotationFilter
import dev.folio.mobile.core.annotation.filtered
import dev.folio.mobile.core.library.LibraryFileCorruptException
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.library.ReadResult
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.model.HighlightColor
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.reader.ReaderActivity
import dev.folio.mobile.ui.components.FolioDestructiveTextButton
import dev.folio.mobile.ui.components.FolioEmptyState
import dev.folio.mobile.ui.components.FolioIconButton
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.FolioMobileTheme
import dev.folio.mobile.ui.theme.FolioSpacing
import dev.folio.mobile.ui.theme.HighlightBlue
import dev.folio.mobile.ui.theme.HighlightGreen
import dev.folio.mobile.ui.theme.HighlightPink
import dev.folio.mobile.ui.theme.HighlightYellow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class NotesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FolioMobileTheme {
                NotesScreen()
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, NotesActivity::class.java)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var annotations by remember { mutableStateOf<List<Annotation>>(emptyList()) }
    var docs by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // id -> title
    var reloadKey by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(AnnotationFilter.All) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var mutating by remember { mutableStateOf(false) }
    var mutationError by remember { mutableStateOf<String?>(null) }

    /** Delete confirm target (null = closed). */
    var deleteTarget by remember { mutableStateOf<Annotation?>(null) }

    /** Edit-note target (null = closed). */
    var editTarget by remember { mutableStateOf<Annotation?>(null) }

    fun reload() {
        reloadKey++
    }

    fun showSnack(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    LaunchedEffect(reloadKey) {
        if (annotations.isEmpty()) loading = true
        loadError = null
        try {
            withContext(Dispatchers.IO) {
                val library = LibraryManager(AppFileStore(context.filesDir))
                // Audit P0-02: a corrupt annotations file is NOT an empty notes
                // list — it is an unreadable-file state with its own recovery copy.
                when (val state = library.readAnnotationsState()) {
                    is ReadResult.Loaded -> annotations =
                        state.value.sortedByDescending { a -> (a.updatedAt.ifBlank { null } ?: a.createdAt) }
                    is ReadResult.Missing -> annotations = emptyList()
                    is ReadResult.Corrupt -> throw LibraryFileCorruptException(state.path, state.message)
                }
                val titles = library.listDocuments().associate { it.id to it.title }
                docs = titles
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (corrupt: LibraryFileCorruptException) {
            loadError = when {
                corrupt.path == dev.folio.mobile.core.library.ANNOTATIONS_PATH ->
                    "Saved notes can't be read. The notes file was left untouched — " +
                        "try again, or restore your data from a backup."
                else ->
                    "The library index needs repair. Notes open again after it's fixed — nothing was changed."
            }
        } catch (error: Exception) {
            loadError = "Couldn't load your notes. Please try again."
        } finally {
            loading = false
        }
    }

    val shown = remember(annotations, filter) { annotations.filtered(filter) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Notes",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            when (annotations.size) {
                                0 -> "Nothing saved yet"
                                1 -> "1 item"
                                else -> "${annotations.size} items"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                loadError != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            loadError.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { reload() }) { Text("Try again") }
                    }
                }

                annotations.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    FolioEmptyState(
                        icon = FolioIcons.NotebookPen,
                        title = "Nothing saved yet",
                        body = "Highlights, notes and bookmarks you make while reading appear here.",
                        ctaLabel = "Open your library",
                        onCta = { context.startActivity(Intent(context, MainActivity::class.java)) },
                    )
                }

                else -> {
                    FilterRow(filter) { picked -> filter = picked }
                    if (shown.isEmpty()) {
                        Text(
                            "No ${filter.label.lowercase()} yet. Everything you save while reading will show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x8),
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(shown, key = { it.id }) { a ->
                                AnnotationRow(
                                    annotation = a,
                                    bookTitle = docs[a.docId] ?: a.docId,
                                    onOpen = {
                                        // EPUB-018 tie-in: jump back to the
                                        // annotation's saved location — chapter-
                                        // accurate for the href/CFI plus, when a
                                        // quote+context exists (highlights/notes),
                                        // a second text-quote anchored go() that
                                        // lands on the exact highlighted text.
                                        context.startActivity(
                                            ReaderActivity.createIntent(
                                                context, a.docId, a.location ?: "",
                                                a.prefix, a.quote, a.suffix
                                            )
                                        )
                                    },
                                    onEdit = { mutationError = null; editTarget = a },
                                    onDelete = { mutationError = null; deleteTarget = a },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!mutating) deleteTarget = null },
            title = { Text("Delete?") },
            text = {
                Column {
                    Text(
                        if (target.kind == AnnotationKind.Bookmark) "Remove this bookmark?"
                        else "Remove this highlight" +
                            (if (target.note.isNullOrBlank()) "?" else " and its note?")
                    )
                    mutationError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = FolioSpacing.x2),
                        )
                    }
                }
            },
            confirmButton = {
                FolioDestructiveTextButton(
                    text = if (mutating) "Deleting…" else "Delete",
                    enabled = !mutating,
                    onClick = {
                        if (mutating) return@FolioDestructiveTextButton
                        mutating = true
                        mutationError = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    LibraryManager(AppFileStore(context.filesDir)).removeAnnotation(target.id)
                                }
                                deleteTarget = null
                                reload()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (corrupt: LibraryFileCorruptException) {
                                // Audit P0-02: an unreadable notes file refuses
                                // the edit honestly instead of a free-space hint.
                                mutationError =
                                    "Saved notes can't be read, so nothing was deleted. " +
                                        "The notes file was left untouched — restore from a backup to recover it."
                            } catch (error: Exception) {
                                mutationError = "Couldn't delete this. Please try again."
                            } finally {
                                mutating = false
                            }
                        }
                    }
                )
            },
            dismissButton = {
                TextButton(enabled = !mutating, onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    editTarget?.let { target ->
        EditNoteDialog(
            initial = target.note.orEmpty(),
            hasNote = !target.note.isNullOrBlank(),
            saving = mutating,
            error = mutationError,
            onSave = { note ->
                if (mutating) return@EditNoteDialog
                mutating = true
                mutationError = null
                val updated = target.copy(
                    note = note.ifBlank { null },
                    updatedAt = Instant.now().toString()
                )
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            LibraryManager(AppFileStore(context.filesDir)).saveAnnotation(updated)
                        }
                        editTarget = null
                        reload()
                        showSnack(if (updated.note != null) "Note saved" else "Note removed")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (corrupt: LibraryFileCorruptException) {
                        // Audit P0-02: nothing was written and the unreadable
                        // file is preserved; the typed text stays in the dialog.
                        mutationError =
                            "Saved notes can't be read, so this edit wasn't saved. " +
                                "Your note text is kept here — restore from a backup to recover it."
                    } catch (error: Exception) {
                        mutationError = "Couldn't save your note. Please try again."
                    } finally {
                        mutating = false
                    }
                }
            },
            onDelete = {
                if (!mutating) {
                    editTarget = null
                    mutationError = null
                    deleteTarget = target
                }
            },
            onDismiss = { if (!mutating) editTarget = null }
        )
    }
}

/** One line of pill filters; selection = filled accent pill (§7.9). */
@Composable
private fun FilterRow(selected: AnnotationFilter, onSelect: (AnnotationFilter) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
    ) {
        AnnotationFilter.entries.forEach { f ->
            val isOn = f == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (isOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .selectable(selected = isOn, role = Role.Tab, onClick = { onSelect(f) })
                    .padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
            ) {
                Text(
                    f.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isOn) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Editorial quote row: a highlight-color rail, the book it came from, the
 * quoted passage as the primary text, then the attached note. Whole row opens
 * the book at the saved location; secondary actions live behind overflow so the
 * calm reading surface is not crowded with icon buttons.
 */
@Composable
private fun AnnotationRow(
    annotation: Annotation,
    bookTitle: String,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x5),
        verticalAlignment = Alignment.Top,
    ) {
        // Highlight color rail (neutral rail for bookmarks: nothing to colorize).
        Box(
            modifier = Modifier
                .padding(top = FolioSpacing.x1, end = FolioSpacing.x4)
                .width(3.dp)
                .height(44.dp)
                .background(
                    color = railColor(annotation),
                    shape = RoundedCornerShape(2.dp),
                )
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen),
        ) {
            Text(
                bookTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (annotation.quote.isNotBlank()) {
                Text(
                    "\u201C${annotation.quote}\u201D",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = FolioSpacing.x1),
                )
            }
            annotation.note?.takeIf { it.isNotBlank() }?.let { n ->
                Text(
                    n,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = FolioSpacing.x1),
                )
            }
            val meta = when {
                annotation.kind == AnnotationKind.Bookmark -> "Bookmark"
                !annotation.note.isNullOrBlank() -> "Has note"
                else -> null
            }
            meta?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = FolioSpacing.x2),
                )
            }
        }
        var menuOpen by remember(annotation.id) { mutableStateOf(false) }
        Box {
            FolioIconButton(
                icon = FolioIcons.MoreVertical,
                contentDescription = "More actions",
                onClick = { menuOpen = true },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Edit note") },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** Rail ink = the stored highlight swatch; bookmarks get a neutral rail. */
private fun railColor(annotation: Annotation) = when {
    annotation.kind == AnnotationKind.Bookmark -> HighlightColorRailNeutral
    else -> when (annotation.color) {
        HighlightColor.Green -> HighlightGreen
        HighlightColor.Blue -> HighlightBlue
        HighlightColor.Pink -> HighlightPink
        null, HighlightColor.Yellow -> HighlightYellow
    }
}

private val HighlightColorRailNeutral = Color(0xFFD8D5CC)

/**
 * Edit dialog for an annotation's note. Saving keeps the dialog open until the
 * storage call succeeds; failures show an inline message and keep the text.
 */
@Composable
private fun EditNoteDialog(
    initial: String,
    hasNote: Boolean,
    saving: Boolean,
    error: String?,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasNote) "Edit note" else "Add note") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Your note\u2026") },
                    minLines = 3,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = FolioSpacing.x2),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && text.isNotBlank(),
                onClick = { onSave(text) },
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = {
            Row {
                if (hasNote) {
                    FolioDestructiveTextButton(
                        text = "Remove note",
                        enabled = !saving,
                        onClick = { onSave("") },
                    )
                }
                TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
