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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.library.LibraryManager
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

/** Notes list filters (design §B Notes): All / Highlights / Notes / Bookmarks. */
private enum class NotesFilter(val label: String) {
    All("All"),
    Highlights("Highlights"),
    Notes("Notes"),
    Bookmarks("Bookmarks"),
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var annotations by remember { mutableStateOf<List<Annotation>>(emptyList()) }
    var docs by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // id -> title
    var reloadKey by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(NotesFilter.All) }

    /** Delete confirm target (null = closed). */
    var deleteTarget by remember { mutableStateOf<Annotation?>(null) }

    /** Edit-note target (null = closed). */
    var editTarget by remember { mutableStateOf<Annotation?>(null) }

    fun reload() {
        reloadKey++
    }

    LaunchedEffect(reloadKey) {
        withContext(Dispatchers.IO) {
            val library = LibraryManager(AppFileStore(context.filesDir))
            val all = library.getAnnotations()
            val titles = library.listDocuments().associate { it.id to it.title }
            // Newest first (updatedAt, falling back to createdAt).
            annotations = all.sortedByDescending { a -> (a.updatedAt.ifBlank { null } ?: a.createdAt) }
            docs = titles
        }
    }

    val shown = remember(annotations, filter) {
        when (filter) {
            NotesFilter.All -> annotations
            NotesFilter.Highlights -> annotations.filter {
                it.kind == AnnotationKind.Highlight || it.kind == AnnotationKind.Note
            }
            NotesFilter.Notes -> annotations.filter { !it.note.isNullOrBlank() }
            NotesFilter.Bookmarks -> annotations.filter { it.kind == AnnotationKind.Bookmark }
        }
    }

    Scaffold(
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
            if (annotations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    FolioEmptyState(
                        icon = FolioIcons.NotebookPen,
                        title = "Nothing saved yet",
                        body = "Highlights, notes and bookmarks you make while reading appear here.",
                        ctaLabel = "Open your library",
                        onCta = { context.startActivity(Intent(context, MainActivity::class.java)) },
                    )
                }
            } else {
                FilterRow(filter) { picked ->
                    filter = picked
                }
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
                                onEdit = { editTarget = a },
                                onDelete = { deleteTarget = a },
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
            title = { Text("Delete?") },
            text = {
                Text(
                    if (target.kind == AnnotationKind.Bookmark) "Remove this bookmark?"
                    else "Remove this " + target.kind.name.lowercase() +
                        (if (target.note.isNullOrBlank()) "?" else " and its note?")
                )
            },
            confirmButton = {
                FolioDestructiveTextButton(
                    text = "Delete",
                    onClick = {
                        deleteTarget = null
                        scope.launch(Dispatchers.IO) {
                            LibraryManager(AppFileStore(context.filesDir)).removeAnnotation(target.id)
                            withContext(Dispatchers.Main) { reload() }
                        }
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    editTarget?.let { target ->
        EditNoteDialog(
            initial = target.note.orEmpty(),
            hasNote = target.kind == AnnotationKind.Note || !target.note.isNullOrBlank(),
            onSave = { note ->
                editTarget = null
                val updated = target.copy(
                    note = note.ifBlank { null },
                    updatedAt = Instant.now().toString()
                )
                scope.launch(Dispatchers.IO) {
                    LibraryManager(AppFileStore(context.filesDir)).saveAnnotation(updated)
                    withContext(Dispatchers.Main) { reload() }
                }
            },
            onDelete = {
                editTarget = null
                deleteTarget = target
            },
            onDismiss = { editTarget = null }
        )
    }
}

/** One line of pill filters; selection = filled accent pill (§7.9). */
@Composable
private fun FilterRow(selected: NotesFilter, onSelect: (NotesFilter) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
    ) {
        NotesFilter.entries.forEach { f ->
            val isOn = f == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (isOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(f) }
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
 * quoted passage as the primary text, the attached note underneath, then the
 * kind. Whole row opens the book at the saved location; edit/delete sit at the
 * end with full-size touch targets.
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
            .clickable { onOpen() }
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
        Column(modifier = Modifier.weight(1f)) {
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
                    modifier = Modifier.padding(top = FolioSpacing.x1),
                )
            }
            annotation.note?.takeIf { it.isNotBlank() }?.let { n ->
                Text(
                    n,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = FolioSpacing.x1),
                )
            }
            Text(
                annotation.kind.name.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = FolioSpacing.x2),
            )
        }
        FolioIconButton(
            icon = FolioIcons.PenLine,
            contentDescription = "Edit note",
            onClick = onEdit,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FolioIconButton(
            icon = FolioIcons.Trash,
            contentDescription = "Delete",
            onClick = onDelete,
            tint = MaterialTheme.colorScheme.error,
        )
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
 * Edit dialog for an annotation's note. For a plain highlight (no note + not a
 * Note-kind record), Save with an empty field would be a no-op, so Delete is
 * offered inline; bookmarks just get Save/Cancel.
 */
@Composable
private fun EditNoteDialog(
    initial: String,
    hasNote: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Your note\u2026") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (hasNote) {
                    FolioDestructiveTextButton(text = "Delete", onClick = onDelete)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
