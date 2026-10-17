package dev.folio.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    background = FolioLightBg,
    surface = FolioLightSurface,
    onBackground = FolioLightText,
    onSurface = FolioLightText,
    primary = FolioLightAccent,
    onPrimary = FolioLightSurface,
    surfaceVariant = FolioLightBorder,
    onSurfaceVariant = FolioLightMuted
)

private val DarkColors = darkColorScheme(
    background = FolioDarkBg,
    surface = FolioDarkSurface,
    onBackground = FolioDarkText,
    onSurface = FolioDarkText,
    primary = FolioDarkAccent,
    onPrimary = FolioBlackBg,
    surfaceVariant = FolioDarkBorder,
    onSurfaceVariant = FolioDarkMuted
)

@Composable
fun FolioMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color (Material You) is disabled for now so the Folio bookish
    // palette stays consistent across devices; revisit at phase N5.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
