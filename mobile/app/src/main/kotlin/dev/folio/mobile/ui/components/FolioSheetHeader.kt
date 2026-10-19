package dev.folio.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.FolioSpacing

/**
 * One standard modal-bottom-sheet header (§7.8): title, optional subtitle,
 * close affordance on the right. Content inset is [FolioSpacing.x6] per §7.8.
 */
@Composable
fun FolioSheetHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = FolioSpacing.x6,
                end = FolioSpacing.x2,
                top = FolioSpacing.x4,
                bottom = FolioSpacing.x2,
            ),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let { s ->
                Text(
                    s,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        FolioIconButton(
            icon = FolioIcons.Close,
            contentDescription = "Close",
            onClick = onClose,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Content wrapper for sheet bodies: standard 24dp horizontal inset (§7.8). */
@Composable
fun FolioSheetBody(
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = FolioSpacing.x6)) { content() }
}
