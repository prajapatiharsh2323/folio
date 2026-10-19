package dev.folio.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Explicit interface type scale (design system §3.2). System sans-serif on
 * purpose: the UI font and the book's reading font are separate systems, and
 * the reader canvas keeps its own family/player preferences.
 *
 * Naming bridge (design tokens → Material slots):
 *   displayTitle 28/34 600 → displayMedium
 *   screenTitle  24/30 600 → headlineMedium
 *   sectionTitle 17/24 600 → titleLarge
 *   bookTitle    16/22 500 → titleMedium
 *   titleSmall   15/21 600 → titleSmall
 *   body         15/22 400 → bodyLarge
 *   bodySmall    13/19 400 → bodyMedium
 *   metadata     12/16 500 → bodySmall / labelMedium
 *   label        13/18 500 → labelLarge
 *   micro        11/14 500 → labelSmall
 *
 * Use [FolioTypography] (not Typography()) everywhere; never style by copying
 * one-off alphas/weights per call site.
 */
val FolioTypography = Typography(
    // displayTitle
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    // screenTitle
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    // sectionTitle
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    // bookTitle
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    // titleSmall (sheet/dialog heading)
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    // body
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    // bodySmall
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    // metadata (rare: quiet secondary values)
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // label (buttons, field labels, chips)
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // micro (non-essential metadata only)
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)
