package dev.folio.mobile.core.store

import dev.folio.mobile.core.model.ReadingPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrefsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AppFileStore(tmp.newFolder())

    @Test
    fun `round trips reading prefs through settings json`() = runBlocking {
        val prefsStore = PrefsStore(store())
        val custom = ReadingPrefs(
            theme = "warm",
            fontFamily = "lora",
            fontSize = 22.0,
            lineHeight = 1.8,
            margin = 32.0,
            justify = true,
            hyphenate = true,
            flow = "scrolled"
        )
        prefsStore.saveReadingPrefs(custom)
        assertEquals(custom, prefsStore.loadReadingPrefs())
    }

    @Test
    fun `defaults write every schema field present`() = runBlocking {
        val root = store()
        val prefsStore = PrefsStore(root)
        prefsStore.saveReadingPrefs(ReadingPrefs())

        val text = root.readText(PrefsStore.SETTINGS_PATH)
        // Backup contract: settings.json must carry ALL keys the PWA schema
        // lists (encodeDefaults on), parsed-value parity encoded below.
        val obj = Json.parseToJsonElement(text).jsonObject
        val expectedKeys = setOf(
            "theme", "fontFamily", "fontSize", "lineHeight", "margin",
            "justify", "hyphenate", "paragraphSpacing", "letterSpacing",
            "maxWidth", "brightness", "flow", "spread", "pdfZoom"
        )
        assertEquals(expectedKeys, obj.keys)
    }

    @Test
    fun `unknown pwa keys are tolerated on read`() = runBlocking {
        val s = store()
        s.write(
            PrefsStore.SETTINGS_PATH,
            """{"theme":"dark","futureField":42}"""
        )
        val prefs = PrefsStore(s).loadReadingPrefs()
        assertEquals("dark", prefs.theme)
        // everything else falls back to defaults (partial write tolerance)
        assertEquals(18.0, prefs.fontSize, 0.000001)
    }

    @Test
    fun `missing file returns defaults`() = runBlocking<Unit> {
        val prefs = PrefsStore(store()).loadReadingPrefs()
        assertEquals(ReadingPrefs(), prefs)
    }

    @Test
    fun `settings live under the schema 1 user folder`() = runBlocking<Unit> {
        assertTrue(
            "backup export lists library/user/settings.json",
            PrefsStore.SETTINGS_PATH.startsWith("library/user/")
        )
    }
}
