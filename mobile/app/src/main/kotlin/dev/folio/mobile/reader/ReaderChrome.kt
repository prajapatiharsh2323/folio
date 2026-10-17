package dev.folio.mobile.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.NightsStay
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
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.model.ReadingPrefs

/**
 * Reader chrome (R2 lightweight start): always-visible top bar with back,
 * theme menu, font-size stepper and flow toggle. Colors mirror the PWA reader
 * tokens so the chrome doesn't clash with the reading canvas.
 */
@Composable
fun ReaderChrome(
    prefs: ReadingPrefs,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onPrefsChange: (ReadingPrefs) -> Unit,
    modifier: Modifier = Modifier
) {
    var themeMenuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = chromeSurface(prefs.theme),
        contentColor = chromeText(prefs.theme),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canGoBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            // Theme dots menu
            Box {
                IconButton(onClick = { themeMenuOpen = true }) {
                    Icon(Icons.Filled.NightsStay, contentDescription = "Theme")
                }
                DropdownMenu(
                    expanded = themeMenuOpen,
                    onDismissRequest = { themeMenuOpen = false }
                ) {
                    val options = listOf(
                        "light", "warm", "dark", "true-black", "system"
                    )
                    options.forEach { key ->
                        DropdownMenuItem(
                            text = { Text(if (prefs.theme == key) "• ".plus(key) else key) },
                            leadingIcon = {
                                ThemeDot(key)
                            },
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
