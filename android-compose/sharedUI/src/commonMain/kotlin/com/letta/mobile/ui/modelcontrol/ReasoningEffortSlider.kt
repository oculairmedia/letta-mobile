package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortStops
import com.letta.mobile.data.repository.modelcontrol.ReasoningTier
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.roundToInt

object ReasoningEffortSliderTags {
    const val SLIDER = "reasoning_effort_slider"
    const val VALUE = "reasoning_effort_slider_value"
}

/**
 * letta-mobile-3io8k: one discrete reasoning-effort slider for a model, replacing a row of effort
 * chips under every model. It snaps to [stops] (with a haptic tick per stop where the platform has
 * haptics), names the value beside it and to accessibility, moves one stop per arrow key (Material's own
 * slider keys), and
 * applies only when the drag is released (or a key or accessibility action sets it) — never per
 * drag tick. [current] is the effort the model runs at now; null is Default.
 */
@Composable
fun ReasoningEffortSlider(
    stops: ReasoningEffortStops,
    current: String?,
    onApply: (effort: String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val currentIndex = stops.indexOf(current)
    var position by remember(stops, currentIndex) { mutableFloatStateOf(currentIndex.toFloat()) }
    val haptics = LocalHaptics.current
    val label = effortLabel(stops.valueAt(position.roundToInt()))
    fun moveTo(index: Int) {
        val clamped = index.coerceIn(0, stops.count - 1)
        if (clamped != position.roundToInt()) haptics.play(LettaHapticCue.SegmentTick)
        position = clamped.toFloat()
    }
    fun apply() {
        val index = position.roundToInt()
        if (index != currentIndex) onApply(stops.valueAt(index))
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(text = ModelControlStrings.EFFORT, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = position,
            onValueChange = { moveTo(it.roundToInt()) },
            onValueChangeFinished = ::apply,
            valueRange = 0f..(stops.count - 1).toFloat(),
            steps = (stops.count - 2).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .testTag(ReasoningEffortSliderTags.SLIDER)
                .semantics {
                    contentDescription = ModelControlStrings.EFFORT
                    stateDescription = label
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ReasoningEffortSliderTags.VALUE),
        )
    }
}

/** "Default", or the tier as the picker rows name it ("High"). */
fun effortLabel(effort: String?): String =
    ReasoningTier.of(effort)?.let(ModelControlStrings::tierLabel) ?: effort ?: ModelControlStrings.EFFORT_DEFAULT

