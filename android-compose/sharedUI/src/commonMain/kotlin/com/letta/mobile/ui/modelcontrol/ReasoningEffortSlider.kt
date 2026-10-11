package com.letta.mobile.ui.modelcontrol

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortStops
import com.letta.mobile.data.repository.modelcontrol.ReasoningTier

object ReasoningEffortSliderTags {
    const val SLIDER = "reasoning_effort_slider"
    const val VALUE = "reasoning_effort_slider_value"
}

/**
 * letta-mobile-3io8k: one discrete reasoning-effort slider for a model, replacing a row of effort
 * chips under every model: a [StepSlider] over the model's effort stops, applied on release.
 */
@Composable
fun ReasoningEffortSlider(setting: EffortSetting, onApply: (effort: String?) -> Unit, modifier: Modifier = Modifier) {
    val stops = setting.stops
    StepSlider(
        spec = StepSliderSpec(
            name = ModelControlStrings.EFFORT,
            count = stops.count,
            currentIndex = stops.indexOf(setting.current),
            sliderTag = ReasoningEffortSliderTags.SLIDER,
            valueTag = ReasoningEffortSliderTags.VALUE,
        ),
        callbacks = StepSliderCallbacks(
            label = { effortLabel(stops.valueAt(it)) },
            onApply = { onApply(stops.valueAt(it)) },
        ),
        modifier = modifier,
    )
}

/** A model's effort stops and the effort it runs at now (null = Default). */
data class EffortSetting(val stops: ReasoningEffortStops, val current: String?)

/** "Default", or the tier as the picker rows name it ("High"). */
fun effortLabel(effort: String?): String =
    ReasoningTier.of(effort)?.let(ModelControlStrings::tierLabel) ?: effort ?: ModelControlStrings.EFFORT_DEFAULT
