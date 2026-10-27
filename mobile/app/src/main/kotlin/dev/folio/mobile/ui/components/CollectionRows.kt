package dev.folio.mobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.library.CoverLoader
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.CoverInk
import dev.folio.mobile.ui.theme.FolioDimensions
import dev.folio.mobile.ui.theme.FolioRadii
import dev.folio.mobile.ui.theme.FolioSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Book cover thumbnail decoded once (LRU-cached in [CoverLoader]): a real
 * extracted cover when present, otherwise one of the bundled cover-imgs
 * illustrations chosen from [seed]. The tinted initial remains only as the
 * transient decode frame.
 */
@Composable
fun BookCover(
    coverPath: String?,
    seed: Int,
    title: String,
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    height: Dp = Dp.Unspecified,
    fallbackVariant: Int? = null,
    /** False keeps the quiet initial placeholder (e.g. empty fan slots). */
    fallbackArt: Boolean = true,
) {
    val context = LocalContext.current
    val store = remember { AppFileStore(context.filesDir) }
    val variant = fallbackVariant ?: DefaultCoverArt.indexFor(seed)
    var bitmap by remember(coverPath, variant, fallbackArt) {
        mutableStateOf<ImageBitmap?>(null)
    }
    // Fallback art is warm and light; a hairline keeps it from melting into the
    // canvas (the tinted placeholder already had one). Real covers keep their
    // own edge.
    var bundledArt by remember(coverPath, variant, fallbackArt) { mutableStateOf(false) }
    LaunchedEffect(coverPath, variant, fallbackArt) {
        val resources = context.resources
        val fallback = DefaultCoverArt.resourceAt(variant)
        val (decoded, usedBundled) = withContext(Dispatchers.IO) {
            val real = coverPath?.let { CoverLoader.decode(store, it) }
            if (real != null) {
                real to false
            } else {
                val art = if (fallbackArt) CoverLoader.decodeResource(resources, fallback) else null
                art to (art != null)
            }
        }
        bitmap = decoded?.asImageBitmap()
        bundledArt = usedBundled
    }
    val sized = modifier
        .then(if (width != Dp.Unspecified) Modifier.width(width) else Modifier)
        .then(if (height != Dp.Unspecified) Modifier.height(height) else Modifier)
    if (bitmap != null) {
        val coverShape = RoundedCornerShape(FolioRadii.cover)
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = sized
                .clip(coverShape)
                .then(
                    if (bundledArt) {
                        Modifier.border(1.dp, CoverInk.copy(alpha = 0.16f), coverShape)
                    } else {
                        Modifier
                    }
                ),
        )
    } else {
        FolioCoverPlaceholder(
            seed = seed,
            initial = coverInitial(title),
            modifier = sized,
        )
    }
}

/** Fan geometry shared by the fan and the row divider inset (design rhythm). */
internal val FanCoverWidth = 50.dp
internal val FanCoverStep = 18.dp
internal const val FAN_DEPTH = 3

/**
 * Three-cover fan preview ("shelf chip"): back-to-front, right-most behind,
 * each with a hairline surface edge so overlapping spares separate. Front
 * cover = first (oldest) collection member.
 */
@Composable
fun FolioCoverFan(
    covers: List<CoverSlot>,
    modifier: Modifier = Modifier,
    coverWidth: Dp = FanCoverWidth,
    coverHeight: Dp = 70.dp,
    step: Dp = FanCoverStep,
) {
    val fanWidth = coverWidth + step * (FAN_DEPTH - 1)
    Box(
        modifier = modifier
            .width(fanWidth)
            .height(coverHeight),
    ) {
        for (index in FAN_DEPTH - 1 downTo 0) {
            val slot = covers.getOrNull(index)
            BookCover(
                coverPath = slot?.coverPath,
                seed = slot?.seed ?: index,
                title = slot?.title ?: "·",
                width = coverWidth,
                height = coverHeight,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = step * index)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        RoundedCornerShape(FolioRadii.cover),
                    ),
                // Distinct fallback art per fan slot; real covers ignore it.
                // Missing slots (fewer than 3 members) stay quiet placeholders.
                fallbackVariant = index,
                fallbackArt = slot != null,
            )
        }
    }
}

/** One cover in a fan: store-relative path or placeholder tint seed. */
data class CoverSlot(
    val coverPath: String?,
    val seed: Int,
    val title: String,
)

/**
 * Editorial collection list row: the same flat-row anatomy as [FolioBookRow]
 * with a cover fan instead of a single cover, a bookTitle name, a count +
 * last-updated metadata line and a 48dp Rename/Delete overflow.
 */
@Composable
fun FolioCollectionRow(
    name: String,
    bookCount: Int,
    updatedLabel: String?,
    covers: List<CoverSlot>,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    startInset: Dp = FolioSpacing.x4,
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
            FolioCoverFan(covers = covers)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = FolioSpacing.x4),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.x1),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(
                        if (bookCount == 1) "1 book" else "$bookCount books",
                        updatedLabel
                    ).filter { !it.isNullOrBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box {
                FolioIconButton(
                    icon = FolioIcons.MoreVertical,
                    contentDescription = "More actions for \"$name\"",
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
                            Icon(FolioIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error)
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
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(
                    start = startInset + FanCoverWidth + FanCoverStep * (FAN_DEPTH - 1) + FolioSpacing.x4
                ),
            )
        }
    }
}

/** Human "Updated …" label; null when the collection has no recorded update. */
fun relativeUpdateLabel(iso: String?): String? {
    val updated = dev.folio.mobile.core.util.IsoTime.parseOrNull(iso) ?: return null
    val mins = Duration.between(updated, Instant.now()).toMinutes()
    return when {
        mins < 1 -> "Updated just now"
        mins < 60 -> "Updated ${mins}m ago"
        mins < 60 * 24 -> "Updated ${mins / 60}h ago"
        mins < 60 * 24 * 7 -> "Updated ${mins / (60 * 24)}d ago"
        else -> "Updated " + DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(updated)
    }
}
