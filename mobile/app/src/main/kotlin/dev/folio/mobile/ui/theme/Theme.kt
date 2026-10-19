package dev.folio.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Complete app-interface schemes (design system §2). Semantic roles are mapped
// once per light/dark; screens read MaterialTheme.colorScheme and must not
// invent ad hoc grays/greens locally.
private val LightColors: ColorScheme = lightColorScheme(
    primary = AppLightAccent,
    onPrimary = AppLightOnAccent,
    primaryContainer = AppLightAccentContainer,
    onPrimaryContainer = AppLightOnPrimaryContainer,
    secondary = AppLightAccentPressed,
    onSecondary = AppLightOnAccent,
    secondaryContainer = AppLightSurfaceSubtle,
    onSecondaryContainer = AppLightTextPrimary,
    tertiary = AppLightWarning,
    onTertiary = AppLightOnAccent,
    tertiaryContainer = AppLightSurfaceSubtle,
    onTertiaryContainer = AppLightTextPrimary,
    background = AppLightCanvas,
    onBackground = AppLightTextPrimary,
    surface = AppLightSurface,
    onSurface = AppLightTextPrimary,
    surfaceVariant = AppLightSurfaceSubtle,
    onSurfaceVariant = AppLightTextSecondary,
    surfaceContainerLowest = AppLightSurfaceRaised,
    surfaceContainerLow = AppLightSurface,
    surfaceContainer = AppLightSurface,
    surfaceContainerHigh = AppLightSurfaceSubtle,
    surfaceContainerHighest = AppLightSurfaceSubtle,
    surfaceDim = AppLightCanvas,
    inverseSurface = AppDarkSurface,
    inverseOnSurface = AppDarkTextPrimary,
    inversePrimary = AppDarkAccent,
    outline = AppLightBorder,
    outlineVariant = AppLightBorder,
    error = AppLightDanger,
    onError = AppLightOnError,
    errorContainer = AppLightDangerContainer,
    onErrorContainer = AppLightOnDangerContainer,
    scrim = AppLightScrim,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = AppDarkAccent,
    onPrimary = AppDarkOnAccent,
    primaryContainer = AppDarkAccentContainer,
    onPrimaryContainer = AppDarkOnPrimaryContainer,
    secondary = AppDarkAccentPressed,
    onSecondary = AppDarkOnAccent,
    secondaryContainer = AppDarkSurfaceSubtle,
    onSecondaryContainer = AppDarkTextPrimary,
    tertiary = AppDarkWarning,
    onTertiary = AppDarkOnAccent,
    tertiaryContainer = AppDarkSurfaceSubtle,
    onTertiaryContainer = AppDarkTextPrimary,
    background = AppDarkCanvas,
    onBackground = AppDarkTextPrimary,
    surface = AppDarkSurface,
    onSurface = AppDarkTextPrimary,
    surfaceVariant = AppDarkSurfaceSubtle,
    onSurfaceVariant = AppDarkTextSecondary,
    surfaceContainerLowest = AppDarkCanvas,
    surfaceContainerLow = AppDarkSurface,
    surfaceContainer = AppDarkSurface,
    surfaceContainerHigh = AppDarkSurfaceSubtle,
    surfaceContainerHighest = AppDarkSurfaceSubtle,
    surfaceDim = AppDarkCanvas,
    inverseSurface = AppLightSurface,
    inverseOnSurface = AppLightTextPrimary,
    inversePrimary = AppLightAccent,
    outline = AppDarkBorder,
    outlineVariant = AppDarkBorder,
    error = AppDarkDanger,
    onError = AppDarkOnError,
    errorContainer = AppDarkDangerContainer,
    onErrorContainer = AppDarkOnDangerContainer,
    scrim = AppDarkScrim,
)

// M3 has no success/warning slots; Folio exposes them as extended tokens
// (design system §2.4: recovery warnings must be semantic, not improvised gray).
data class FolioExtendedColors(
    val success: Color,
    val warning: Color,
    val onWarning: Color,
)

private val LightExtended = FolioExtendedColors(
    success = AppLightSuccess,
    warning = AppLightWarning,
    onWarning = AppLightOnAccent,
)

private val DarkExtended = FolioExtendedColors(
    success = AppDarkSuccess,
    warning = AppDarkWarning,
    onWarning = AppDarkOnAccent,
)

val LocalExtendedFolioColors = staticCompositionLocalOf { LightExtended }

@Composable
fun extendedFolioColors(): FolioExtendedColors = LocalExtendedFolioColors.current

private val FolioShapes = Shapes(
    extraSmall = RoundedCornerShape(FolioRadii.control),
    small = RoundedCornerShape(FolioRadii.control),
    medium = RoundedCornerShape(FolioRadii.field),
    large = RoundedCornerShape(FolioRadii.card),
    extraLarge = RoundedCornerShape(FolioRadii.sheet),
)

@Composable
fun FolioMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color (Material You) stays off so the Folio bookish palette is
    // stable across devices (kept from the previous theme; revisit at phase N5).
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalExtendedFolioColors provides if (darkTheme) DarkExtended else LightExtended
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FolioTypography,
            shapes = FolioShapes,
            content = content
        )
    }
}
