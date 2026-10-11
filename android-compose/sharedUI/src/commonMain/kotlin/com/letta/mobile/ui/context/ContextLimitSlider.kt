package com.letta.mobile.ui.context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.context.limit.ContextLimitAdvice
import com.letta.mobile.data.context.limit.ContextLimitScope
import com.letta.mobile.data.context.limit.ContextLimitStops
import com.letta.mobile.data.context.limit.ContextLimitWarning
import com.letta.mobile.ui.modelcontrol.StepSlider
import com.letta.mobile.ui.modelcontrol.StepSliderCallbacks
import com.letta.mobile.ui.modelcontrol.StepSliderSpec
import com.letta.mobile.ui.theme.AgentContextDimens
import com.letta.mobile.ui.theme.LettaDimens

object ContextLimitTags {
    const val SLIDER = "context_limit_slider"
    const val VALUE = "context_limit_slider_value"
    const val STATUS = "context_limit_status"
    const val SCOPE = "context_limit_scope"
    const val COMPACT = "context_limit_compact"
    const val UNSUPPORTED = "context_limit_unsupported"
}

/** The slider's stops, the limit in force, what the conversation holds, and where a change lands. */
@Immutable
data class ContextLimitSetting(
    val stops: ContextLimitStops,
    /** The limit in force now; null when unknown (the thumb starts at the model's maximum). */
    val current: Int?,
    val usedTokens: Int?,
    val scope: ContextLimitScope,
    val applying: Boolean = false,
    /** letta-code's refusal of the last change, shown until the next one. */
    val failure: String? = null,
)

/** The sheet's context-limit control: a slider, or the reason there is none. */
sealed interface ContextLimitControl {
    class Adjustable(val setting: ContextLimitSetting, val onApply: (tokens: Int) -> Unit) : ContextLimitControl

    data object Unsupported : ContextLimitControl
}

/**
 * letta-mobile-joigh: the context-limit slider — how much of the model's window the agent (on its
 * default conversation) or this conversation actually uses. A [StepSlider] over [ContextLimitStops]
 * applied on release; under it one status line for the stop under the thumb (where letta-code
 * auto-compacts, or a warning, with a Compact action when the limit is below what the
 * conversation already holds) and the scope line. The status row keeps one height whatever it
 * says, so dragging never moves the sheet.
 */
@Composable
fun ContextLimitSlider(
    setting: ContextLimitSetting,
    onApply: (tokens: Int) -> Unit,
    onCompact: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val stops = setting.stops
    val currentIndex = stops.indexOf(setting.current)
    var previewIndex by remember(stops, currentIndex) { mutableIntStateOf(currentIndex) }
    val advice = ContextLimitAdvice.of(stops.valueAt(previewIndex), setting.usedTokens, stops.modelMax)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        StepSlider(
            spec = StepSliderSpec(
                name = ContextLimitStrings.LIMIT,
                count = stops.count,
                currentIndex = currentIndex,
                sliderTag = ContextLimitTags.SLIDER,
                valueTag = ContextLimitTags.VALUE,
                valueMinWidth = AgentContextDimens.limitValueWidth,
            ),
            callbacks = StepSliderCallbacks(
                label = { ContextLimitStrings.valueLabel(stops.valueAt(it), stops.modelMax) },
                onApply = { onApply(stops.valueAt(it)) },
                onPositionChange = { previewIndex = it },
            ),
            enabled = !setting.applying,
        )
        LimitStatusRow(setting, advice, onCompact)
        Text(
            text = ContextLimitStrings.scope(setting.scope),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ContextLimitTags.SCOPE),
        )
    }
}

@Composable
private fun LimitStatusRow(setting: ContextLimitSetting, advice: ContextLimitAdvice, onCompact: (() -> Unit)?) {
    val warns = setting.failure != null || advice.warning != ContextLimitWarning.None
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = AgentContextDimens.limitStatusHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(
            text = ContextLimitStrings.status(advice, setting.usedTokens, setting.stops.maxKnown, setting.failure),
            style = MaterialTheme.typography.labelSmall,
            color = if (warns) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.weight(1f).testTag(ContextLimitTags.STATUS),
        )
        if (onCompact != null && advice.warning == ContextLimitWarning.BelowUsage) {
            TextButton(onClick = onCompact, modifier = Modifier.testTag(ContextLimitTags.COMPACT)) {
                Text(ContextLimitStrings.COMPACT)
            }
        }
    }
}
