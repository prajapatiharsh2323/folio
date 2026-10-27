package dev.folio.mobile.core.store

import dev.folio.mobile.core.library.LibraryFileCorruptException
import dev.folio.mobile.core.library.ReadResult
import dev.folio.mobile.core.model.ReadingPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `corrupt file is reported and never silently defaulted`() = runBlocking<Unit> {
        val root = store()
        val corrupt = """{"theme":"dark","fontSize":oops""".encodeToByteArray()
        root.write(PrefsStore.SETTINGS_PATH, corrupt)

        val prefsStore = PrefsStore(root)

        val typed = prefsStore.readReadingPrefs()
        assertTrue("corrupt settings must not read as Missing", typed is ReadResult.Corrupt)

        try {
            prefsStore.loadReadingPrefs()
            fail("corrupt settings must be surfaced, not replaced with defaults")
        } catch (expected: LibraryFileCorruptException) {
            assertEquals(PrefsStore.SETTINGS_PATH, expected.path)
        }

        // Reading never modifies the file.
        assertArrayEquals(corrupt, root.read(PrefsStore.SETTINGS_PATH))
    }

    @Test
    fun `saving over corrupt settings preserves the original bytes first`() = runBlocking<Unit> {
        val root = store()
        val corrupt = "not json[".encodeToByteArray()
        root.write(PrefsStore.SETTINGS_PATH, corrupt)

        PrefsStore(root).saveReadingPrefs(ReadingPrefs(theme = "warm"))

        assertEquals("warm", PrefsStore(root).loadReadingPrefs().theme)

        val recovery = root.list("library/recovery")
        val copy = recovery.singleOrNull { it.startsWith("settings.json-corrupt-") }
        assertTrue("corrupt settings must be preserved under library/recovery", copy != null)
        assertArrayEquals(corrupt, root.read("library/recovery/$copy"))
    }

    @Test
    fun `a valid save does not create recovery copies`() = runBlocking<Unit> {
        val root = store()
        val prefsStore = PrefsStore(root)
        prefsStore.saveReadingPrefs(ReadingPrefs(theme = "dark"))
        prefsStore.saveReadingPrefs(ReadingPrefs(theme = "warm"))

        assertEquals(0, root.list("library/recovery").size)
    }

    @Test
    fun `settings live under the schema 1 user folder`() = runBlocking<Unit> {
        assertTrue(
            "backup export lists library/user/settings.json",
            PrefsStore.SETTINGS_PATH.startsWith("library/user/")
        )
    }
}
