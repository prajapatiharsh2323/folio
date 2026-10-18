package dev.folio.mobile.core.store

import dev.folio.mobile.core.model.ReadingPrefs
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Per-document reading preferences (Batch 2: per-book typography overrides).
 *
 * Stored at `library/user/doc-settings.json` as `{ docId: ReadingPrefs }`.
 *
 * MOBILE-ONLY EXTENSION: this file is NOT part of the schema-1 backup contract
 * (the PWA reads annotations/progress/collections/settings only and ignores
 * unknown user files). Deliberately excluded from backup export/restore for
 * v1 so archive bytes stay exactly PWA-compatible.
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

    /** The doc's saved override, or null when it reads with the global prefs. */
    suspend fun get(docId: String): ReadingPrefs? =
        runCatching {
            val text = store.readText(DOC_SETTINGS_PATH)
            json.decodeFromString(serializer, text)[docId]
        }.getOrNull()

    /** Saves (or removes when [prefs] is null) the doc's override. */
    suspend fun set(docId: String, prefs: ReadingPrefs?) {
        val map = runCatching {
            json.decodeFromString(serializer, store.readText(DOC_SETTINGS_PATH))
        }.getOrElse { emptyMap() }
        val updated = if (prefs == null) map - docId else map + (docId to prefs)
        when {
            updated.isEmpty() && store.exists(DOC_SETTINGS_PATH) ->
                store.remove(DOC_SETTINGS_PATH)
            else -> store.write(
                DOC_SETTINGS_PATH,
                json.encodeToString(serializer, updated)
            )
        }
    }
}
