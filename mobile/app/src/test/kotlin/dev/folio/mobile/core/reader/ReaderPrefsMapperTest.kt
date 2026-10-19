package dev.folio.mobile.core.reader

import dev.folio.mobile.core.model.ReadingPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderPrefsMapperTest {

    private val defaults = ReadingPrefs()

    @Test
    fun `pwa defaults map to px-divided readium factors`() {
        val m = ReaderPrefsMapper.mapPrefs(defaults)
        assertEquals(18.0 / 16.0, m.fontScale, 0.00001)
        assertEquals(1.6, m.lineHeight, 0.00001)
        assertEquals(20.0 / 16.0, m.pageMargins, 0.00001)
        assertEquals(false, m.scroll)
        assertEquals("never", m.spread)
        assertEquals("start", m.textAlign)
        assertEquals(false, m.hyphens)
        assertEquals("serif", m.fontFamily)
        assertEquals("light", m.theme)
        // exact PWA tokens (epub-engine.ts THEME_COLORS.light)
        assertEquals(0xFFFAFAF7.toInt(), m.backgroundArgb)
        assertEquals(0xFF1C1B19.toInt(), m.textArgb)
        // Audit EPUB-011: dormant spacing fields ride along as em pass-through.
        assertEquals(0.0, m.letterSpacing, 0.00001)
        assertEquals(0.0, m.paragraphSpacing, 0.00001)
    }

    @Test
    fun `warm and true-black resolve to sepia-dark tokens`() {
        val warm = ReaderPrefsMapper.mapPrefs(defaults.copy(theme = "warm"))
        assertEquals("sepia", warm.theme)
        assertEquals(0xFFF4ECD8.toInt(), warm.backgroundArgb)
        assertEquals(0xFF3B3226.toInt(), warm.textArgb)

        val black = ReaderPrefsMapper.mapPrefs(defaults.copy(theme = "true-black"))
        assertEquals("dark", black.theme)
        assertEquals(0xFF000000.toInt(), black.backgroundArgb)
        assertEquals(0xFFC8C4BC.toInt(), black.textArgb)

        val dark = ReaderPrefsMapper.mapPrefs(defaults.copy(theme = "dark"))
        assertEquals("dark", dark.theme)
        assertEquals(0xFF121214.toInt(), dark.backgroundArgb)
        assertEquals(0xFFE6E3DC.toInt(), dark.textArgb)
    }

    @Test
    fun `system follows the os dark mode setting`() {
        // system + dark OS → dark tokens (PWA media-query parity)
        val dark = ReaderPrefsMapper.mapPrefs(defaults.copy(theme = "system"), systemDark = true)
        assertEquals("dark", dark.theme)
        assertEquals(0xFF121214.toInt(), dark.backgroundArgb)
        assertEquals(0xFFE6E3DC.toInt(), dark.textArgb)

        // system + light OS → light tokens (backward-compatible default)
        val light = ReaderPrefsMapper.mapPrefs(defaults.copy(theme = "system"), systemDark = false)
        assertEquals("light", light.theme)
        assertEquals(0xFFFAFAF7.toInt(), light.backgroundArgb)

        // the theme key itself: dark prop only affects "system"
        assertEquals("dark", ReaderPrefsMapper.resolveThemeKey("system", systemDark = true))
        assertEquals("light", ReaderPrefsMapper.resolveThemeKey("system", systemDark = false))
        assertEquals("warm", ReaderPrefsMapper.resolveThemeKey("warm", systemDark = true))
        // arbitrary keys are guarded, too
        assertEquals("light", ReaderPrefsMapper.resolveThemeKey("banana", systemDark = true))
    }

    @Test
    fun `flow spread and justify map one to one`() {
        val scrolled = ReaderPrefsMapper.mapPrefs(
            defaults.copy(flow = "scrolled", spread = "auto", justify = true, hyphenate = true)
        )
        assertEquals(true, scrolled.scroll)
        assertEquals("auto", scrolled.spread)
        assertEquals("justify", scrolled.textAlign)
        assertEquals(true, scrolled.hyphens)

        val dual = ReaderPrefsMapper.mapPrefs(defaults.copy(spread = "both"))
        assertEquals("always", dual.spread)
    }

    @Test
    fun `publisher font family disables the override`() {
        assertNull(ReaderPrefsMapper.mapPrefs(defaults.copy(fontFamily = "publisher")).fontFamily)
        assertEquals("lora", ReaderPrefsMapper.mapPrefs(defaults.copy(fontFamily = "lora")).fontFamily)
    }

    /**
     * Audit EPUB-011: letterSpacing/paragraphSpacing are em pass-throughs —
     * the schema-1 round-trip keeps them stable while making them real CSS.
     */
    @Test
    fun `spacing prefs pass through as em values`() {
        val m = ReaderPrefsMapper.mapPrefs(
            defaults.copy(letterSpacing = 0.05, paragraphSpacing = 0.3)
        )
        assertEquals(0.05, m.letterSpacing, 0.00001)
        assertEquals(0.3, m.paragraphSpacing, 0.00001)
    }
}
