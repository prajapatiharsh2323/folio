package dev.folio.mobile.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.model.ReadingPrefs

/**
 * Reader chrome (R2). Layers:
 * - top bar (back / theme / size / flow) — hidden by tapping the page's middle
 *   third (see ReaderActivity's InputListener); visible by default, PWA-parity;
 * - secondary typography row (family, line-height, margins, justify) shown
 *   with the top bar;
 * - always-visible bottom pillar: `<chapter title> — <%>`.
 *
 * Colors mirror the PWA reader tokens (THEME_COLORS in ReaderPrefsMapper).
 */
@Composable
fun ReaderChrome(
    visible: Boolean,
    prefs: ReadingPrefs,
    pillarTitle: String,
    pillarPercent: Double,
    onBack: () -> Unit,
    onPrefsChange: (ReadingPrefs) -> Unit,
    onHighlight: () -> Unit = {},
    onBookmark: () -> Unit = {},
    onToc: () -> Unit = {},
    onScrub: (Double) -> Unit = {},
    onScrubFinished: (Double) -> Unit = {},
    timeLeft: String? = null,
    /** "system" theme → the OS dark-mode state (canvas uses the same rule). */
    systemDark: Boolean = false,
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
                Column {
                    TopBar(prefs, onBack, onPrefsChange, onHighlight, onBookmark, onToc)
                    TypographyRow(prefs, text, onPrefsChange)
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) // spacer over the canvas
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

@Composable
private fun TopBar(
    prefs: ReadingPrefs,
    onBack: () -> Unit,
    onPrefsChange: (ReadingPrefs) -> Unit,
    onHighlight: () -> Unit,
    onBookmark: () -> Unit = {},
    onToc: () -> Unit = {}
) {
    var themeMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Box {
            IconButton(onClick = { themeMenuOpen = true }) {
                Icon(Icons.Filled.NightsStay, contentDescription = "Theme")
            }
            DropdownMenu(
                expanded = themeMenuOpen,
                onDismissRequest = { themeMenuOpen = false }
            ) {
                listOf("light", "warm", "dark", "true-black", "system").forEach { key ->
                    DropdownMenuItem(
                        text = { Text(if (prefs.theme == key) "• ".plus(key) else key) },
                        leadingIcon = { ThemeDot(key) },
                        onClick = {
                            themeMenuOpen = false
                            onPrefsChange(prefs.copy(theme = key))
                        }
                    )
                }
            }
        }
        IconButton(onClick = {
            onPrefsChange(prefs.copy(fontSize = (prefs.fontSize - 2.0).coerceIn(14.0, 32.0)))
        }) {
            Icon(Icons.Filled.Remove, contentDescription = "Smaller text")
        }
        Text("Aa", style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = {
            onPrefsChange(prefs.copy(fontSize = (prefs.fontSize + 2.0).coerceIn(14.0, 32.0)))
        }) {
            Icon(Icons.Filled.Add, contentDescription = "Larger text")
        }
        IconButton(onClick = {
            onPrefsChange(
                prefs.copy(flow = if (prefs.flow == "paginated") "scrolled" else "paginated")
            )
        }) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Toggle flow")
        }
        IconButton(onClick = onToc) {
            Icon(Icons.Filled.FormatListBulleted, contentDescription = "Table of contents")
        }
        IconButton(onClick = onBookmark) {
            Icon(Icons.Filled.Bookmark, contentDescription = "Add bookmark")
        }
        // R3 groundwork: captures the current text selection as a highlight.
        IconButton(onClick = onHighlight) {
            Icon(Icons.Filled.Brush, contentDescription = "Highlight selection")
        }
    }
}

@Composable
private fun TypographyRow(
    prefs: ReadingPrefs,
    tint: Color,
    onPrefsChange: (ReadingPrefs) -> Unit
) {
    var familyMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Text(
                text = "font: ${prefs.fontFamily}",
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .background(tint.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable { familyMenuOpen = true }
            )
            DropdownMenu(
                expanded = familyMenuOpen,
                onDismissRequest = { familyMenuOpen = false }
            ) {
                listOf("serif", "sans", "mono", "lora", "publisher").forEach { fam ->
                    DropdownMenuItem(
                        text = { Text(fam) },
                        onClick = {
                            familyMenuOpen = false
                            onPrefsChange(prefs.copy(fontFamily = fam))
                        }
                    )
                }
            }
        }
        Stepper(
            label = "lh %.1f".format(prefs.lineHeight),
            onDown = {
                onPrefsChange(prefs.copy(lineHeight = (prefs.lineHeight - 0.1).coerceIn(1.3, 2.4)))
            },
            onUp = {
                onPrefsChange(prefs.copy(lineHeight = (prefs.lineHeight + 0.1).coerceIn(1.3, 2.4)))
            },
            tint = tint
        )
        Stepper(
            label = "mx ${prefs.margin.toInt()}",
            onDown = {
                onPrefsChange(prefs.copy(margin = (prefs.margin - 4.0).coerceIn(12.0, 64.0)))
            },
            onUp = {
                onPrefsChange(prefs.copy(margin = (prefs.margin + 4.0).coerceIn(12.0, 64.0)))
            },
            tint = tint
        )
        Text(
            text = "J",
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .background(
                    if (prefs.justify) tint.copy(alpha = 0.16f) else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
                .border(
                    1.dp,
                    tint.copy(alpha = if (prefs.justify) 0f else 0.5f),
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .clickable { onPrefsChange(prefs.copy(justify = !prefs.justify)) }
        )
        Stepper(
            label = "br ${prefs.brightness.toInt()}",
            onDown = {
                onPrefsChange(
                    prefs.copy(brightness = (prefs.brightness - 10.0).coerceIn(30.0, 100.0))
                )
            },
            onUp = {
                onPrefsChange(
                    prefs.copy(brightness = (prefs.brightness + 10.0).coerceIn(30.0, 100.0))
                )
            },
            tint = tint
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    tint: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDown) {
            Icon(Icons.Filled.Remove, contentDescription = "$label decrease", tint = tint)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
        IconButton(onClick = onUp) {
            Icon(Icons.Filled.Add, contentDescription = "$label increase", tint = tint)
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

    Box(modifier = modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .background(surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = buildString {
                    append(if (title.isBlank()) "" else "${title.take(24)} — ")
                    append((((scrub ?: percent).coerceIn(0.0, 1.0)) * 100).toInt())
                    append("%")
                    timeLeft?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = text.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(0.9f)
                    .height(22.dp)
            )
        }
    }
}

@Composable
private fun ThemeDot(themeKey: String) {
    val dotColors = mapOf(
        "light" to 0xFFFAFAF7,
        "warm" to 0xFFF4ECD8,
        "dark" to 0xFF121214,
        "true-black" to 0xFF000000,
        "system" to 0xFFFAFAF7
    )
    val argb = dotColors[themeKey] ?: 0xFFFAFAF7
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(Color(argb), CircleShape)
    )
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
