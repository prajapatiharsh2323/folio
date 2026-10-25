package dev.folio.mobile.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.HighlightColor
import dev.folio.mobile.ui.components.FolioFilledButton
import dev.folio.mobile.ui.components.FolioSheetBody
import dev.folio.mobile.ui.components.FolioSheetHeader
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.FolioDimensions
import dev.folio.mobile.ui.theme.FolioSpacing

/**
 * Annotation sheets (R3).
 *
 * Both the "new selection" and "existing highlight" surfaces use the same calm
 * layout: one sheet header, a clamped quote preview, accessible colour swatches,
 * a multiline note field, and one primary save action. Saving is the only path
 * that persists; callers keep the sheet open until storage succeeds.
 */

/** PWA tokens.css HIGHLIGHT_COLORS — same hex as the PWA highlight painter. */
val ANNOTATION_CHIP_ARGBS: Map<HighlightColor, Long> = mapOf(
    HighlightColor.Yellow to 0xFFFFE58A,
    HighlightColor.Green to 0xFFB7E4B0,
    HighlightColor.Blue to 0xFFA9D1F5,
    HighlightColor.Pink to 0xFFF5B7CC
)

private val HIGHLIGHT_NAMES: Map<HighlightColor, String> = mapOf(
    HighlightColor.Yellow to "Yellow",
    HighlightColor.Green to "Green",
    HighlightColor.Blue to "Blue",
    HighlightColor.Pink to "Pink",
)

/** The persistent record's colour, defaulting to yellow when absent. */
private fun Annotation?.highlightColor(): HighlightColor =
    this?.color ?: HighlightColor.Yellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationMenuSheet(
    annotation: Annotation,
    saving: Boolean,
    error: String?,
    onSave: (HighlightColor, String?) -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val initialNote = annotation.note.orEmpty()
        var color by remember(annotation.id) { mutableStateOf(annotation.highlightColor()) }
        var note by remember(annotation.id) { mutableStateOf(initialNote) }
        val title = if (initialNote.isNotBlank() || note.isNotBlank()) "Edit note" else "Edit highlight"

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = FolioSpacing.x6),
        ) {
            FolioSheetHeader(title = title, onClose = onDismiss)
            FolioSheetBody {
                QuotePreview(annotation.quote)
                SectionLabel("Highlight colour")
                HighlightSwatches(selected = color, enabled = !saving, onSelect = { color = it })
                SectionLabel("Note")
                NoteField(
                    value = note,
                    enabled = !saving,
                    placeholder = "Add a note to this passage…",
                    onValueChange = { note = it },
                )
                if (initialNote.isNotBlank()) {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            note = ""
                            onSave(color, null)
                        },
                    ) { Text("Remove note") }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = FolioSpacing.x2),
                    )
                }
                if (saving) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FolioSpacing.x3),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FolioSpacing.x4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(enabled = !saving, onClick = onShare) { Text("Share") }
                    TextButton(enabled = !saving, onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
                    FolioFilledButton(
                        text = "Save",
                        enabled = !saving,
                        modifier = Modifier.padding(start = FolioSpacing.x2),
                        onClick = { onSave(color, note.ifBlank { null }) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionToolbarSheet(
    annotation: Annotation?,
    saving: Boolean,
    error: String?,
    onSave: (HighlightColor, String?) -> Unit,
    onCopy: () -> Unit,
    onMeaning: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var color by remember(annotation?.id) { mutableStateOf(annotation.highlightColor()) }
    var noteEditing by remember(annotation?.id) { mutableStateOf(false) }
    var noteText by remember(annotation?.id) { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = FolioSpacing.x6),
        ) {
            FolioSheetHeader(
                title = if (noteEditing) "Add note" else "Highlight",
                subtitle = annotation?.quote?.takeIf { it.isNotBlank() }
                    ?.let { "Selected text" }
                    ?: "Select text on the page first",
                onClose = onDismiss,
            )
            FolioSheetBody {
                if (annotation == null) {
                    Text(
                        "Select a passage first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@FolioSheetBody
                }
                QuotePreview(annotation.quote)
                SectionLabel("Highlight colour")
                HighlightSwatches(selected = color, enabled = !saving, onSelect = { color = it })

                if (noteEditing) {
                    SectionLabel("Note")
                    NoteField(
                        value = noteText,
                        enabled = !saving,
                        placeholder = "Write your note…",
                        onValueChange = { noteText = it },
                    )
                }

                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = FolioSpacing.x2),
                    )
                }
                if (saving) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FolioSpacing.x3),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FolioSpacing.x4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (noteEditing) {
                        TextButton(enabled = !saving, onClick = { noteEditing = false }) {
                            Text("Cancel")
                        }
                        Spacer(Modifier.weight(1f))
                        FolioFilledButton(
                            text = "Save note",
                            enabled = !saving && noteText.isNotBlank(),
                            onClick = { onSave(color, noteText.trim().ifBlank { null }) },
                        )
                    } else {
                        TextButton(enabled = !saving, onClick = { noteEditing = true }) {
                            Text("Add note")
                        }
                        TextButton(enabled = !saving, onClick = onCopy) { Text("Copy") }
                        // D1: quiet dictionary lookup entry point beside Copy;
                        // wraps into the same handlers as the selection bar.
                        onMeaning?.let { meaning ->
                            TextButton(enabled = !saving, onClick = meaning) { Text("Meaning") }
                        }
                        Spacer(Modifier.weight(1f))
                        FolioFilledButton(
                            text = "Highlight",
                            enabled = !saving,
                            onClick = { onSave(color, null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotePreview(quote: String) {
    if (quote.isBlank()) return
    Text(
        "\u201C$quote\u201D",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            )
            .padding(FolioSpacing.x3),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = FolioSpacing.x4, bottom = FolioSpacing.x2),
    )
}

@Composable
private fun HighlightSwatches(
    selected: HighlightColor,
    enabled: Boolean,
    onSelect: (HighlightColor) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
        modifier = Modifier.selectableGroup(),
    ) {
        ANNOTATION_CHIP_ARGBS.forEach { (color, argb) ->
            val isSelected = color == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(FolioDimensions.touchTarget)
                    .semantics {
                        contentDescription = "${HIGHLIGHT_NAMES[color] ?: "Highlight"} highlight"
                    }
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(color) },
                    ),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(argb), CircleShape)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape,
                        ),
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = FolioIcons.Check,
                            contentDescription = null,
                            tint = Color(0xFF22251F),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteField(
    value: String,
    enabled: Boolean,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        placeholder = { Text(placeholder) },
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
}
