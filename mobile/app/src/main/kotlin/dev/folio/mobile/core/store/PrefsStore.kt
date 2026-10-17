package dev.folio.mobile.core.store

import dev.folio.mobile.core.model.ReadingPrefs
import kotlinx.serialization.json.Json

/**
 * Persistence for reader preferences at `library/user/settings.json`.
 *
 * BYTE-COMPAT CONTRACT: the path is part of the schema-1 backup contract
 * (packages/core/src/backup/export-zip.ts lists it; import-zip.ts copies it
 * verbatim). Content is a bare [ReadingPrefs] object with ALL fields present —
 * matching the PWA's DEFAULT_READING_PREFS (apps/web/src/state/library-store.ts).
 * Unknown keys are tolerated on read so future PWA additions don't break us.
 * (Whitespace differs from `JSON.stringify(x, null, 2)` — kotlinx prettyPrint
 * uses 4 spaces; contract parity is at the parsed-value level, like the rest
 * of the mobile-side suite.)
 */
class PrefsStore(private val store: FileStore) {

    companion object {
        const val SETTINGS_PATH = "library/user/settings.json"
    }

    private val json = Json {
        // JSON.stringify(x, null, 2) parity with the PWA
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun saveReadingPrefs(prefs: ReadingPrefs) {
        store.write(SETTINGS_PATH, json.encodeToString(ReadingPrefs.serializer(), prefs))
    }

    /** Returns defaults when the file is missing or unreadable (never null). */
    suspend fun loadReadingPrefs(): ReadingPrefs =
        runCatching {
            json.decodeFromString(
                ReadingPrefs.serializer(),
                store.readText(SETTINGS_PATH)
            )
        }.getOrDefault(ReadingPrefs())
}
