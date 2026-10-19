package dev.folio.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.folio.mobile.ui.theme.extendedFolioColors
import dev.folio.mobile.ui.theme.FolioRadii
import dev.folio.mobile.ui.theme.FolioSpacing

/**
 * Recovery/needs-attention banner (§7.11 "recovery state"): semantic warning
 * tone, human-readable summary, explicit review action. Never silently hides
 * broken/interrupted documents; detailed review stays in the caller's dialog.
 */
@Composable
fun FolioStatusBanner(
    icon: ImageVector?,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val extended = extendedFolioColors()
    val tone = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        extended.warning
    }
    val container = if (destructive) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        tone.copy(alpha = 0.14f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(FolioRadii.card)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = FolioSpacing.x3, end = FolioSpacing.x1, top = FolioSpacing.x2, bottom = FolioSpacing.x2),
        ) {
            icon?.let { ic ->
                Icon(
                    imageVector = ic,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier
                        .padding(end = FolioSpacing.x2)
                        .size(20.dp),
                )
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = tone,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = FolioSpacing.x1),
            )
            TextButton(onClick = onAction) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = tone,
                )
            }
        }
    }
}
