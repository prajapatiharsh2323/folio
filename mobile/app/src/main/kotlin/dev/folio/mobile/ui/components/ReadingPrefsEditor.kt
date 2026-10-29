package dev.folio.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.reader.ReaderPrefsMapper
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.FolioSpacing
import androidx.compose.ui.text.style.TextAlign

/**
 * The per-book reading-appearance controls (audit P0-02 / S1 audit Phase 2),
 * extracted from `ReadingAppearanceSheet` so the READER sheet and the SETTINGS
 * reading-defaults editor render one shared, identical control set:
 * Theme · Text size · Typeface · Line spacing · Page margins · Alignment ·
 * Reading flow · Page dimming.
 *
 * Callback contract: [onPreview] fires continuously while a slider is dragged
 * (callers update the displayed value only — the renderer, not the store);
 * [onPrefsChange] fires on commit (release / tap). Stored keys and ReadingPrefs
 * semantics are untouched by this refactor.
 */
@Composable
fun ReadingPrefsEditor(
    prefs: ReadingPrefs,
    onPrefsChange: (ReadingPrefs) -> Unit,
    onPreview: (ReadingPrefs) -> Unit = {},
) {
    ReadingEditorSection("Theme") {
        ReadingThemeEditor(prefs, onPrefsChange)
    }

    ReadingEditorSection("Text size") {
        ReadingTextSizeEditor(prefs, onPrefsChange, onPreview)
    }

    ReadingEditorSection("Typeface") {
        ReadingSegmentedRow(
            options = listOf(
                "serif" to "Serif",
                "sans" to "Sans",
                "mono" to "Mono",
                "publisher" to "Publisher font",
            ),
            selected = prefs.fontFamily,
            onSelect = { fam -> onPrefsChange(prefs.copy(fontFamily = fam)) },
        )
        ReadingEditorNote(
            when (prefs.fontFamily) {
                "publisher" -> "Uses the typeface the book itself provides."
                "mono" -> "Monospace, similar to a typewriter page."
                "sans" -> "Clean sans-serif letters."
                else -> "Classic book serif letters."
            }
        )
    }

    ReadingEditorSection("Line spacing") {
        ReadingLabeledSlider(
            label = "Space between lines",
            value = prefs.lineHeight,
            onPreview = { onPreview(prefs.copy(lineHeight = it)) },
            onCommit = { onPrefsChange(prefs.copy(lineHeight = it)) },
            valueRange = 1.3f..2.4f,
            steps = 10,
            format = { v -> "%.1f×".format(v) },
        )
    }

    ReadingEditorSection("Page margins") {
        ReadingLabeledSlider(
            label = "Side margins",
            value = prefs.margin,
            onPreview = { onPreview(prefs.copy(margin = it)) },
            onCommit = { onPrefsChange(prefs.copy(margin = it)) },
            valueRange = 12f..64f,
            steps = 12,
            format = { v -> "${v.toInt()}" },
        )
    }

    ReadingEditorSection("Alignment") {
        ReadingSegmentedToggleRow(
            options = listOf(false to "Align left", true to "Justify"),
            selected = prefs.justify,
            onSelect = { just -> onPrefsChange(prefs.copy(justify = just)) },
        )
    }

    ReadingEditorSection("Reading flow") {
        ReadingSegmentedRow(
            options = listOf(
                "paginated" to "Paginated",
                "scrolled" to "Scroll",
            ),
            selected = prefs.flow,
            onSelect = { flow -> onPrefsChange(prefs.copy(flow = flow)) },
        )
    }

    ReadingEditorSection("Page dimming") {
        ReadingLabeledSlider(
            label = "Dim the reading page",
            value = prefs.brightness,
            onPreview = { onPreview(prefs.copy(brightness = it)) },
            onCommit = { onPrefsChange(prefs.copy(brightness = it)) },
            valueRange = 30f..100f,
            steps = 6,
            format = { v -> "${v.toInt()}%" },
        )
        ReadingEditorNote("Dims this reading screen only; it does not change your device brightness.")
    }
}

/**
 * Reader-page preview with the REAL reader theme/ink mapping (values come
 * straight from [ReaderPrefsMapper] — no duplicated hex): the chosen page
 * color, the chosen ink, the live font size / line height / alignment.
 * Used by both the reader sheet and the settings defaults editor.
 */
@Composable
fun ReadingPagePreview(
    prefs: ReadingPrefs,
    modifier: Modifier = Modifier,
) {
    val systemDark = isSystemInDarkTheme()
    val tokens = ReaderPrefsMapper.themeColors(prefs.theme, systemDark)
    val page = Color(colorHex(tokens.bg))
    val ink = Color(colorHex(tokens.text))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = page,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (prefs.justify) {
                "Aa — The lantern lit the page, and the page lit the room around it."
            } else {
                "Aa — The lantern lit the page."
            },
            style = androidx.compose.ui.text.TextStyle(
                fontSize = prefs.fontSize.sp,
                lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
                letterSpacing = prefs.letterSpacing.sp,
                textAlign = if (prefs.justify) {
                    androidx.compose.ui.text.style.TextAlign.Justify
                } else {
                    androidx.compose.ui.text.style.TextAlign.Start
                },
            ),
            color = ink,
            maxLines = 4,
            modifier = Modifier.padding(FolioSpacing.x4),
        )
    }
}

// ------------------------------------------------------------- sections ---

/** "#RRGGBB" → 0xFF RR GG BB, for X11-style PWA tokens. */
private fun colorHex(hex: String): Long =
    hex.removePrefix("#").toLong(16) or 0xFF000000L

@Composable
fun ReadingEditorSection(name: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x3)) {
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
fun ReadingEditorNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = FolioSpacing.x1),
    )
}

// --------------------------------------------------------------- theme ---

@Composable
fun ReadingThemeEditor(
    prefs: ReadingPrefs,
    onPrefsChange: (ReadingPrefs) -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val options = listOf("warm", "light", "dark", "true-black", "system")
    val labels = mapOf(
        "warm" to "Paper",
        "light" to "Light",
        "dark" to "Dark",
        "true-black" to "True black",
        "system" to "System",
    )
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
                row.forEach { key ->
                    val tokens = ReaderPrefsMapper.themeColors(key, systemDark)
                    val page = Color(colorHex(tokens.bg))
                    val ink = Color(colorHex(tokens.text))
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
                            color = page,
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
                                                .background(
                                                    Color(colorHex(
                                                        ReaderPrefsMapper.themeColors(
                                                            "light", false
                                                        ).bg
                                                    ))
                                                ),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .background(
                                                    Color(colorHex(
                                                        ReaderPrefsMapper.themeColors(
                                                            "dark", false
                                                        ).bg
                                                    ))
                                                ),
                                        )
                                    }
                                } else {
                                    Text(
                                        "Aa",
                                        style = MaterialTheme.typography.titleMedium.copy(color = ink),
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
                            labels.getValue(key),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            textAlign = TextAlign.Center,
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

// ---------------------------------------------------------------- rows ---

@Composable
fun ReadingTextSizeEditor(
    prefs: ReadingPrefs,
    onPrefsChange: (ReadingPrefs) -> Unit,
    onPreview: (ReadingPrefs) -> Unit = {},
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
fun ReadingLabeledSlider(
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

/** Row of mutually exclusive options (accent container = selected). */
@Composable
fun ReadingSegmentedRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (key, label) ->
            ReadingSegment(
                label = label,
                selected = selected == key,
                onClick = { onSelect(key) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun ReadingSegmentedToggleRow(
    options: List<Pair<Boolean, String>>,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (key, label) ->
            ReadingSegment(
                label = label,
                selected = selected == key,
                onClick = { onSelect(key) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun ReadingSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
