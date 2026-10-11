package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.roundToInt

/** A [StepSlider]'s name, how many stops it has, the stop in force now, and its test tags. */
@Immutable
data class StepSliderSpec(
    val name: String,
    val count: Int,
    val currentIndex: Int,
    val sliderTag: String,
    val valueTag: String,
    /** Reserved width of the value text, so a longer label never shifts the track. */
    val valueMinWidth: Dp = Dp.Unspecified,
)

/** What a [StepSlider] reports: each stop the thumb lands on, and the stop it is released on. */
class StepSliderCallbacks(
    val label: (index: Int) -> String,
    val onApply: (index: Int) -> Unit,
    val onPositionChange: (index: Int) -> Unit = {},
)

/**
 * letta-mobile-3io8k / joigh: a discrete slider over [StepSliderSpec.count] stops — the reasoning
 * effort and the context limit. It snaps to stops (with a haptic tick per stop where the platform
 * has haptics), names the value beside it and to accessibility, moves one stop per arrow key
 * (Material's own slider keys), and applies only when the drag is released (or a key or
 * accessibility action sets it) — never per drag tick, and never when it lands where it started.
 */
@Composable
fun StepSlider(spec: StepSliderSpec, callbacks: StepSliderCallbacks, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val lastIndex = (spec.count - 1).coerceAtLeast(0)
    var position by remember(spec.count, spec.currentIndex) { mutableFloatStateOf(spec.currentIndex.toFloat()) }
    val haptics = LocalHaptics.current
    val label = callbacks.label(position.roundToInt())
    fun moveTo(index: Int) {
        val clamped = index.coerceIn(0, lastIndex)
        if (clamped != position.roundToInt()) {
            haptics.play(LettaHapticCue.SegmentTick)
            callbacks.onPositionChange(clamped)
        }
        position = clamped.toFloat()
    }
    fun apply() {
        val index = position.roundToInt()
        if (index != spec.currentIndex) callbacks.onApply(index)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(text = spec.name, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = position,
            onValueChange = { moveTo(it.roundToInt()) },
            onValueChangeFinished = ::apply,
            enabled = enabled,
            valueRange = 0f..lastIndex.toFloat(),
            steps = (spec.count - 2).coerceAtLeast(0),
            modifier = Modifier
                .weight(1f)
                .testTag(spec.sliderTag)
                .semantics {
                    contentDescription = spec.name
                    stateDescription = label
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = spec.valueMinWidth).testTag(spec.valueTag),
        )
    }
}
