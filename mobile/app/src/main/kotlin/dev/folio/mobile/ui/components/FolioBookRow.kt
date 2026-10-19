package dev.folio.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.CoverInk
import dev.folio.mobile.ui.theme.CoverTints
import dev.folio.mobile.ui.theme.FolioDimensions
import dev.folio.mobile.ui.theme.FolioRadii
import dev.folio.mobile.ui.theme.FolioSpacing

/**
 * Editorial book list row (design system §7.4): flat row on the shared canvas,
 * larger consistent cover, strong title line, quiet author/kind metadata, thin
 * progress line, secondary actions in a 48dp overflow menu. Tap opens/resumes;
 * the menu deliberately does NOT repeat "Open".
 */
@Composable
fun FolioBookRow(
    title: String,
    subtitle: String,
    kindLabel: String,
    colorSeed: Int,
    cover: ImageBitmap?,
    percent: Double?,
    percentLabel: String,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    startInset: androidx.compose.ui.unit.Dp = FolioSpacing.x4,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(
                    start = startInset,
                    end = FolioSpacing.x1,
                    top = FolioSpacing.x3,
                    bottom = FolioSpacing.x3,
                ),
        ) {
            if (cover != null) {
                androidx.compose.foundation.Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(
                            width = FolioDimensions.bookCoverWidth,
                            height = FolioDimensions.bookCoverHeight,
                        )
                        .clip(RoundedCornerShape(FolioRadii.cover)),
                )
            } else {
                FolioCoverPlaceholder(
                    seed = colorSeed,
                    initial = coverInitial(title),
                    modifier = Modifier.size(
                        width = FolioDimensions.bookCoverWidth,
                        height = FolioDimensions.bookCoverHeight,
                    ),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = FolioSpacing.x4),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.x1),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                // Do not present kind ("book"/"article") as if it were an author
                // (§7.4): show the kind as a quiet tag instead.
                if (subtitle == kindLabel) {
                    KindTag(kindLabel)
                } else {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (percent != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = FolioSpacing.x1),
                    ) {
                        LinearProgressIndicator(
                            progress = { (percent / 100.0).toFloat() },
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp),
                        )
                        Spacer(Modifier.width(FolioSpacing.x2))
                        Text(
                            percentLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Box {
                FolioIconButton(
                    icon = FolioIcons.MoreVertical,
                    contentDescription = "More actions for \"$title\"",
                    onClick = { menuOpen = true },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Details") },
                        leadingIcon = {
                            Icon(FolioIcons.PenLine, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onDetails()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
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

        if (divider) {
            HorizontalDivider(
                thickness = FolioDimensions.divider,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = startInset + FolioDimensions.bookCoverWidth + FolioSpacing.x4),
            )
        }
    }
}

/** Quiet generated placeholder (§7.5): same geometry as real covers, stable tint, big initial. */
@Composable
fun FolioCoverPlaceholder(
    seed: Int,
    initial: Char,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(CoverTints[seed.and(0x7) % CoverTints.size], RoundedCornerShape(FolioRadii.cover))
            .border(1.dp, CoverInk.copy(alpha = 0.16f), RoundedCornerShape(FolioRadii.cover)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial.toString(),
            style = MaterialTheme.typography.displayMedium.copy(color = CoverInk),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun KindTag(kind: String) {
    Surface(
        shape = RoundedCornerShape(FolioRadii.pill),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            kind,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = FolioSpacing.x2, vertical = 2.dp),
        )
    }
}

/** First meaningful character for the placeholder's initial. */
internal fun coverInitial(title: String): Char =
    title.mapNotNull { c ->
        when {
            c.isLetter() -> c.uppercaseChar()
            else -> null
        }
    }.firstOrNull() ?: CoverFallbackChar

private const val CoverFallbackChar = '·'
