package dev.folio.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Folio's quiet reading slider.
 *
 * A thin, low-contrast track whose inactive half recedes toward the page, a
 * restrained primary accent for the active half, and a small diamond-shaped
 * thumb centred exactly on the track line. Built on Material 3's [Slider] so
 * drag, tap, steps, keyboard and TalkBack range semantics stay native — only
 * the visuals are replaced.
 *
 * The caller owns persistence timing: use `onValueChangeFinished` for disk
 * writes so one drag emits one persisted update; `onValueChange` gives live
 * feedback. Robustness contract: input values are clipped to [valueRange] and
 * NaN/∞ never reaches the callback, so no caller can persist an invalid value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolioSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Guard rails: never render or emit a non-finite or out-of-range value.
    val safeValue = if (value.isFinite()) {
        value.coerceIn(valueRange.start, valueRange.endInclusive)
    } else {
        valueRange.start
    }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it.isFinite() && it > 0f }
    val fraction = span?.takeIf { it > 0f }?.let {
        ((safeValue - valueRange.start) / it).coerceIn(0f, 1f)
    } ?: 0f

    val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    val colors = androidx.compose.material3.SliderDefaults.colors(
        thumbColor = androidx.compose.ui.graphics.Color.Transparent,
        activeTrackColor = androidx.compose.ui.graphics.Color.Transparent,
        inactiveTrackColor = androidx.compose.ui.graphics.Color.Transparent,
        activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
        inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
    )

    Slider(
        value = safeValue,
        onValueChange = { raw ->
            // NaN/∞ from a malformed state must not propagate to the caller.
            if (raw.isFinite()) {
                onValueChange(raw.coerceIn(valueRange.start, valueRange.endInclusive))
            }
        },
        modifier = modifier
            .height(48.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps.coerceAtLeast(0),
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Compact diamond/lozenge marker: a 13dp square rotated 45°
                // with softly rounded corners, centred on the track line.
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .graphicsLayer {
                            rotationZ = 45f
                        }
                        .clip(RoundedCornerShape(3.dp))
                        .background(activeColor),
                )
            }
        },
        track = {
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 4.dp.toPx()
                    val centerY = size.height / 2f
                    val endX = size.width * fraction
                    // Inactive rail: recedes into the background page.
                    drawLine(
                        color = inactiveColor,
                        start = Offset(0f, centerY),
                        end = Offset(size.width, centerY),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                    // Active rail: quiet accent up to the thumb.
                    if (endX > 0f) {
                        drawLine(
                            color = activeColor,
                            start = Offset(0f, centerY),
                            end = Offset(endX, centerY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        },
    )
}
