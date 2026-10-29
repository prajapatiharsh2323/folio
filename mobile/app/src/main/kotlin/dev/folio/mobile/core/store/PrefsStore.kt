package dev.folio.mobile.core.store

import dev.folio.mobile.core.library.LibraryFileCorruptException
import dev.folio.mobile.core.library.RECOVERY_DIR
import dev.folio.mobile.core.library.ReadResult
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.util.IsoTime
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

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
 *
 * Data safety: a settings file that exists but cannot be decoded is NOT the
 * same as "no settings yet". [readReadingPrefs] reports it as
 * [ReadResult.Corrupt], [loadReadingPrefs] throws
 * [LibraryFileCorruptException] (callers show defaults with an explicit
 * message), and [saveReadingPrefs] preserves the unreadable bytes under
 * [RECOVERY_DIR] (verified byte-identical) before overwriting them — if the
 * preservation cannot be confirmed, the save aborts and the corrupt source
 * stays exactly as it was.
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

    /**
     * Typed read: Missing = no settings saved yet; Corrupt = the file exists
     * but cannot be decoded and must be surfaced, not replaced silently.
     */
    suspend fun readReadingPrefs(): ReadResult<ReadingPrefs> {
        if (!store.exists(SETTINGS_PATH)) return ReadResult.Missing
        return try {
            ReadResult.Loaded(
                json.decodeFromString(
                    ReadingPrefs.serializer(),
                    store.readText(SETTINGS_PATH)
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ReadResult.Corrupt(SETTINGS_PATH, e.message ?: e.toString())
        }
    }

    /**
     * Returns defaults only when nothing is saved yet. A corrupt file throws
     * [LibraryFileCorruptException] so the caller can say so out loud instead
     * of presenting factory defaults as the user's saved choices.
     */
    suspend fun loadReadingPrefs(): ReadingPrefs = when (val result = readReadingPrefs()) {
        is ReadResult.Loaded -> result.value
        ReadResult.Missing -> ReadingPrefs()
        is ReadResult.Corrupt -> throw LibraryFileCorruptException(
            result.path,
            result.message
        )
    }

    suspend fun saveReadingPrefs(prefs: ReadingPrefs) {
        // Never overwrite unreadable user settings without keeping the bytes.
        if (readReadingPrefs() is ReadResult.Corrupt) {
            // Throws when the recovery copy is not written AND verifiably
            // byte-identical — the corrupt source must stay intact.
            preserveCorrupt()
        }
        store.write(SETTINGS_PATH, json.encodeToString(ReadingPrefs.serializer(), prefs))
    }

    /**
     * Preserves the unreadable settings bytes under [RECOVERY_DIR] before the
     * caller overwrites them.
     *
     * Preservation is only "done" when the copy exists and reads back
     * byte-identical to the source. Anything else (the write itself fails,
     * the copy vanishes, silent bit-rot) throws and [saveReadingPrefs] aborts:
     * overwriting the user's unreadable settings without a verified copy would
     * destroy the only copy of their data. Callers already handle a failing
     * save with a visible error, so throwing is the safe failure mode.
     */
    private suspend fun preserveCorrupt() {
        val bytes = store.read(SETTINGS_PATH)
        val stamp = IsoTime.now().replace(":", "-").replace(".", "-")
        val dest = "$RECOVERY_DIR/settings.json-corrupt-$stamp"
        try {
            store.write(dest, bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException(
                "Could not preserve unreadable reader settings before replacing " +
                    "them (${error.message}) — save aborted, your old settings file is unchanged",
                error
            )
        }
        val copied = try {
            store.exists(dest) && store.read(dest).contentEquals(bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            false
        }
        check(copied) {
            "Recovery copy of unreadable reader settings not confirmed at $dest — " +
                "save aborted, your old settings file is unchanged"
        }
        android.util.Log.w(
            "PrefsStore",
            "Preserved unreadable reader settings at $dest before replacing them"
        )
    }
}
