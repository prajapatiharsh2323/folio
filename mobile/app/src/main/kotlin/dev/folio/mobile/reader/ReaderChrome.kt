package dev.folio.mobile.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.ui.components.FolioIconButton
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.FolioSpacing

/**
 * Reader chrome (quiet editorial pass): the book text stays dominant.
 * - Compact, NON-scrolling top bar: back, book title, reading appearance (Aa),
 *   search, overflow (contents/bookmark). Highlights go through the selection
 *   action mode, never a global toolbar action.
 * - Bottom reading dock: one slim progress control + chapter/percent/time.
 * - Colors mirror the PWA reader tokens (THEME_COLORS in ReaderPrefsMapper) so
 *   chrome blends with the page, never flashing app-theme colors.
 * Auto-hide: tapping the page's middle third toggles visibility (unchanged,
 * see ReaderActivity's InputListener); the dock stays up while scrubbing.
 */
@Composable
fun ReaderChrome(
    visible: Boolean,
    prefs: ReadingPrefs,
    bookTitle: String?,
    pillarTitle: String,
    pillarPercent: Double,
    onBack: () -> Unit,
    onBookmark: () -> Unit = {},
    onToc: () -> Unit = {},
    onSearch: () -> Unit = {},
    onAppearance: () -> Unit = {},
    onScrub: (Double) -> Unit = {},
    onScrubFinished: (Double) -> Unit = {},
    timeLeft: String? = null,
    /** "system" theme → the OS dark-mode state (canvas uses the same rule). */
    systemDark: Boolean = false,
    /** True when the current reading location already has a bookmark (P1-09). */
    bookmarked: Boolean = false,
    /** Pillar layer — false hides it so it stops covering the last page lines. */
    pillarVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val surface = chromeSurface(resolvedThemeKey(prefs.theme, systemDark))
    val text = chromeText(resolvedThemeKey(prefs.theme, systemDark))

    Column(modifier = modifier.fillMaxWidth()) {
        if (visible) {
            Surface(
                color = surface,
                contentColor = text,
                tonalElevation = 2.dp,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.statusBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
            ) {
                TopBar(
                    bookTitle = bookTitle,
                    text = text,
                    onBack = onBack,
                    onAppearance = onAppearance,
                    onSearch = onSearch,
                    onBookmark = onBookmark,
                    onToc = onToc,
                    bookmarked = bookmarked,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) // spacer over the canvas
        if (pillarVisible) {
            Pillar(
                pillarTitle,
                pillarPercent,
                surface,
                text,
                onScrub,
                onScrubFinished,
                timeLeft,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
            )
        }
    }
}

/**
 * One slim, labelled, NON-scrolling row. Secondary destinations (contents,
 * bookmark, highlight) live behind the overflow menu — nothing can be lost
 * off-screen on a 320dp phone.
 */
@Composable
private fun TopBar(
    bookTitle: String?,
    text: Color,
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    onSearch: () -> Unit,
    onBookmark: () -> Unit,
    onToc: () -> Unit,
    bookmarked: Boolean
) {
    var overflowOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioSpacing.x1, vertical = FolioSpacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolioIconButton(
            icon = FolioIcons.ArrowLeft,
            contentDescription = "Go back",
            onClick = onBack,
            tint = text,
        )
        Text(
            bookTitle.orEmpty().ifBlank { "Reader" },
            style = MaterialTheme.typography.titleMedium.copy(color = text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = FolioSpacing.x1),
        )
        FolioIconButton(
            icon = FolioIcons.Typeface,
            contentDescription = "Reading appearance",
            onClick = onAppearance,
            tint = text,
        )
        FolioIconButton(
            icon = FolioIcons.Search,
            contentDescription = "Search in book",
            onClick = onSearch,
            tint = text,
        )
        Box {
            FolioIconButton(
                icon = FolioIcons.MoreVertical,
                contentDescription = "More reading controls",
                onClick = { overflowOpen = true },
                tint = text,
            )
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Table of contents") },
                    leadingIcon = { Icon(FolioIcons.ListTree, contentDescription = null) },
                    onClick = {
                        overflowOpen = false
                        onToc()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (bookmarked) "Remove bookmark" else "Add bookmark") },
                    leadingIcon = {
                        Icon(
                            FolioIcons.Bookmark,
                            contentDescription = null,
                            tint = if (bookmarked) MaterialTheme.colorScheme.primary else text,
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        onBookmark()
                    }
                )
                /* Highlight selection removed: highlights go through the ActionMode
                   popup (see ReaderActivity selectionActionModeCallback). */
            }
        }
    }
}

@Composable
private fun Pillar(
    title: String,
    percent: Double,
    surface: Color,
    text: Color,
    onScrub: (Double) -> Unit,
    onScrubFinished: (Double) -> Unit,
    timeLeft: String? = null,
    modifier: Modifier = Modifier
) {
    if (title.isBlank() && percent <= 0.0) return

    // Local drag value so the thumb doesn't fight live progression updates.
    var scrub by remember { mutableStateOf<Double?>(null) }
    val shownPercent = ((scrub ?: percent).coerceIn(0.0, 1.0)) * 100

    Box(modifier = modifier.fillMaxWidth().padding(bottom = FolioSpacing.x2)) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .background(surface.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
                .padding(horizontal = FolioSpacing.x3, vertical = FolioSpacing.x2),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
            ) {
                Text(
                    title.takeIf { it.isNotBlank() } ?: "",
                    style = MaterialTheme.typography.labelMedium.copy(color = text.copy(alpha = 0.85f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (title.isNotBlank()) {
                    Text(
                        "  ·  ",
                        style = MaterialTheme.typography.labelMedium.copy(color = text.copy(alpha = 0.4f)),
                    )
                }
                Text(
                    "${shownPercent.toInt()}%",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = text.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Medium,
                    ),
                )
                timeLeft?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "  ·  $it",
                        style = MaterialTheme.typography.labelMedium.copy(color = text.copy(alpha = 0.6f)),
                    )
                }
            }
            Slider(
                value = (scrub ?: percent.coerceIn(0.0, 1.0)).toFloat(),
                onValueChange = {
                    scrub = it.toDouble()
                    onScrub(it.toDouble())
                },
                onValueChangeFinished = {
                    scrub?.let { s -> onScrubFinished(s) }
                    scrub = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = text,
                    activeTrackColor = text,
                    inactiveTrackColor = text.copy(alpha = 0.25f),
                ),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    // Visual track stays slim, but the interactive region is a
                    // full 48dp touch target (audit P1-06).
                    .height(48.dp)
                    .semantics {
                        contentDescription = "Reading progress"
                        stateDescription = "${shownPercent.toInt()}% read"
                    },
            )
        }
    }
}

private fun chromeSurface(theme: String): Color = when (theme) {
    "warm" -> Color(0xFFF4ECD8)
    "dark" -> Color(0xFF1C1C20)
    "true-black" -> Color(0xFF0B0B0E)
    else -> Color(0xFFFFFFFF)
}

/** "system" resolves to dark in dark OS mode (PWA media-query parity). */
fun resolvedThemeKey(theme: String, systemDark: Boolean): String =
    if (theme == "system" && systemDark) "dark" else theme

/**
 * R2b brightness scrim: darkens the page content when prefs.brightness < 100.
 * Maps 100..30 to alpha 0..0.56 — never fully black so text stays readable.
 * The mapping ([brightnessAlpha]) is pure and JVM-testable; this is the paint.
 */
@Composable
fun BrightnessOverlay(brightness: Double) {
    val alpha = brightnessAlpha(brightness)
    if (alpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(alpha = alpha, red = 0f, green = 0f, blue = 0f))
        )
    }
}

fun brightnessAlpha(brightness: Double): Float =
    (((100.0 - brightness) / 100.0) * 0.7).coerceIn(0.0, 0.7).toFloat()

private fun chromeText(theme: String): Color = when (theme) {
    "warm" -> Color(0xFF3B3226)
    "dark" -> Color(0xFFE6E3DC)
    "true-black" -> Color(0xFFC8C4BC)
    else -> Color(0xFF1C1B19)
}
