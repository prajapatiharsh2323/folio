package dev.folio.mobile.core.reader

import dev.folio.mobile.core.model.ReadingPrefs

/**
 * Maps schema-1 [ReadingPrefs] (the PWA's byte-compat settings shape) onto the
 * primitive knobs of the Readium EPUB navigator.
 *
 * The pure core works on primitives so it stays JVM-testable (Readium
 * `Color`/`Theme` values need the Android runtime — see ProgressBridge KDoc).
 * `ReaderPrefsAndroid.toEpubPreferences()` performs the thin Android-side
 * conversion for `ReaderActivity`.
 *
 * VALUE SEMANTICS (PWA parity):
 * - `fontSize` is PX in the schema; Readium's `fontSize` preference is a
 *   RELATIVE factor (1.0 = 100% of the html base ~16px) → divide by 16.
 * - `lineHeight` is a unitless multiplier in both → passthrough.
 * - `margin` is PX in the schema; Readium `pageMargins` is an em-factor of the
 *   base gutter → divide by 16 (v1 approximation, revisit after R2 feedback).
 * - Themes use the exact PWA tokens from `apps/web/src/reader/epub-engine.ts`
 *   THEME_COLORS so the canvas colors match the web reader.
 * - `spread: none|auto|both`, `flow: paginated|scrolled`, `justify`,
 *   `hyphenate` map 1:1.
 * - `fontFamily: publisher` → null (don't override the publisher's choice).
 * - `letterSpacing` / `paragraphSpacing` (audit EPUB-011): em pass-through to
 *   the ReadiumCSS user vars — the schema's dormant settings are now real.
 */
object ReaderPrefsMapper {

    /** PWA tokens verbatim (epub-engine.ts THEME_COLORS) */
    private val THEME_COLORS: Map<String, ThemeTokens> = mapOf(
        "light" to ThemeTokens(
            bg = "#FAFAF7", surface = "#FFFFFF", text = "#1C1B19",
            muted = "#6B6860", border = "#E6E3DC", accent = "#2F6F5E"
        ),
        "warm" to ThemeTokens(
            bg = "#F4ECD8", surface = "#FBF5E6", text = "#3B3226",
            muted = "#7A6C58", border = "#E0D4B8", accent = "#8A5A2B"
        ),
        "dark" to ThemeTokens(
            bg = "#121214", surface = "#1C1C20", text = "#E6E3DC",
            muted = "#9A968C", border = "#2C2C32", accent = "#7FC8B2"
        ),
        "true-black" to ThemeTokens(
            bg = "#000000", surface = "#0B0B0E", text = "#C8C4BC",
            muted = "#8B887F", border = "#232329", accent = "#7FC8B2"
        )
    )

    data class ThemeTokens(
        val bg: String,
        val surface: String,
        val text: String,
        val muted: String,
        val border: String,
        val accent: String
    )

    /** Primitive preferences handed to the navigator's Android adapter. */
    data class MappedReaderPrefs(
        /** Readium relative font size (schema px / 16). */
        val fontScale: Double,
        /** Unitless line-height multiplier. */
        val lineHeight: Double,
        /** Readium pageMargins em-factor (schema px / 16 — v1 approximation). */
        val pageMargins: Double,
        /** true = scrolled mode. */
        val scroll: Boolean,
        /** "auto" | "never" | "always". */
        val spread: String,
        /** "start" | "justify". */
        val textAlign: String,
        val hyphens: Boolean,
        /** "light" | "dark" | "sepia" (Readium enum names). */
        val theme: String,
        /** Canvas background, 0xAARRGGBB. */
        val backgroundArgb: Int,
        /** Text color, 0xAARRGGBB. */
        val textArgb: Int,
        /** Font family key, null = honor publisher styles. */
        val fontFamily: String?,
        /**
         * Audit EPUB-011: pass-through of the schema's dormant CSS prefs.
         * Values are em-units in both the schema and ReadiumCSS
         * (--USER__letterSpacing / --USER__paragraphSpacing): exact 1:1.
         * The schema default is 0.0; ReaderPrefsAndroid maps that to null so
         * untouched users do not gain explicit `0` styles that stomp publisher
         * CSS hand-tuning.
         */
        val letterSpacing: Double = 0.0,
        val paragraphSpacing: Double = 0.0
    )

    fun mapPrefs(prefs: ReadingPrefs, systemDark: Boolean = false): MappedReaderPrefs {
        val key = resolveThemeKey(prefs.theme, systemDark)
        val tokens = THEME_COLORS[key] ?: THEME_COLORS.getValue("light")
        return MappedReaderPrefs(
            fontScale = prefs.fontSize / 16.0,
            lineHeight = prefs.lineHeight,
            pageMargins = prefs.margin / 16.0,
            scroll = prefs.flow != "paginated",
            spread = when (prefs.spread) {
                "auto" -> "auto"
                "both" -> "always"
                else -> "never"
            },
            textAlign = if (prefs.justify) "justify" else "start",
            hyphens = prefs.hyphenate,
            theme = when (key) {
                "dark", "true-black" -> "dark"
                "warm" -> "sepia"
                else -> "light"
            },
            backgroundArgb = argbOf(tokens.bg),
            textArgb = argbOf(tokens.text),
            fontFamily = prefs.fontFamily.takeUnless { it == "publisher" },
            letterSpacing = prefs.letterSpacing,
            paragraphSpacing = prefs.paragraphSpacing
        )
    }

    /** "system" resolves to the OS dark-mode setting (PWA uses a media query). */
    fun resolveThemeKey(raw: String, systemDark: Boolean = false): String = when (raw) {
        "warm", "dark", "true-black" -> raw
        "system" -> if (systemDark) "dark" else "light"
        else -> "light"
    }

    /**
     * True when the resolved reading theme paints the app chrome dark — also
     * the flag the dictionary sheet understands via [dev.folio.mobile.ui.theme.FolioMobileTheme].
     */
    fun resolvedThemeIsDark(raw: String, systemDark: Boolean = false): Boolean =
        resolveThemeKey(raw, systemDark).let { it == "dark" || it == "true-black" }

    private fun argbOf(hex: String): Int =
        java.lang.Long.decode(hex.replaceFirst("#", "0xFF")).toInt()
}
