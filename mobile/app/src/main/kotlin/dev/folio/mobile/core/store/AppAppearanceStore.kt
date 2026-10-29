package dev.folio.mobile.core.store

import dev.folio.mobile.core.library.LibraryFileCorruptException
import dev.folio.mobile.core.library.ReadResult
import dev.folio.mobile.core.util.IsoTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistence for the APP-INTERFACE appearance (System/Light/Dark) at
 * `app-appearance.json` in the app-private filesDir.
 *
 * Deliberately OUTSIDE `library/` and OUTSIDE the schema-1 backup contract:
 * the backup include-list (exportBackupZip/exporting + restore) enumerates
 * library paths only, so this never travels in a backup and a desktop port's
 * user-JSON shapes are untouched. It is Folio UI chrome, not user data.
 *
 * Missing file = valid first run (System). A file that exists but cannot be
 * decoded is surfaced through [read] as [ReadResult.Corrupt] and — same
 * data-safety rule as [PrefsStore] — [save] preserves the unreadable bytes
 * under `library/recovery` (verified byte-identical) before overwriting them;
 * if the preservation cannot be confirmed, the save aborts and the corrupt
 * source stays exactly as it was.
 */
class AppAppearanceStore(private val store: FileStore) {

    companion object {
        const val APP_APPEARANCE_PATH = "app-appearance.json"

        const val KEY_SYSTEM = "system"
        const val KEY_LIGHT = "light"
        const val KEY_DARK = "dark"

        val VALID_KEYS = setOf(KEY_SYSTEM, KEY_LIGHT, KEY_DARK)

        /**
         * Stored key → dark flag for [FolioMobileTheme] consumers. Any
         * unknown/missing value (including first-run null) means System.
         */
        fun isDark(storedKey: String?, systemDark: Boolean): Boolean = when (storedKey) {
            KEY_LIGHT -> false
            KEY_DARK -> true
            else -> systemDark
        }

        /**
         * Blocking single read for the activity `onCreate` path: the theme must
         * be known BEFORE the first frame to avoid a system→stored flash.
         * Tiny file, one disk read. Corrupt file resolves to System here — the
         * honest corrupt state is surfaced where the user can act on it
         * (Settings shows the corrupt banner); startup must not crash on it.
         */
        fun loadThemeSync(store: FileStore): String = runBlocking {
            try {
                when (val result = AppAppearanceStore(store).read()) {
                    is ReadResult.Loaded -> result.value
                    ReadResult.Missing -> KEY_SYSTEM
                    is ReadResult.Corrupt -> KEY_SYSTEM
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                KEY_SYSTEM
            }
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Typed read: Missing = no appearance saved yet; Corrupt = the file exists
     * but cannot be decoded and must be surfaced, never replaced silently.
     */
    suspend fun read(): ReadResult<String> {
        if (!store.exists(APP_APPEARANCE_PATH)) return ReadResult.Missing
        return try {
            ReadResult.Loaded(
                json.decodeFromString(
                    AppAppearance.serializer(),
                    store.readText(APP_APPEARANCE_PATH)
                ).appTheme
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ReadResult.Corrupt(APP_APPEARANCE_PATH, e.message ?: e.toString())
        }
    }

    /**
     * Returns defaults ("system") only when nothing is saved yet. A corrupt
     * file throws [LibraryFileCorruptException] so callers say so out loud
     * instead of presenting factory defaults as the user's choice.
     */
    suspend fun load(): String = when (val result = read()) {
        is ReadResult.Loaded -> result.value
        ReadResult.Missing -> KEY_SYSTEM
        is ReadResult.Corrupt -> throw LibraryFileCorruptException(
            result.path,
            result.message
        )
    }

    /** Only the three canonical keys are ever written. */
    suspend fun save(key: String) {
        check(key in VALID_KEYS) { "Unknown app appearance key: $key" }
        // Never overwrite unreadable user settings without keeping the bytes
        // (same rule as PrefsStore.saveReadingPrefs).
        if (read() is ReadResult.Corrupt) {
            preserveCorrupt()
        }
        store.write(
            APP_APPEARANCE_PATH,
            json.encodeToString(AppAppearance.serializer(), AppAppearance(appTheme = key))
        )
    }

    /**
     * Preserves the unreadable appearance bytes under `library/recovery`
     * before the caller overwrites them. The write is "done" only when the
     * copy exists and reads back byte-identical; anything else throws and the
     * save aborts with the corrupt source exactly as it was.
     */
    private suspend fun preserveCorrupt() {
        val bytes = store.read(APP_APPEARANCE_PATH)
        val stamp = IsoTime.now().replace(":", "-").replace(".", "-")
        val dest = "library/recovery/app-appearance.json-corrupt-$stamp"
        try {
            store.write(dest, bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException(
                "Could not preserve unreadable app-appearance settings before replacing " +
                    "them (${error.message}) — save aborted, your old file is unchanged",
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
            "Recovery copy of unreadable app-appearance settings not confirmed at $dest — " +
                "save aborted, your old file is unchanged"
        }
        android.util.Log.w(
            "AppAppearanceStore",
            "Preserved unreadable app-appearance file at $dest before replacing it"
        )
    }
}

@Serializable
private data class AppAppearance(val appTheme: String = "system")
