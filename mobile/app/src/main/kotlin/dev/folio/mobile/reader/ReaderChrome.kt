package dev.folio.mobile.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    modifier: Modifier = Modifier
) {
    val surface = chromeSurface(prefs.theme)
    val text = chromeText(prefs.theme)

    Column(modifier = modifier.fillMaxWidth()) {
        if (visible) {
            Surface(
                color = surface,
                contentColor = text,
                tonalElevation = 2.dp
            ) {
                Column {
                    TopBar(prefs, onBack, onPrefsChange, onHighlight)
                    TypographyRow(prefs, text, onPrefsChange)
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) // spacer over the canvas
        Pillar(pillarTitle, pillarPercent, surface, text)
    }
}

@Composable
private fun TopBar(
    prefs: ReadingPrefs,
    onBack: () -> Unit,
    onPrefsChange: (ReadingPrefs) -> Unit,
    onHighlight: () -> Unit
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
    text: Color
) {
    if (title.isBlank() && percent <= 0.0) return
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = surface.copy(alpha = 0.92f),
            tonalElevation = 1.dp,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = if (title.isBlank()) {
                    "${(percent * 100).toInt()}%"
                } else {
                    "${title.take(24)} — ${(percent * 100).toInt()}%"
                },
                style = MaterialTheme.typography.labelSmall,
                color = text.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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

private fun chromeText(theme: String): Color = when (theme) {
    "warm" -> Color(0xFF3B3226)
    "dark" -> Color(0xFFE6E3DC)
    "true-black" -> Color(0xFFC8C4BC)
    else -> Color(0xFF1C1B19)
}
