package dev.folio.mobile.core.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One-time device-local UI hints ("show this once, never nags again").
 *
 * This is NOT user library data: the file (`app-hints.json`, filesDir root)
 * sits outside `library/` and the schema-1 backup contract, exactly like
 * [AppAppearanceStore]. Corrupt/missing resolves to "not shown yet" — the
 * worst case is a hint re-appearing once — so the heavy preservation dance of
 * the user-data stores is deliberately NOT applied here; a bad file is simply
 * replaced on the next write.
 */
class AppHintsStore(private val store: FileStore) {

    companion object {
        const val APP_HINTS_PATH = "app-hints.json"

        const val HINT_CHROME = "chromeHint"

        /**
         * Blocking read for the activity `onCreate` path (hint state must be
         * known before the reader surface mounts). Any failure → false.
         */
        fun chromeHintDismissedSync(store: FileStore): Boolean = runBlocking {
            try {
                AppHintsStore(store).isChromeHintDismissed()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                false
            }
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun isChromeHintDismissed(): Boolean {
        if (!store.exists(APP_HINTS_PATH)) return false
        return try {
            json.decodeFromString(
                AppHints.serializer(),
                store.readText(APP_HINTS_PATH)
            ).chromeHintDismissed
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // Corrupt hints are noise, not user data; "not dismissed" is safe.
            false
        }
    }

    suspend fun dismissChromeHint() = write(AppHints(chromeHintDismissed = true))

    private suspend fun write(hints: AppHints) {
        store.write(APP_HINTS_PATH, json.encodeToString(AppHints.serializer(), hints))
    }
}

@Serializable
private data class AppHints(val chromeHintDismissed: Boolean = false)
