package dev.folio.mobile.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.folio.mobile.ui.theme.FolioDimensions
import dev.folio.mobile.ui.theme.FolioRadii
import dev.folio.mobile.ui.theme.FolioSpacing

/**
 * Folio action buttons (design system §7.1): one filled primary, one outlined
 * secondary, one destructive text action. Shared height/corner/padding so
 * buttons pair cleanly in any row.
 */
@Composable
fun FolioFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(FolioRadii.field),
        contentPadding = PaddingValues(horizontal = FolioSpacing.x5, vertical = 13.dp),
        modifier = modifier.heightIn(min = FolioDimensions.buttonHeight),
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun FolioOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(FolioRadii.field),
        contentPadding = PaddingValues(horizontal = FolioSpacing.x5, vertical = 13.dp),
        modifier = modifier.heightIn(min = FolioDimensions.buttonHeight),
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

/**
 * Destructive action styling: `danger` icon/label, never visually equal to a
 * routine primary action next to it (design system §7.1).
 */
@Composable
fun FolioDestructiveTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(FolioRadii.field),
        contentPadding = PaddingValues(horizontal = FolioSpacing.x4, vertical = 11.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
        ),
        modifier = modifier.heightIn(min = FolioDimensions.touchTarget),
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}
