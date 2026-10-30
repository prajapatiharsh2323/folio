package dev.folio.mobile.ui.theme

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.folio.mobile.core.store.AppAppearanceStore
import dev.folio.mobile.core.store.FileStore

/**
 * Process-lifetime holder for the APP-INTERFACE theme key (M-26).
 *
 * Why this exists: every activity (Library / Notes / Settings) used to
 * snapshot the stored appearance into a plain `val` captured at
 * `setContent` time, so choosing Light/Dark inside Settings only moved the
 * segment indicator — the visible session never re-themed, and a screen left
 * open across the change (the Library sitting under Settings) kept the old
 * palette until its next process creation.
 *
 * Contract:
 * - Each activity calls [ensure] ONCE before `setContent` (a blocking tiny
 *   read, same flash-prevention as `AppAppearanceStore.loadThemeSync`). The
 *   first call after process start loads from disk; the rest are no-ops.
 * - Activities read [key] inside composition — it is snapshot state, so any
 *   whose `FolioMobileTheme(darkTheme = …)` argument derives from it re-themes
 *   the moment Settings writes through [set].
 * - [set] updates the UI immediately; persistence stays in Settings via
 *   `AppAppearanceStore.save()` (corrupt-safe per the store's own contract).
 */
object FolioAppTheme {

    /** Live app-theme key; readers default to System until the first [ensure]. */
    val key: MutableState<String> =
        mutableStateOf(AppAppearanceStore.KEY_SYSTEM)

    /**
     * Seeds the holder from the persisted appearance once per process start
     * and returns the current key. Called before `setContent` so the first
     * frame already carries the stored choice instead of flashing the system
     * theme.
     */
    fun ensure(store: FileStore): String {
        if (!loaded) {
            key.value = AppAppearanceStore.loadThemeSync(store)
            loaded = true
        }
        return key.value
    }

    /** Immediate UI write-through; persistence remains the caller's job. */
    fun set(value: String) {
        check(value in AppAppearanceStore.VALID_KEYS) {
            "Unknown app appearance key: $value"
        }
        key.value = value
    }

    private var loaded = false
}
