package dev.folio.mobile.core.store

import dev.folio.mobile.core.library.LibraryFileCorruptException
import dev.folio.mobile.core.library.ReadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAppearanceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AppAppearanceStore(AppFileStore(tmp.newFolder()))

    @Test
    fun `missing file resolves to system`() = runBlocking {
        val appearance = store()
        assertEquals(ReadResult.Missing, appearance.read())
        assertEquals("system", appearance.load())
        // Sync helper used by activities: same outcome without a file on disk.
        assertEquals(
            "system",
            AppAppearanceStore.loadThemeSync(
                AppFileStore(java.io.File(tmp.root, "absent-${System.nanoTime()}")
                .apply { mkdirs() })
            )
        )
    }

    @Test
    fun `round trips all three keys`() = runBlocking {
        val appearance = store()
        for (key in listOf("system", "light", "dark")) {
            appearance.save(key)
            assertEquals(key, appearance.load())
        }
    }

    @Test
    fun `unknown keys are rejected before any write`() = runBlocking {
        val root = AppFileStore(tmp.newFolder())
        val appearance = AppAppearanceStore(root)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { appearance.save("amoled+") }
        }
        // And nothing was written by the rejected save.
        assertEquals(ReadResult.Missing, appearance.read())
    }

    @Test
    fun `json carries the single appTheme field`() = runBlocking {
        val root = AppFileStore(tmp.newFolder())
        val appearance = AppAppearanceStore(root)
        appearance.save("dark")
        val text = root.readText(AppAppearanceStore.APP_APPEARANCE_PATH)
        assertTrue(text.contains("\"appTheme\": \"dark\""))
        assertFalse(text.contains("schema"))
    }

    @Test
    fun `isDark maps stored keys and defaults to system`() {
        assertTrue(AppAppearanceStore.isDark("dark", systemDark = false))
        assertFalse(AppAppearanceStore.isDark("light", systemDark = true))
        assertTrue(AppAppearanceStore.isDark("system", systemDark = true))
        assertTrue(AppAppearanceStore.isDark(null, systemDark = true))
        assertTrue(AppAppearanceStore.isDark("bogus", systemDark = true))
        assertFalse(AppAppearanceStore.isDark("bogus", systemDark = false))
    }

    @Test
    fun `corrupt file throws on load and is surfaced on read`() = runBlocking {
        val root = AppFileStore(tmp.newFolder())
        root.write(
            AppAppearanceStore.APP_APPEARANCE_PATH,
            "{not json at all"
        )
        val appearance = AppAppearanceStore(root)
        when (val state = appearance.read()) {
            is ReadResult.Corrupt -> {
                assertEquals(AppAppearanceStore.APP_APPEARANCE_PATH, state.path)
                assertTrue(state.message.isNotBlank())
            }
            else -> fail("corrupt file must surface Corrupt, got $state")
        }
        assertThrows(LibraryFileCorruptException::class.java) {
            runBlocking { appearance.load() }
        }
        Unit
    }

    @Test
    fun `saving over corrupt file preserves the old bytes and then succeeds`() = runBlocking {
        val root = AppFileStore(tmp.newFolder())
        val corrupt = "{\"appTheme\": \"lig"
        root.write(AppAppearanceStore.APP_APPEARANCE_PATH, corrupt)
        val appearance = AppAppearanceStore(root)

        appearance.save("dark") // preserves + overwrites

        // New value is live…
        assertEquals("dark", appearance.load())
        // …and the unreadable bytes are somewhere in recovery, byte-identical.
        val recoveryDir = "library/recovery"
        val copies = root.list(recoveryDir)
            .filter { it.startsWith("app-appearance.json-corrupt-") }
        assertEquals(1, copies.size)
        assertArrayEquals(
            corrupt.toByteArray(),
            root.read("$recoveryDir/${copies.first()}")
        )
    }

    @Test
    fun `failed preservation aborts the save and keeps the corrupt bytes`() = runBlocking {
        val root = FaultyRecoveryStore(AppFileStore(tmp.newFolder()), FaultMode.WRITE_FAILS)
        val corrupt = "{\"appTheme\": \"da"
        root.write(AppAppearanceStore.APP_APPEARANCE_PATH, corrupt)
        val appearance = AppAppearanceStore(root)

        try {
            appearance.save("light")
            fail("save must abort when preservation cannot be verified")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("save aborted"))
        }
        // Corrupt source still there, byte-identical; nothing new was written.
        assertEquals(corrupt, root.readText(AppAppearanceStore.APP_APPEARANCE_PATH))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue(java.util.Arrays.equals(expected, actual))
    }

    /** Recovery-directory fault injection, mirroring PrefsStoreTest. */
    private enum class FaultMode { WRITE_FAILS, EVAPORATES, MUTATES }

    private class FaultyRecoveryStore(
        private val delegate: AppFileStore,
        private val fault: FaultMode,
    ) : FileStore by delegate {
        override suspend fun write(path: String, data: ByteArray) {
            if (fault == FaultMode.WRITE_FAILS && path.startsWith("library/recovery/")) {
                throw java.io.IOException("injected: recovery write failed")
            }
            delegate.write(path, data)
        }

        override suspend fun exists(path: String): Boolean {
            if (fault == FaultMode.EVAPORATES && path.startsWith("library/recovery/")) {
                return false
            }
            return delegate.exists(path)
        }

        override suspend fun read(path: String): ByteArray {
            val bytes = delegate.read(path)
            if (fault == FaultMode.MUTATES && path.startsWith("library/recovery/")) {
                return bytes + byteArrayOf(0x58)
            }
            return bytes
        }
    }
}
