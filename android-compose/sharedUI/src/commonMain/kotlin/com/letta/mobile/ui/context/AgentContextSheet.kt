package com.letta.mobile.ui.context

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.context.AgentContextCardModel
import com.letta.mobile.data.context.CompactAffordance
import com.letta.mobile.data.context.ContextMeter
import com.letta.mobile.data.context.ContextProvenance
import com.letta.mobile.data.context.formatContextTokens
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.data.repository.modelcontrol.ModelPickerState
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortStops
import com.letta.mobile.ui.modelcontrol.ReasoningEffortSlider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.letta.mobile.ui.modelcontrol.LocalModelPickerContextTokens
import com.letta.mobile.ui.modelcontrol.ModelPickerActions
import com.letta.mobile.ui.modelcontrol.ModelPickerContent
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * The model list the sheet shows, when the host can switch models. [onEffortApplied] sets the
 * selected model's reasoning effort (null = Default); null when the host has no effort switch.
 */
data class AgentContextPicker(
    val state: ModelPickerState,
    val actions: ModelPickerActions,
    val onEffortApplied: ((ModelPickerEntry, String?) -> Unit)? = null,
)

/**
 * letta-mobile-3io8k: the card's sheet — the searchable model list (grouped by provider, with
 * windows and effort chips) under "Applies to this agent/conversation", then the context: used /
 * limit, the per-section legend with its provenance, and Compact.
 */
@Composable
fun ColumnScope.AgentContextSheetContent(
    model: AgentContextCardModel,
    picker: AgentContextPicker?,
    onCompact: () -> Unit,
) {
    SheetHeading(AgentContextStrings.MODEL_TITLE, AgentContextStrings.scope(model.scope), Modifier.testTag(AgentContextTags.SCOPE))
    if (picker != null) {
        CompositionLocalProvider(LocalModelPickerContextTokens provides model.meter?.usage?.usedTokens) {
            ModelPickerContent(state = picker.state, actions = picker.actions)
        }
        SelectedModelEffort(picker)
    } else {
        Text(
            text = modelLine(model),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    ContextSection(model, onCompact)
}

/**
 * letta-mobile-3io8k: one effort slider, for the selected model only (tap a model first to tune
 * another; the pick applies at once and the slider follows it). Hidden for a model with no tiers
 * or a single one; it unfolds rather than jumping in.
 */
@Composable
private fun SelectedModelEffort(picker: AgentContextPicker) {
    val selected = picker.state.selected
    val stops = selected?.let { ReasoningEffortStops.of(it.efforts) }
    val apply = picker.onEffortApplied
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = selected != null && stops != null && apply != null,
        enter = if (reducedMotion) EnterTransition.None else expandVertically() + fadeIn(),
        exit = if (reducedMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
    ) {
        if (selected == null || stops == null || apply == null) return@AnimatedVisibility
        ReasoningEffortSlider(
            stops = stops,
            current = selected.tier?.effort,
            onApply = { effort -> apply(selected, effort) },
            modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs),
        )
    }
}

@Composable
private fun SheetHeading(title: String, caption: String, captionModifier: Modifier = Modifier) {
    Column(Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = captionModifier,
        )
    }
}

@Composable
private fun ContextSection(model: AgentContextCardModel, onCompact: () -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (reducedMotion) Modifier else Modifier.animateContentSize(tween(LettaMotionTokens.CONTENT_SIZE_MILLIS)))
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        ContextHeader(model)
        val meter = model.meter
        if (meter == null) {
            Caption(AgentContextStrings.NO_READING)
        } else {
            MeterDetail(meter)
        }
        CompactControls(model, onCompact)
    }
}

@Composable
private fun ContextHeader(model: AgentContextCardModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = AgentContextStrings.CONTEXT_TITLE,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        val usage = model.meter?.usage ?: return@Row
        Text(
            text = AgentContextStrings.totalLine(
                used = formatContextTokens(usage.usedTokens),
                window = usage.maxTokens.takeIf { it > 0 }?.let(::formatContextTokens),
                percent = model.usedPercent,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = levelColor(model.level),
            modifier = Modifier.testTag(AgentContextTags.TOTAL),
        )
    }
}

@Composable
private fun MeterDetail(meter: ContextMeter) {
    if (meter.usage.maxTokens > 0) ContextUsageBar(meter.usage)
    ContextUsageRows(meter.usage)
    Caption(
        AgentContextStrings.provenance(meter.provenance, meter.totalIsEstimate),
        Modifier.testTag(AgentContextTags.PROVENANCE),
    )
    if (meter.provenance == ContextProvenance.TotalOnly) {
        Caption(AgentContextStrings.TOTAL_ONLY_HINT, Modifier.testTag(AgentContextTags.HINT))
    }
}

@Composable
private fun CompactControls(model: AgentContextCardModel, onCompact: () -> Unit) {
    if (model.compact == CompactAffordance.Hidden) return
    if (model.nudgeCompact) Caption(AgentContextStrings.NEAR_FULL_HINT)
    val busy = model.compact == CompactAffordance.Busy
    FilledTonalButton(
        onClick = onCompact,
        enabled = !busy,
        colors = if (model.nudgeCompact) nudgeColors() else ButtonDefaults.filledTonalButtonColors(),
        modifier = Modifier.fillMaxWidth().testTag(AgentContextTags.COMPACT),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(LettaDimens.Control.iconSm), strokeWidth = LettaDimens.Stroke.hairline)
            Spacer(Modifier.width(LettaDimens.Space.sm))
        }
        Text(text = if (busy) AgentContextStrings.COMPACTING else AgentContextStrings.COMPACT)
    }
    AgentContextStrings.notice(model.notice, model.messageCounts, model.noticeDetail)?.let {
        Caption(it, Modifier.testTag(AgentContextTags.NOTICE))
    }
}

/** The amber nudge near a full window. */
@Composable
private fun nudgeColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
)

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
