package com.letta.mobile.ui.components.foundation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable

/**
 * The brand's slider colours: the thumb and active track are the primary (vermilion), the inactive track is the
 * neutral surfaceContainerHighest, and the stop ticks are onSurfaceVariant at low alpha on both tracks. Material's
 * defaults draw the ticks and the active ticks in the secondary roles, which put stray coloured dots on the track.
 */
@Composable
fun lettaSliderColors(): SliderColors {
    val scheme = MaterialTheme.colorScheme
    val tick = scheme.onSurfaceVariant.copy(alpha = SLIDER_TICK_ALPHA)
    return SliderDefaults.colors(
        thumbColor = scheme.primary,
        activeTrackColor = scheme.primary,
        inactiveTrackColor = scheme.surfaceContainerHighest,
        activeTickColor = tick,
        inactiveTickColor = tick,
    )
}

/** How strongly a slider's stop ticks keep onSurfaceVariant: a hint of the stops, not a row of dots. */
private const val SLIDER_TICK_ALPHA = 0.38f
