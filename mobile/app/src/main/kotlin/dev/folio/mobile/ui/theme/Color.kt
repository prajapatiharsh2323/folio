package dev.folio.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Folio semantic palette ("Folio Mobile — Premium UI Design System" §2).
// Two independent color systems live here:
//  - App UI tokens (§2.1/§2.2): Library, Notes, Settings and reader-sheet chrome
//    exposed through MaterialTheme + [LocalExtendedFolioColors].
//  - Reader-canvas palette (§2.3): the EPUB page itself — those colors live in
//    the reader package and follow the user's/book's reading theme, never the
//    app UI theme. Do not swap the two.

// App UI · light
val AppLightCanvas = Color(0xFFF7F6F1)          // app page background
val AppLightSurface = Color(0xFFFFFEFB)         // sheets, elevated groups
val AppLightSurfaceSubtle = Color(0xFFF1F0E9)   // inputs, quiet panels
val AppLightSurfaceRaised = Color(0xFFFFFFFF)   // dialogs (rare elevation)
val AppLightTextPrimary = Color(0xFF22251F)     // deep ink
val AppLightTextSecondary = Color(0xFF676A61)   // helper copy
val AppLightTextMuted = Color(0xFF87897F)       // non-essential metadata only
val AppLightBorder = Color(0xFFE1E0D6)          // hairlines, outlines
val AppLightAccent = Color(0xFF355847)          // evergreen primary
val AppLightAccentPressed = Color(0xFF294638)
val AppLightAccentContainer = Color(0xFFE2EAE1) // selected chips
val AppLightOnPrimaryContainer = Color(0xFF244234)
val AppLightOnAccent = Color(0xFFFFFDF7)
val AppLightSuccess = Color(0xFF356B4D)
val AppLightWarning = Color(0xFF94692E)         // recovery notices
val AppLightDanger = Color(0xFFA44C43)          // destructive/error only
val AppLightDangerContainer = Color(0xFFF6E4E1)
val AppLightOnDangerContainer = Color(0xFF5C241E)
val AppLightScrim = Color(0x99141713)

// App UI · dark
val AppDarkCanvas = Color(0xFF141713)           // not pure black (§2.4)
val AppDarkSurface = Color(0xFF1C211C)
val AppDarkSurfaceSubtle = Color(0xFF242B24)
val AppDarkSurfaceRaised = Color(0xFF293029)    // dialogs
val AppDarkTextPrimary = Color(0xFFECEFE7)
val AppDarkTextSecondary = Color(0xFFB2B7AB)
val AppDarkTextMuted = Color(0xFF8D968A)
val AppDarkBorder = Color(0xFF343D35)
val AppDarkAccent = Color(0xFFA7C4A6)           // sage accent
val AppDarkAccentPressed = Color(0xFFBED4BB)
val AppDarkAccentContainer = Color(0xFF2B3C2F)
val AppDarkOnPrimaryContainer = Color(0xFFCFE0CC)
val AppDarkOnAccent = Color(0xFF162017)
val AppDarkSuccess = Color(0xFF8CC39B)
val AppDarkWarning = Color(0xFFD8B77E)
val AppDarkDanger = Color(0xFFE0A09A)
val AppDarkDangerContainer = Color(0xFF4A2823)
val AppDarkOnDangerContainer = Color(0xFFF2CDC8)
val AppDarkScrim = Color(0x99000000)

// Quiet ink/tonal error pair used for DANGER text on light containers.
val AppLightOnError = Color(0xFFFFFDF7)
val AppDarkOnError = Color(0xFF141713)

// Placeholder cover tints (§7.5): a restrained green/warm-neutral family used
// ONLY for generated book covers (no real cover image present). Keyed by the
// stable LibraryUi.colorSeed so rows repeat deliberately instead of randomly.
val CoverTints = listOf(
    Color(0xFFD8DDD0), // sage grey
    Color(0xFFE5DECD), // parchment
    Color(0xFFCFD8CF), // eucalyptus
    Color(0xFFE0D4C6), // clay
    Color(0xFFCFD4D9), // slate
    Color(0xFFE2DBD0), // oat
    Color(0xFFD4CEC0), // stone
    Color(0xFFCBD3C4), // moss
)

// Ink used for cover-placeholders' initial/title text (readable on every tint).
val CoverInk = Color(0xFF3A4038)

// Highlight swatches for the reader's selection toolbar (annotation chips live
// in reader/AnnotationMenu.kt; these are the base colors shared with the PWA).
val HighlightYellow = Color(0xFFFFF3B0)
val HighlightGreen = Color(0xFFCDE8C2)
val HighlightBlue = Color(0xFFBFDFF7)
val HighlightPink = Color(0xFFF8CFE0)
