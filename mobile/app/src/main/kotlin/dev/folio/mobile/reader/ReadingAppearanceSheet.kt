package dev.folio.mobile.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.ui.components.FolioIconButton
import dev.folio.mobile.ui.components.FolioSheetHeader
import dev.folio.mobile.ui.components.FolioSlider
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.FolioSpacing

/**
 * Reading appearance sheet (audit P0-02): every typography control that used to
 * live in the horizontally scrolling rows, now named in plain language:
 * Theme · Text size · Typeface · Line spacing · Page margins · Alignment ·
 * Reading flow · Reader brightness, plus the per-book scope note and reset.
 *
 * Prefs contract (unchanged): the sheet only emits updated [ReadingPrefs] with
 * the exact serialized keys already stored ("warm"/"lh"/"mx"/... semantics are
 * untouched; only labels changed). Live updates and per-book override
 * persisting stay in ReaderActivity.
 */
@Composable
fun ReadingAppearanceSheet(
    prefs: ReadingPrefs,
    perBookOverride: Boolean,
    onPrefsChange: (ReadingPrefs) -> Unit,
    onResetPerBook: () -> Unit,
    onDismiss: () -> Unit,
    /** Live preview while dragging; persistence happens through [onPrefsChange]. */
    onPreview: (ReadingPrefs) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = FolioSpacing.x4, bottom = FolioSpacing.x8)
    ) {
        FolioSheetHeader(
            title = "Reading appearance",
            subtitle = "These settings apply to this book",
            onClose = onDismiss,
        )

        Section("Theme") {
            ThemePicker(prefs, onPrefsChange)
        }

        Section("Text size") {
            TextSizeRow(prefs, onPrefsChange, onPreview)
            Text(
                "Aa — The lantern lit the page.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = prefs.fontSize.sp,
                    lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                modifier = Modifier.padding(top = FolioSpacing.x2),
            )
        }

        Section("Typeface") {
            SegmentedRow(
                options = listOf(
                    "serif" to "Serif",
                    "sans" to "Sans",
                    "mono" to "Mono",
                    "publisher" to "Publisher font",
                ),
                selected = prefs.fontFamily,
                onSelect = { fam -> onPrefsChange(prefs.copy(fontFamily = fam)) },
            )
            Note(
                when (prefs.fontFamily) {
                    "publisher" -> "Uses the typeface the book itself provides."
                    "mono" -> "Monospace, similar to a typewriter page."
                    "sans" -> "Clean sans-serif letters."
                    else -> "Classic book serif letters."
                }
            )
        }

        Section("Line spacing") {
            LabeledSlider(
                label = "Space between lines",
                value = prefs.lineHeight,
                onPreview = {
                    onPreview(prefs.copy(lineHeight = it))
                },
                onCommit = {
                    onPrefsChange(prefs.copy(lineHeight = it))
                },
                valueRange = 1.3f..2.4f,
                steps = 10,
                format = { v -> "%.1f×".format(v) },
            )
        }

        Section("Page margins") {
            LabeledSlider(
                label = "Side margins",
                value = prefs.margin,
                onPreview = {
                    onPreview(prefs.copy(margin = it))
                },
                onCommit = {
                    onPrefsChange(prefs.copy(margin = it))
                },
                valueRange = 12f..64f,
                steps = 12,
                format = { v -> "${v.toInt()}" },
            )
        }

        Section("Alignment") {
            SegmentedToggleRow(
                options = listOf(false to "Align left", true to "Justify"),
                selected = prefs.justify,
                onSelect = { just -> onPrefsChange(prefs.copy(justify = just)) },
            )
        }

        Section("Reading flow") {
            SegmentedRow(
                options = listOf(
                    "paginated" to "Paginated",
                    "scrolled" to "Scroll",
                ),
                selected = prefs.flow,
                onSelect = { flow -> onPrefsChange(prefs.copy(flow = flow)) },
            )
        }

        Section("Page dimming") {
            LabeledSlider(
                label = "Dim the reading page",
                value = prefs.brightness,
                onPreview = {
                    onPreview(prefs.copy(brightness = it))
                },
                onCommit = {
                    onPrefsChange(prefs.copy(brightness = it))
                },
                valueRange = 30f..100f,
                steps = 6,
                format = { v -> "${v.toInt()}%" },
            )
            Note("Dims this reading screen only; it does not change your device brightness.")
        }

        if (perBookOverride) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
            ) {
                Text(
                    "This book uses its own reading settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onResetPerBook) { Text("Reset to defaults") }
            }
        }
    }
}

// ---------------------------------------------------------------- sections ---

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x3)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = FolioSpacing.x1),
    )
}

// ------------------------------------------------------------------- theme ---

// Canvas/ink pairs mirror ReaderPrefsMapper THEME_COLORS so the swatches show
// exactly what the page will look like (values are display-only; the stored
// keys and renderer mapping stay untouched).
private val PaperPage = Color(0xFFF4ECD8)
private val PaperInk = Color(0xFF3B3226)
private val LightPage = Color(0xFFFAFAF7)
private val LightInk = Color(0xFF1C1B19)
private val DarkPage = Color(0xFF121214)
private val DarkInk = Color(0xFFE6E3DC)
private val TrueBlackPage = Color(0xFF000000)
private val TrueBlackInk = Color(0xFFC8C4BC)

private data class ThemeSwatch(val page: Color, val ink: Color, val label: String)

private val THEME_SWATCHES = mapOf(
    "warm" to ThemeSwatch(PaperPage, PaperInk, "Paper"),
    "light" to ThemeSwatch(LightPage, LightInk, "Light"),
    "dark" to ThemeSwatch(DarkPage, DarkInk, "Dark"),
    "true-black" to ThemeSwatch(TrueBlackPage, TrueBlackInk, "True black"),
    "system" to ThemeSwatch(LightPage, LightInk, "System"),
)

@Composable
private fun ThemePicker(prefs: ReadingPrefs, onPrefsChange: (ReadingPrefs) -> Unit) {
    val options = THEME_SWATCHES.entries.toList()
    // Two rows of at most three options: every label stays readable at 320dp
    // and at 1.5× font scale without horizontal scrolling (audit P1-08).
    Column(
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
        modifier = Modifier.selectableGroup(),
    ) {
        options.chunked(3).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { entry ->
                    val key = entry.key
                    val swatch = entry.value
                    val selected = prefs.theme == key
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onPrefsChange(prefs.copy(theme = key)) },
                            ),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = swatch.page,
                            border = if (selected) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (key == "system") {
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .background(LightPage),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .background(DarkPage),
                                        )
                                    }
                                } else {
                                    Text(
                                        "Aa",
                                        style = MaterialTheme.typography.titleMedium.copy(color = swatch.ink),
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        FolioIcons.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp)
                                            .size(14.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            swatch.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(top = FolioSpacing.x1),
                        )
                    }
                }
                // Keep the last (shorter) row aligned with the first.
                repeat(3 - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// -------------------------------------------------------------------- rows ---

@Composable
private fun TextSizeRow(
    prefs: ReadingPrefs,
    onPrefsChange: (ReadingPrefs) -> Unit,
    onPreview: (ReadingPrefs) -> Unit
) {
    var value by remember(prefs.fontSize) { mutableStateOf(prefs.fontSize.toFloat()) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        FolioIconButton(
            icon = FolioIcons.Minus,
            contentDescription = "Smaller text",
            onClick = {
                val next = (value - 2f).coerceIn(14f, 32f)
                value = next
                onPrefsChange(prefs.copy(fontSize = next.toDouble()))
            },
        )
        FolioSlider(
            value = value,
            onValueChange = {
                value = it
                onPreview(prefs.copy(fontSize = it.toDouble()))
            },
            onValueChangeFinished = {
                onPrefsChange(prefs.copy(fontSize = value.toDouble()))
            },
            valueRange = 14f..32f,
            steps = 8,
            contentDescription = "Text size",
            modifier = Modifier.weight(1f),
        )
        FolioIconButton(
            icon = FolioIcons.Plus,
            contentDescription = "Larger text",
            onClick = {
                val next = (value + 2f).coerceIn(14f, 32f)
                value = next
                onPrefsChange(prefs.copy(fontSize = next.toDouble()))
            },
        )
        Text(
            "${value.toInt()}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = FolioSpacing.x1)
                .heightIn(min = 32.dp),
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Double,
    onPreview: (Double) -> Unit,
    onCommit: (Double) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FolioSlider(
                value = local,
                onValueChange = {
                    local = it
                    onPreview(it.toDouble())
                },
                onValueChangeFinished = { onCommit(local.toDouble()) },
                valueRange = valueRange,
                steps = steps,
                contentDescription = label,
            )
        }
        Text(
            format(local),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = FolioSpacing.x2),
        )
    }
}

/** Toggle row of mutually exclusive options (accent container = selected). */
@Composable
private fun SegmentedRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (key, label) ->
            Segment(
                label = label,
                selected = selected == key,
                onClick = { onSelect(key) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SegmentedToggleRow(
    options: List<Pair<Boolean, String>>,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (key, label) ->
            Segment(
                label = label,
                selected = selected == key,
                onClick = { onSelect(key) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clickable { onClick() }
            .heightIn(min = 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
