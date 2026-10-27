package dev.folio.mobile.core.store

import dev.folio.mobile.core.library.LibraryFileCorruptException
import dev.folio.mobile.core.library.RECOVERY_DIR
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.util.IsoTime
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

/**
 * Per-document reading preferences (Batch 2: per-book typography overrides).
 *
 * Stored at `library/user/doc-settings.json` as `{ docId: ReadingPrefs }`.
 *
 * MOBILE-ONLY EXTENSION: this file is NOT part of the schema-1 backup contract
 * (the PWA reads annotations/progress/collections/settings only and ignores
 * unknown user files). Deliberately excluded from backup export/restore for
 * v1 so archive bytes stay exactly PWA-compatible.
 *
 * Data safety: an unreadable file is never silently treated as "no overrides"
 * and overwritten. [get] degrades to the global defaults for display (the
 * reader must still open), logs loudly, and [set] preserves the unreadable
 * bytes under [RECOVERY_DIR] before rebuilding the file. Per-book typography
 * is fully reconstructible, so this recovery is safe without blocking the
 * reader on a modal repair flow.
 */
class DocPrefsStore(private val store: FileStore) {

    companion object {
        const val DOC_SETTINGS_PATH = "library/user/doc-settings.json"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val serializer =
        MapSerializer(String.serializer(), ReadingPrefs.serializer())

    /**
     * The doc's saved override, or null when it reads with the global prefs.
     *
     * A corrupt file returns null (the reader still opens with global
     * defaults); this is a display fallback only — no write path treats the
     * corrupt file as an empty map (see [set]).
     */
    suspend fun get(docId: String): ReadingPrefs? =
        try {
            readStrict()?.get(docId)
        } catch (corrupt: LibraryFileCorruptException) {
            android.util.Log.w(
                "DocPrefsStore",
                "Per-book settings unreadable; using global defaults — ${corrupt.message}"
            )
            null
        }

    /** Saves (or removes when [prefs] is null) the doc's override. */
    suspend fun set(docId: String, prefs: ReadingPrefs?) {
        val current = try {
            readStrict()
        } catch (corrupt: LibraryFileCorruptException) {
            // Preserve the unreadable bytes byte-for-byte, then rebuild from a
            // valid empty map. Never drop saved overrides without a copy.
            preserveCorrupt(corrupt)
            emptyMap()
        } ?: emptyMap()

        val updated = if (prefs == null) current - docId else current + (docId to prefs)
        when {
            updated.isEmpty() && store.exists(DOC_SETTINGS_PATH) ->
                store.remove(DOC_SETTINGS_PATH)
            else -> store.write(
                DOC_SETTINGS_PATH,
                json.encodeToString(serializer, updated)
            )
        }
    }

    /** Null means "no per-book overrides yet"; corrupt throws instead. */
    private suspend fun readStrict(): Map<String, ReadingPrefs>? {
        if (!store.exists(DOC_SETTINGS_PATH)) return null
        return try {
            json.decodeFromString(serializer, store.readText(DOC_SETTINGS_PATH))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            throw LibraryFileCorruptException(DOC_SETTINGS_PATH, e.message ?: e.toString(), e)
        }
    }

    private suspend fun preserveCorrupt(corrupt: LibraryFileCorruptException) {
        val stamp = IsoTime.now().replace(":", "-").replace(".", "-")
        val dest = "$RECOVERY_DIR/doc-settings.json-corrupt-$stamp"
        store.write(dest, store.read(corrupt.path))
        android.util.Log.w(
            "DocPrefsStore",
            "Preserved unreadable per-book settings at $dest before rebuilding"
        )
    }
}
