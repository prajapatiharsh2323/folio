package dev.folio.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Folio palette — mirrors apps/web/src/styles/tokens.css so both platforms share
// one visual identity (spec §9: theme tokens light/warm/dark).

// Light
val FolioLightBg = Color(0xFFFAFAF7)
val FolioLightSurface = Color(0xFFFFFFFF)
val FolioLightText = Color(0xFF1C1B19)
val FolioLightMuted = Color(0xFF6B6860)
val FolioLightBorder = Color(0xFFE6E3DC)
val FolioLightAccent = Color(0xFF2F6F5E)

// Warm
val FolioWarmBg = Color(0xFFF4ECD8)
val FolioWarmSurface = Color(0xFFFBF5E6)
val FolioWarmText = Color(0xFF3B3226)
val FolioWarmAccent = Color(0xFF8A5A2B)

// Dark
val FolioDarkBg = Color(0xFF121214)
val FolioDarkSurface = Color(0xFF1C1C20)
val FolioDarkText = Color(0xFFE6E3DC)
val FolioDarkMuted = Color(0xFF9A968C)
val FolioDarkBorder = Color(0xFF2C2C32)
val FolioDarkAccent = Color(0xFF7FC8B2)

// True-black
val FolioBlackBg = Color(0xFF000000)
val FolioBlackSurface = Color(0xFF0B0B0E)
val FolioBlackText = Color(0xFFC8C4BC)

// Highlight colors (≈50% opacity is applied at usage site)
val HighlightYellow = Color(0xFFFFE58A)
val HighlightGreen = Color(0xFFB7E4B0)
val HighlightBlue = Color(0xFFA9D1F5)
val HighlightPink = Color(0xFFF5B7CC)
