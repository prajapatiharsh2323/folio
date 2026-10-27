package dev.folio.mobile.core.store

import dev.folio.mobile.core.model.ReadingPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocPrefsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AppFileStore(tmp.newFolder())

    @Test
    fun `round trips per-doc overrides`() = runBlocking {
        val docs = DocPrefsStore(store())
        val warm = ReadingPrefs(theme = "warm", fontSize = 22.0, flow = "scrolled")
        val dark = ReadingPrefs(theme = "dark", justify = true)

        docs.set("doc-a", warm)
        docs.set("doc-b", dark)

        assertEquals(warm, docs.get("doc-a"))
        assertEquals(dark, docs.get("doc-b"))
    }

    @Test
    fun `missing file reads as no override`() = runBlocking {
        assertNull(DocPrefsStore(store()).get("doc-a"))
    }

    @Test
    fun `other docs keep their overrides when one is cleared`() = runBlocking {
        val docs = DocPrefsStore(store())
        docs.set("a", ReadingPrefs(theme = "warm"))
        docs.set("b", ReadingPrefs(theme = "dark"))

        docs.set("a", null)

        assertNull(docs.get("a"))
        assertEquals("dark", docs.get("b")!!.theme)
    }

    @Test
    fun `clearing the last override removes the file`() = runBlocking {
        val root = store()
        val docs = DocPrefsStore(root)
        docs.set("a", ReadingPrefs(theme = "warm"))
        assertTrue(root.exists(DocPrefsStore.DOC_SETTINGS_PATH))

        docs.set("a", null)

        assertFalse(root.exists(DocPrefsStore.DOC_SETTINGS_PATH))
        assertNull(docs.get("a"))
    }

    @Test
    fun `corrupt file reads as no override but is preserved before repair`() = runBlocking {
        val root = store()
        val corrupt = "not json[".encodeToByteArray()
        root.write(DocPrefsStore.DOC_SETTINGS_PATH, corrupt)

        val docs = DocPrefsStore(root)
        // Reading is a display fallback: the reader still opens.
        assertNull(docs.get("a"))
        assertArrayEquals(corrupt, root.read(DocPrefsStore.DOC_SETTINGS_PATH))

        docs.set("a", ReadingPrefs(theme = "warm"))
        assertEquals("warm", docs.get("a")!!.theme)

        val recovery = root.list("library/recovery")
        val copy = recovery.singleOrNull { it.startsWith("doc-settings.json-corrupt-") }
        assertTrue("corrupt per-book settings must be preserved", copy != null)
        assertArrayEquals(corrupt, root.read("library/recovery/$copy"))
    }

    @Test
    fun `repairing a corrupt file only preserves it once`() = runBlocking {
        val root = store()
        root.write(DocPrefsStore.DOC_SETTINGS_PATH, "not json[")

        val docs = DocPrefsStore(root)
        docs.set("a", ReadingPrefs(theme = "warm"))
        docs.set("b", ReadingPrefs(theme = "dark"))

        assertEquals(1, root.list("library/recovery").size)
        assertEquals("warm", docs.get("a")!!.theme)
        assertEquals("dark", docs.get("b")!!.theme)
    }

    @Test
    fun `doc settings live under the schema 1 user folder`() {
        assertTrue(
            DocPrefsStore.DOC_SETTINGS_PATH.startsWith("library/user/")
        )
    }
}
