package dev.folio.mobile.reader

import dev.folio.mobile.core.reader.ReaderPrefsMapper.MappedReaderPrefs
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

/**
 * Android-side adapter: [MappedReaderPrefs] (primitives, see the pure mapper)
 * → Readium `EpubPreferences`. Kept thin so the mapping logic can stay in
 * JVM-tested code.
 */
object ReaderPrefsAndroid {

    fun toEpubInitialPreferences(mapped: MappedReaderPrefs): EpubPreferences = EpubPreferences(
        // Matches the PWA: custom typography always wins over publisher CSS
        publisherStyles = false,
        fontSize = mapped.fontScale,
        lineHeight = mapped.lineHeight,
        pageMargins = mapped.pageMargins,
        scroll = mapped.scroll,
        // Audit EPUB-011: the schema's dormant spacing prefs are now real CSS.
        // Schema default 0.0 → null (no explicit style, publisher rules stay in
        // charge); any non-zero value is unambiguous user intent → pass 1:1 (em).
        letterSpacing = mapped.letterSpacing.takeIf { it != 0.0 },
        paragraphSpacing = mapped.paragraphSpacing.takeIf { it != 0.0 },
        columnCount = if (mapped.scroll) null else ColumnCount.AUTO,
        spread = when (mapped.spread) {
            "always" -> Spread.ALWAYS
            "auto" -> Spread.AUTO
            else -> Spread.NEVER
        },
        textAlign = if (mapped.textAlign == "justify") TextAlign.JUSTIFY else TextAlign.START,
        hyphens = mapped.hyphens,
        theme = when (mapped.theme) {
            "dark" -> Theme.DARK
            "sepia" -> Theme.SEPIA
            else -> Theme.LIGHT
        },
        backgroundColor = Color(mapped.backgroundArgb),
        textColor = Color(mapped.textArgb),
        fontFamily = when (mapped.fontFamily) {
            "serif" -> FontFamily.SERIF
            "sans" -> FontFamily.SANS_SERIF
            "mono" -> FontFamily.MONOSPACE
            null -> null
            else -> FontFamily(mapped.fontFamily)
        }
    )

    fun toEpubDefaults(mapped: MappedReaderPrefs): EpubDefaults = EpubDefaults(
        fontSize = mapped.fontScale,
        lineHeight = mapped.lineHeight,
        pageMargins = mapped.pageMargins,
        scroll = mapped.scroll
    )
}
