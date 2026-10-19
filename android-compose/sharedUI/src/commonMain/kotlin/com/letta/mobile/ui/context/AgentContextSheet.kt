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
import com.letta.mobile.data.repository.modelcontrol.ReasoningTier
import com.letta.mobile.ui.modelcontrol.EffortSetting
import com.letta.mobile.ui.modelcontrol.ReasoningEffortSlider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.picker.PickerDrillIn
import com.letta.mobile.ui.components.picker.rememberPickerDrillIn
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.modelcontrol.LocalModelPickerContextTokens
import com.letta.mobile.ui.modelcontrol.ModelControlStrings
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
    /**
     * Which level the sheet shows, owned by the pane so its dismissal can go
     * back first; null keeps the levels inside the sheet.
     */
    val levels: PickerDrillIn<AgentSettingsLevel>? = null,
)

/** The agent settings sheet's levels: its rows, and the model list opened over them. */
enum class AgentSettingsLevel { Root, Model }

/**
 * The card's sheet. (built on the S8 picker,
 *): two levels. The root is one scroll of rows: the "Model" row under
 * "Applies to this agent/conversation" with the selected model's effort slider, then the context
 * (used / limit, the per-section legend with its provenance, Compact). The "Model" row opens the
 * searchable model list as the second level, with a back arrow, its search pinned and its own
 * scroll; a pick returns to the root. The pane holding the sheet asks [AgentContextPicker.levels]
 * to go back before it closes.
 */
@Composable
fun ColumnScope.AgentContextSheetContent(
    model: AgentContextCardModel,
    picker: AgentContextPicker?,
    onCompact: () -> Unit,
    limit: ContextLimitControl? = null,
) {
    val ownLevels = rememberPickerDrillIn(AgentSettingsLevel.Root)
    val levels = picker?.levels ?: ownLevels
    if (picker != null && levels.current == AgentSettingsLevel.Model) {
        ModelLevel(model, picker, levels)
        return
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        SheetHeading(AgentContextStrings.MODEL_TITLE, AgentContextStrings.scope(model.scope), Modifier.testTag(AgentContextTags.SCOPE))
        if (picker != null) {
            ModelRow(model, picker, onOpen = { levels.open(AgentSettingsLevel.Model) })
            SelectedModelEffort(picker, model.effort)
        } else {
            Text(
                text = modelLine(model),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ContextSection(model, onCompact, limit)
    }
}

/** The second level: the model list alone, so it gets the pane's height; a pick goes back to the rows. */
@Composable
private fun ColumnScope.ModelLevel(model: AgentContextCardModel, picker: AgentContextPicker, levels: PickerDrillIn<AgentSettingsLevel>) {
    val actions = picker.actions.copy(
        onSelect = { entry ->
            picker.actions.onSelect(entry)
            levels.back()
        },
        onBack = { levels.back() },
    )
    CompositionLocalProvider(LocalModelPickerContextTokens provides model.meter?.usage?.usedTokens) {
        ModelPickerContent(state = picker.state, actions = actions, autoFocusSearch = true)
    }
}

/** "Model  Opus 4 · High  >": the current model; tapping opens the model list. */
@Composable
private fun ModelRow(model: AgentContextCardModel, picker: AgentContextPicker, onOpen: () -> Unit) {
    val selected = picker.state.selected
    val name = selected?.displayName ?: modelLine(model)
    LettaListRow(
        spec = LettaListRowSpec(title = name, trailing = EffortChoice.tierOf(model.effort, selected)?.let(ModelControlStrings::tierLabel)),
        onClick = onOpen,
        modifier = Modifier.padding(horizontal = LettaDimens.Space.sm).testTag(AgentContextTags.MODEL_ROW),
        end = { Icon(LettaIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon)) },
    )
}

/**
 * One effort slider, for the selected model only (tap a model first to tune
 * another; the pick applies at once and the slider follows it). Hidden for a model with no tiers
 * or a single one; it unfolds rather than jumping in.
 */
@Composable
private fun SelectedModelEffort(picker: AgentContextPicker, conversationEffort: String?) {
    val choice = EffortChoice.of(picker)
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = choice != null,
        enter = if (reducedMotion) EnterTransition.None else expandVertically() + fadeIn(),
        exit = if (reducedMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
    ) {
        choice ?: return@AnimatedVisibility
        ReasoningEffortSlider(
            setting = EffortSetting(choice.stops, EffortChoice.tierOf(conversationEffort, choice.entry)?.effort),
            onApply = { effort -> choice.apply(choice.entry, effort) },
            modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs),
        )
    }
}

/** The selected model, its effort stops and the host's switch: all three, or no slider. */
private class EffortChoice(val entry: ModelPickerEntry, val stops: ReasoningEffortStops, val apply: (ModelPickerEntry, String?) -> Unit) {
    companion object {
        fun of(picker: AgentContextPicker): EffortChoice? {
            val apply = picker.onEffortApplied ?: return null
            val entry = picker.state.selected ?: return null
            val stops = ReasoningEffortStops.of(entry.efforts) ?: return null
            return EffortChoice(entry, stops, apply)
        }

        /**
         * The tier the conversation runs at — its own effort (this session's
         * pick, else the host's record) — else the catalog row's. The row's tier is only the
         * catalog's first variant for the handle, so reading it alone snapped the slider back.
         */
        fun tierOf(conversationEffort: String?, entry: ModelPickerEntry?): ReasoningTier? =
            ReasoningTier.of(conversationEffort) ?: entry?.tier
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
private fun ContextSection(model: AgentContextCardModel, onCompact: () -> Unit, limit: ContextLimitControl?) {
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
        LimitControl(limit, onCompact.takeIf { model.compact == CompactAffordance.Available })
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
    if (meter.usage.maxTokens > 0) ContextUsageBar(meter.usage, autoCompactAt = meter.autoCompactAt)
    ContextUsageRows(meter.usage)
    Caption(
        AgentContextStrings.provenance(meter.provenance, meter.totalIsEstimate),
        Modifier.testTag(AgentContextTags.PROVENANCE),
    )
    if (meter.provenance == ContextProvenance.TotalOnly) {
        Caption(AgentContextStrings.TOTAL_ONLY_HINT, Modifier.testTag(AgentContextTags.HINT))
    }
}

/** The context-limit slider, or why this host has none; nothing when the sheet has no limit to offer. */
@Composable
private fun LimitControl(limit: ContextLimitControl?, onCompact: (() -> Unit)?) {
    when (limit) {
        null -> Unit
        ContextLimitControl.Unsupported -> Caption(ContextLimitStrings.UNSUPPORTED, Modifier.testTag(ContextLimitTags.UNSUPPORTED))
        is ContextLimitControl.Adjustable -> ContextLimitSlider(limit.setting, limit.onApply, onCompact)
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
