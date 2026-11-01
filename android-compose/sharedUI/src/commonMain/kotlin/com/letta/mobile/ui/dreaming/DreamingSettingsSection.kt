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
import androidx.compose.runtime.collectAsState
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
import com.letta.mobile.data.dreaming.DreamingActions
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.model.reflection.ReflectionModelChoice
import com.letta.mobile.ui.chat.surface.composer.ModelListPicker
import com.letta.mobile.ui.chat.surface.composer.rememberModelListPicker
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.flow.StateFlow

/** The automatic triggers a user picks between; [DreamTrigger.Off] is the switch. */
private val AutomaticTriggers = listOf(DreamTrigger.CompactionEvent, DreamTrigger.StepCount)

/** The stored settings as controls. Every change saves through letta-code at once. */
@Composable
internal fun DreamingSettingsSection(state: DreamSettingsState, actions: DreamingActions) {
    val settings = state.settings ?: return
    var scope by remember { mutableStateOf(DreamSettingsScope.Both) }
    var pickingModel by remember { mutableStateOf(false) }
    if (pickingModel) {
        ReflectionModelPicker(settings, scope, actions) { pickingModel = false }
        return
    }
    val save: (DreamSettings) -> Unit = { actions.save(DreamSettingsChange(it, scope)) }
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
        if (settings.model != ReflectionModelChoice.Unsupported && settings.focusInstructions != null) {
            ReflectionModelControl(settings, actions.models, onPick = { pickingModel = true }) { choice ->
                actions.save(DreamSettingsChange(settings.copy(model = choice), scope, modelChanged = true))
            }
            FocusInstructions(settings) { focus ->
                actions.save(
                    DreamSettingsChange(
                        settings.copy(focusInstructions = focus),
                        scope,
                        focusInstructionsChanged = true,
                    ),
                )
            }
        }
        ScopeChoice(scope) { picked ->
            scope = picked
            actions.save(DreamSettingsChange(settings, picked))
        }
        state.error?.let { Text(DreamingStrings.failure(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ReflectionModelControl(
    settings: DreamSettings,
    models: StateFlow<List<LlmModel>>,
    onPick: () -> Unit,
    onChoice: (ReflectionModelChoice) -> Unit,
) {
    val available by models.collectAsState()
    val label = when (val choice = settings.model) {
        ReflectionModelChoice.Unsupported -> return
        ReflectionModelChoice.Inherit -> DreamingStrings.MODEL_INHERIT
        is ReflectionModelChoice.Handle ->
            ModelCatalog.selectedModel(available, choice.value)?.displayName ?: choice.value
    }
    Text(DreamingStrings.MODEL_LABEL, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onPick, modifier = Modifier.testTag(DreamingTags.MODEL)) { Text(DreamingStrings.MODEL_CHOOSE) }
    }
    if (settings.model != ReflectionModelChoice.Inherit) {
        TextButton(onClick = { onChoice(ReflectionModelChoice.Inherit) }, modifier = Modifier.testTag(DreamingTags.MODEL_INHERIT)) {
            Text(DreamingStrings.MODEL_INHERIT)
        }
    }
}

@Composable
private fun ReflectionModelPicker(
    settings: DreamSettings,
    scope: DreamSettingsScope,
    actions: DreamingActions,
    onDismiss: () -> Unit,
) {
    val available by actions.models.collectAsState()
    val picker = rememberModelListPicker(actions.models, actions::reloadModels) { value ->
        val handle = ModelCatalog.transportValue(available, value) ?: value
        actions.save(
            DreamSettingsChange(
                settings.copy(model = ReflectionModelChoice.Handle(handle)),
                scope,
                modelChanged = true,
            ),
        )
    }
    val selected = (settings.model as? ReflectionModelChoice.Handle)?.value
    ModelListPicker(picker, selectedValue = selected, onDismiss = onDismiss)
}

@Composable
private fun FocusInstructions(settings: DreamSettings, onSave: (String) -> Unit) {
    val stored = settings.focusInstructions ?: return
    var draft by remember(stored) { mutableStateOf(stored) }
    val trimmed = draft.trim()
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(DreamingStrings.FOCUS_INSTRUCTIONS) },
        modifier = Modifier.fillMaxWidth().testTag(DreamingTags.FOCUS_INSTRUCTIONS),
        minLines = 2,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = { onSave(trimmed) },
            enabled = trimmed != stored,
            modifier = Modifier.testTag(DreamingTags.FOCUS_SAVE),
        ) { Text(DreamingStrings.SAVE) }
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
