package com.letta.mobile.ui.dreaming

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.data.dreaming.DreamMerge
import com.letta.mobile.data.dreaming.DreamSettings
import com.letta.mobile.data.dreaming.DreamSettingsChange
import com.letta.mobile.data.dreaming.DreamSettingsScope
import com.letta.mobile.data.dreaming.DreamSettingsState
import com.letta.mobile.data.dreaming.DreamTrigger
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** The automatic triggers a user picks between; [DreamTrigger.Off] is the switch. */
private val AutomaticTriggers = listOf(DreamTrigger.CompactionEvent, DreamTrigger.StepCount)

/** The stored settings as controls. Every change saves through letta-code at once. */
@Composable
internal fun DreamingSettingsSection(state: DreamSettingsState, onSave: (DreamSettingsChange) -> Unit) {
    val settings = state.settings ?: return
    var scope by remember { mutableStateOf(DreamSettingsScope.Both) }
    val save: (DreamSettings) -> Unit = { onSave(DreamSettingsChange(it, scope)) }
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        SwitchRow(DreamingStrings.AUTOMATIC, settings.automatic, DreamingTags.AUTOMATIC) { on ->
            save(settings.copy(trigger = if (on) DreamTrigger.CompactionEvent else DreamTrigger.Off))
        }
        if (settings.automatic) TriggerControls(settings, save)
        SwitchRow(DreamingStrings.REVIEW, settings.merge == DreamMerge.Explicit, DreamingTags.REVIEW) { on ->
            save(settings.copy(merge = if (on) DreamMerge.Explicit else DreamMerge.Auto))
        }
        Hint(DreamingStrings.REVIEW_HINT)
        if (settings.merge == DreamMerge.Explicit) ReviewInstructions(settings, save)
        ScopeChoice(scope) { picked ->
            scope = picked
            onSave(DreamSettingsChange(settings, picked))
        }
        Hint(DreamingStrings.MODEL)
        state.error?.let { Text(DreamingStrings.failure(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun TriggerControls(settings: DreamSettings, save: (DreamSettings) -> Unit) {
    Text(DreamingStrings.WHEN, style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        AutomaticTriggers.forEachIndexed { index, trigger ->
            SegmentedButton(
                selected = settings.trigger == trigger,
                onClick = { save(settings.copy(trigger = trigger)) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = AutomaticTriggers.size),
                modifier = Modifier.testTag(DreamingTags.trigger(trigger)),
            ) { Text(DreamingStrings.trigger(trigger)) }
        }
    }
    if (settings.trigger == DreamTrigger.StepCount) StepCountStepper(settings, save)
}

@Composable
private fun StepCountStepper(settings: DreamSettings, save: (DreamSettings) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(DreamingStrings.STEPS_LABEL, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(
            onClick = { save(settings.copy(stepCount = settings.stepCount - STEP)) },
            enabled = settings.stepCount - STEP >= DreamSettings.MIN_STEP_COUNT,
            modifier = Modifier.testTag(DreamingTags.STEPS_DOWN),
        ) { Icon(LettaIcons.Remove, contentDescription = "Fewer steps") }
        Text(
            settings.stepCount.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = LettaDimens.Control.iconButtonLg).testTag(DreamingTags.STEPS),
        )
        IconButton(onClick = { save(settings.copy(stepCount = settings.stepCount + STEP)) }, modifier = Modifier.testTag(DreamingTags.STEPS_UP)) {
            Icon(LettaIcons.Add, contentDescription = "More steps")
        }
    }
}

@Composable
private fun ReviewInstructions(settings: DreamSettings, save: (DreamSettings) -> Unit) {
    var draft by remember(settings.mergeInstructions) { mutableStateOf(settings.mergeInstructions) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(DreamingStrings.REVIEW_INSTRUCTIONS) },
        modifier = Modifier.fillMaxWidth().testTag(DreamingTags.INSTRUCTIONS),
        minLines = 2,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { save(settings.copy(mergeInstructions = draft.trim())) }, enabled = draft.trim() != settings.mergeInstructions) {
            Text(DreamingStrings.SAVE)
        }
    }
}

@Composable
private fun ScopeChoice(scope: DreamSettingsScope, onPick: (DreamSettingsScope) -> Unit) {
    Text(DreamingStrings.SAVED_FOR, style = MaterialTheme.typography.labelLarge)
    val scopes = DreamSettingsScope.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        scopes.forEachIndexed { index, option ->
            SegmentedButton(
                selected = scope == option,
                onClick = { if (option != scope) onPick(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = scopes.size),
            ) { Text(DreamingStrings.scope(option)) }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val STEP = 5
