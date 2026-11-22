package com.letta.mobile.ui.components.choice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import com.letta.mobile.ui.components.foundation.LettaRowLabel
import com.letta.mobile.ui.components.foundation.LettaRowWords
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens

/**
 * A single choice shown as a list, each option on its own row with its [LettaChoiceOption.description]
 * under it, so the options can be compared at a glance (where a dropdown shows only the current one).
 * The whole row picks; it is a radio button to assistive technology, and the list is one group.
 * [LettaRadioChoice.error] is a problem with the choice as a whole. Use segments
 * ([LettaSegmentedControl]) for a few short options with nothing to explain.
 */
@Composable
fun LettaRadioList(choice: LettaRadioChoice, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.selectableGroup()) {
        choice.options.forEach { option ->
            val chosen = option.key == choice.selected
            val enabled = choice.enabled && option.enabled
            val picks = Modifier.selectable(
                selected = chosen,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { if (!chosen) onSelect(option.key) },
            )
            ChoiceRowFrame(
                label = LettaRowLabel(option.label, option.description, enabled, supportingMaxLines = Int.MAX_VALUE),
                modifier = picks.then(option.tag?.let { Modifier.testTag(it) } ?: Modifier),
            ) { RadioButton(selected = chosen, onClick = null, enabled = enabled) }
        }
        choice.error?.let { ChoiceError(it) }
    }
}

/**
 * One checkbox on its own row: the [label] (its supporting line is the description) and the box in
 * [state] (on, off, or partly: some of what it stands for is chosen). The whole row toggles through
 * [onClick]; it is one checkbox to assistive technology. A disabled label disables the box.
 */
@Composable
fun LettaCheckboxRow(label: LettaRowLabel, state: ToggleableState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val toggles = Modifier.triStateToggleable(state = state, enabled = label.enabled, role = Role.Checkbox, onClick = onClick)
    ChoiceRowFrame(label = label, modifier = modifier.then(toggles)) {
        TriStateCheckbox(state = state, onClick = null, enabled = label.enabled)
    }
}

/**
 * The "select all" row above a list of checkboxes: on when all are chosen, off when none, partly when
 * some (see [LettaSelectionCount]). Picking it chooses all of them, or, when all are already chosen,
 * clears them; [onSelectAll] gets which. With nothing to choose the row is disabled.
 */
@Composable
fun LettaSelectAllRow(label: LettaRowLabel, count: LettaSelectionCount, onSelectAll: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val state = count.state
    LettaCheckboxRow(
        label = label.copy(enabled = label.enabled && count.total > 0),
        state = state,
        onClick = { onSelectAll(state != ToggleableState.On) },
        modifier = modifier,
    )
}

/**
 * The shared row of a radio list or a checkbox: the control first, then the label's words as every
 * settings row draws them, the whole row the target ([modifier] carries its interaction).
 */
@Composable
private fun ChoiceRowFrame(label: LettaRowLabel, modifier: Modifier, control: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LettaControlTokens.minTouchTarget)
            .padding(vertical = LettaSpaceTokens.xs),
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.sm),
        verticalAlignment = if (label.supporting == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        control()
        LettaRowWords(label, Modifier.weight(1f))
    }
}

/** A problem with the whole choice, under it, in the error colour and announced. */
@Composable
private fun ChoiceError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = LettaSpaceTokens.xs).semantics { liveRegion = LiveRegionMode.Polite },
    )
}
