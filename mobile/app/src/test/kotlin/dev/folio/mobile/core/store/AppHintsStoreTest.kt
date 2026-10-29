package dev.folio.mobile.core.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AppHintsStore is deliberately NOT corrupt-tolerant in the user-data sense:
 * hints are device-local noise, so a corrupt file reads as "not dismissed"
 * and is replaced on the next write. These tests pin that contract (S1 audit
 * Phase 3 — chrome hint) so nobody "upgrades" it to heavy preservation or
 * breaks the fresh-install default.
 */
class AppHintsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root() = AppFileStore(tmp.newFolder())

    @Test
    fun `missing file means hint not yet shown`() = runBlocking {
        val store = AppHintsStore(root())
        assertFalse(store.isChromeHintDismissed())
        assertFalse(AppHintsStore.chromeHintDismissedSync(root()))
    }

    @Test
    fun `dismiss round-trips and is remembered`() = runBlocking {
        val root = root()
        val store = AppHintsStore(root)
        store.dismissChromeHint()
        assertTrue(store.isChromeHintDismissed())
        // A NEW store instance (next session) still sees the dismissal.
        assertTrue(AppHintsStore(root).isChromeHintDismissed())
    }

    @Test
    fun `corrupt file reads as not dismissed and recoverable`() = runBlocking {
        val root = root()
        root.write(AppHintsStore.APP_HINTS_PATH, "{chromeHintDis1missed=")
        assertFalse(AppHintsStore(root).isChromeHintDismissed())
        // The next write replaces the corrupt file instead of preserving it.
        root.write(AppHintsStore.APP_HINTS_PATH, "{\"browser\": true")
        AppHintsStore(root).dismissChromeHint()
        assertTrue(AppHintsStore(root).isChromeHintDismissed())
    }

    @Test
    fun `sync helper survives a corrupt file`() {
        val corrupt = AppFileStore(tmp.newFolder())
            .apply { runBlocking { write(AppHintsStore.APP_HINTS_PATH, "not json") } }
        assertFalse(AppHintsStore.chromeHintDismissedSync(corrupt))
    }

    @Test
    fun `json carries only the hint field`() = runBlocking {
        val root = root()
        AppHintsStore(root).dismissChromeHint()
        val text = root.readText(AppHintsStore.APP_HINTS_PATH)
        assertTrue(text.contains("\"chromeHintDismissed\": true"))
        assertFalse(text.contains("schema"))
    }
}
