package dev.folio.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.mobile.ui.theme.FolioDimensions
import dev.folio.mobile.ui.theme.FolioSpacing

/** Settings/list section heading (§7.10) — sentence case, generous top space. */
@Composable
fun FolioSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    inset: Dp = FolioDimensions.screenInset,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = FolioSpacing.x6, bottom = FolioSpacing.x2, start = inset, end = inset),
    )
}

/**
 * Empty/first-run state (§7.11): one icon, one heading, one short explanation,
 * one primary CTA when there is a useful next step. Kept compact so it stays
 * above the fold on small phones.
 */
@Composable
fun FolioEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    ctaLabel: String? = null,
    onCta: (() -> Unit)? = null,
    note: String? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = FolioSpacing.x8),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = FolioSpacing.x5).size(40.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = FolioSpacing.x3),
        )
        if (ctaLabel != null && onCta != null) {
            FolioFilledButton(
                text = ctaLabel,
                onClick = onCta,
                modifier = Modifier.padding(top = FolioSpacing.x6),
            )
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = FolioSpacing.x4),
            )
        }
    }
}
