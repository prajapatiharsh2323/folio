package dev.folio.mobile.reader

import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.reader.ReaderPrefsMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Audit EPUB-011: the schema's dormant spacing prefs now feed real ReadiumCSS.
 * 0.0 (schema default — never touched) must map to null so untouched libraries
 * don't gain explicit zero styles that stomp publisher hand-tuning; any other
 * value is user intent and passes through 1:1 (em).
 */
class ReaderPrefsAndroidSpacingTest {

    @Test
    fun `schema defaults map spacing to null`() {
        val mapped = ReaderPrefsMapper.mapPrefs(ReadingPrefs())
        val prefs = ReaderPrefsAndroid.toEpubInitialPreferences(mapped)
        assertNull(prefs.letterSpacing)
        assertNull(prefs.paragraphSpacing)
    }

    @Test
    fun `explicit spacing values pass through unchanged`() {
        val mapped = ReaderPrefsMapper.mapPrefs(
            ReadingPrefs(letterSpacing = 0.05, paragraphSpacing = 0.3)
        )
        val prefs = ReaderPrefsAndroid.toEpubInitialPreferences(mapped)
        assertEquals(0.05, prefs.letterSpacing!!, 0.00001)
        assertEquals(0.3, prefs.paragraphSpacing!!, 0.00001)
    }
}
