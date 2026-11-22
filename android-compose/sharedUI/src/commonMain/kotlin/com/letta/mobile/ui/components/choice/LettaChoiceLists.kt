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
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens

/**
 * A single choice shown as a list, each option on its own row with its [LettaChoiceOption.description]
 * under it, so the options can be compared at a glance (where a dropdown shows only the current one).
 * The whole row picks; it is a radio button to assistive technology, and the list is one group.
 * [error] is a problem with the choice as a whole, shown under the list and announced. Use segments
 * ([LettaSegmentedControl]) for a few short options with nothing to explain.
 */
@Composable
fun LettaRadioList(
    options: List<LettaChoiceOption>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
) {
    Column(modifier.selectableGroup()) {
        options.forEach { option ->
            val chosen = option.key == selected
            ChoiceRowFrame(
                interaction = Modifier.selectable(
                    selected = chosen,
                    enabled = enabled && option.enabled,
                    role = Role.RadioButton,
                    onClick = { if (!chosen) onSelect(option.key) },
                ),
                tag = option.tag,
                words = ChoiceWords(option.label, option.description),
            ) { RadioButton(selected = chosen, onClick = null, enabled = enabled && option.enabled) }
        }
        error?.let { ChoiceError(it) }
    }
}

/**
 * One checkbox on its own row: [label], an optional [description] under it, and the box in [state]
 * (on, off, or partly: some of what it stands for is chosen). The whole row toggles through [onClick];
 * it is one checkbox to assistive technology.
 */
@Composable
fun LettaCheckboxRow(
    label: String,
    state: ToggleableState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    ChoiceRowFrame(
        interaction = modifier.triStateToggleable(state = state, enabled = enabled, role = Role.Checkbox, onClick = onClick),
        tag = null,
        words = ChoiceWords(label, description),
    ) { TriStateCheckbox(state = state, onClick = null, enabled = enabled) }
}

/**
 * The "select all" row above a list of checkboxes: on when all [total] are chosen, off when none,
 * partly when some. Picking it chooses all of them, or, when all are already chosen, clears them;
 * [onSelectAll] gets which.
 */
@Composable
fun LettaSelectAllRow(
    label: String,
    chosen: Int,
    total: Int,
    onSelectAll: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val state = when {
        total == 0 || chosen == 0 -> ToggleableState.Off
        chosen >= total -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    LettaCheckboxRow(
        label = label,
        state = state,
        onClick = { onSelectAll(state != ToggleableState.On) },
        modifier = modifier,
        enabled = enabled && total > 0,
    )
}

/** A choice row's label and the optional line under it. */
private class ChoiceWords(val label: String, val description: String?)

/** The shared row of a radio list or a checkbox: the control first, then the words, the whole row the target. */
@Composable
private fun ChoiceRowFrame(interaction: Modifier, tag: String?, words: ChoiceWords, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LettaControlTokens.minTouchTarget)
            .then(interaction)
            .then(tag?.let { Modifier.testTag(it) } ?: Modifier)
            .padding(vertical = LettaSpaceTokens.xs),
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.sm),
        verticalAlignment = if (words.description == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        control()
        // The control is a 24 dp glyph (no touch box of its own: the row is the target); the
        // label's first line centres on it.
        Column(
            Modifier.weight(1f).padding(top = LettaSpaceTokens.hair),
            verticalArrangement = Arrangement.spacedBy(LettaSpaceTokens.hair),
        ) {
            Text(words.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            words.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
