package dev.folio.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.reader.ReaderActivity
import dev.folio.mobile.ui.theme.FolioMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var annotations by remember { mutableStateOf<List<Annotation>>(emptyList()) }
    var docs by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // id -> title
    var reloadKey by remember { mutableStateOf(0) }

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

    Scaffold(
        topBar = { TopAppBar(title = { Text("Notes") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (annotations.isEmpty()) {
                Text("No notes or highlights yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(annotations, key = { it.id }) { a ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        context.startActivity(ReaderActivity.createIntent(context, a.docId))
                                    }
                            ) {
                                Text(
                                    docs[a.docId] ?: a.docId,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (a.quote.isNotBlank()) {
                                    Text(
                                        "\u201C${a.quote}\u201D",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                a.note?.takeIf { it.isNotBlank() }?.let { n ->
                                    Text(
                                        n,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                Text(
                                    a.kind.name.lowercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { editTarget = a }) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "Edit note",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                IconButton(onClick = { deleteTarget = a }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete note",
                                        tint = Color(0xFFCC6650)
                                    )
                                }
                            }
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
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch(Dispatchers.IO) {
                        LibraryManager(AppFileStore(context.filesDir)).removeAnnotation(target.id)
                        withContext(Dispatchers.Main) { reload() }
                    }
                }) { Text("Delete") }
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
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
