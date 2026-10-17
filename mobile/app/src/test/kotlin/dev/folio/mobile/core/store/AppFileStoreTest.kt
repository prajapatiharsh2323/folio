package dev.folio.mobile.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AppFileStore(tmp.newFolder("library-root"))

    @Test
    fun `write then read round trips bytes`() = runBlocking {
        val store = store()
        store.write("library/manifest.json", "{\"a\":1}")
        assertEquals("{\"a\":1}", store.readText("library/manifest.json"))
        val bytes = byteArrayOf(0, 1, 2, 255.toByte(), 1.toByte())
        store.write("library/docs/x/original/book.epub", bytes)
        assertArrayEquals(bytes, store.read("library/docs/x/original/book.epub"))
    }

    @Test
    fun `overwrite replaces content atomically`() = runBlocking {
        val store = store()
        store.write("f.txt", "first")
        store.write("f.txt", "second")
        assertEquals("second", store.readText("f.txt"))
    }

    @Test
    fun `failed write leaves no partial file at target`() = runBlocking {
        val store = store()
        store.write("f.txt", "first")
        // Target path is a directory → write must fail, old content must survive
        store.write("sub/file.txt", "keep")
        // Target path is an existing directory → the atomic move must fail,
        // the old file content must survive, and no .tmp litter remains.
        assertThrows(Exception::class.java) {
            runBlocking { store.write("sub", "oops") }
        }
        assertEquals("keep", store.readText("sub/file.txt"))
        assertTrue("no temp litter at root", store.list(".").none { it.contains(".tmp-") })
    }

    @Test
    fun `path traversal rejected`() = runBlocking<Unit> {
        val store = store()
        assertThrows(SecurityException::class.java) { runBlocking { store.write("../evil.txt", "x") } }
        assertThrows(SecurityException::class.java) { runBlocking { store.read("/etc/passwd") } }
        assertThrows(SecurityException::class.java) { runBlocking { store.read("C:\\windows\\system32") } }
        assertThrows(SecurityException::class.java) { runBlocking { store.write("docs/..\\..\\x", "y") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.read("") } }
    }

    @Test
    fun `exists list stat remove`() = runBlocking {
        val store = store()
        store.write("library/docs/a/original/x.epub", "A")
        store.write("library/docs/a/meta.json", "{}")
        store.write("library/docs/b/meta.json", "{}")
        assertTrue(store.exists("library/docs/a/meta.json"))
        assertFalse(store.exists("library/docs/a/nope.json"))
        assertEquals(
            setOf("a", "b"),
            store.list("library/docs").toSet()
        )
        val stat = store.stat("library/docs/a/meta.json")
        assertTrue(stat.size > 0 && stat.modifiedAt > 0)
        // non-recursive dir remove fails while non-empty
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.remove("library/docs/a") }
        }
        store.remove("library/docs/a", recursive = true)
        assertFalse(store.exists("library/docs/a/meta.json"))
        // removing a missing path is a no-op
        store.remove("library/docs/a", recursive = true)
    }
}
