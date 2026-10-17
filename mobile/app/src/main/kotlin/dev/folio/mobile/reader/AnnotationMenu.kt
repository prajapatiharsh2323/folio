package dev.folio.mobile.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.HighlightColor

/**
 * Annotation context menu (R3), opened from a tap on an existing highlight
 * decoration (`HighlightManager.onDecorationActivated`).
 *
 * Actions: recolor (4 PWA HIGHLIGHT_COLORS chips), note edit, delete, share
 * quote-card (R3 deliverable). Every mutation bumps `updatedAt` upstream and
 * persists through the schema-1 store; decorations re-apply via HighlightManager.
 */

/** PWA tokens.css HIGHLIGHT_COLORS — same hex as the PWA highlight painter. */
val ANNOTATION_CHIP_ARGBS: Map<HighlightColor, Long> = mapOf(
    HighlightColor.Yellow to 0xFFFFE58A,
    HighlightColor.Green to 0xFFB7E4B0,
    HighlightColor.Blue to 0xFFA9D1F5,
    HighlightColor.Pink to 0xFFF5B7CC
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationMenuSheet(
    annotation: Annotation,
    onColor: (HighlightColor) -> Unit,
    onSaveNote: (String) -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AnnotationMenuContent(annotation, onColor, onSaveNote, onDelete, onShare)
    }
}

@Composable
fun AnnotationMenuContent(
    annotation: Annotation,
    onColor: (HighlightColor) -> Unit,
    onSaveNote: (String) -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var note by remember(annotation.id) { mutableStateOf(annotation.note.orEmpty()) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(            text = annotation.quote,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x14171A2E), RoundedCornerShape(8.dp))
                .padding(10.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ANNOTATION_CHIP_ARGBS.forEach { (color, argb) ->
                Chip(argb, isSelected = annotation.color == color) {
                    onColor(color)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "Share quote card")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete annotation",
                    tint = Color(0xFFCC6650)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Note…") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { onSaveNote(note) },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun Chip(argb: Long, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 10.dp)
            .size(30.dp)
            .background(Color(argb), CircleShape)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFF2F6F5E) else Color(0x33171A2E),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}
