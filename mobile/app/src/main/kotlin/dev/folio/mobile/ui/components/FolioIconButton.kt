package dev.folio.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.mobile.ui.theme.FolioDimensions

/**
 * Folio icon button (design system §7.2): 48×48dp hit area for 22–24dp artwork,
 * no permanent background — the ripple indicates press/focus. Icon color comes
 * from `tint` (default: the ambient content color).
 */
@Composable
fun FolioIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // LocalContentColor, not Color.Unspecified: M3 Icon treats Unspecified as
    // "no tint" and falls back to the vector's baked-in black (design-system
    // §2.4 forbids pure black, and it vanishes in dark theme). Call sites can
    // still pass Unspecified for a genuinely multi-colour vector.
    tint: Color = LocalContentColor.current,
    artwork: Dp = 22.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(FolioDimensions.touchTarget)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(artwork),
        )
    }
}
