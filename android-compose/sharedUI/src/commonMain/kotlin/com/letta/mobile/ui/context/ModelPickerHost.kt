package com.letta.mobile.ui.context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.repository.modelcontrol.ModelPickerBinding
import com.letta.mobile.data.repository.modelcontrol.ModelPickerController
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementController
import com.letta.mobile.ui.components.isPointerHost
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.modelcontrol.ModelPickerActions
import com.letta.mobile.ui.modelcontrol.ModelPickerContent
import com.letta.mobile.ui.modelcontrol.ModelPickerTags
import com.letta.mobile.ui.modelcontrol.ModelsEditActions
import com.letta.mobile.ui.modelcontrol.ModelsEditContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The one model picker of every platform, open while [binding] says so. "Search models", the
 * "Recent" group, the models under their providers (each one's effort options in a flyout or sheet
 * when the host picks efforts), "Refresh Models" and "Edit Models…", which gives way to the Models sheet ("Add
 * provider…" goes to the host's Providers page). [selectedValue] marks the current model. A pane is
 * a sheet on a phone and a dialog on a pointer window.
 */
@Composable
fun ModelPickerHost(binding: ModelPickerBinding, selectedValue: String?) {
    // Outlives the Models sheet, so the chat's re-read after it closes is not cancelled with it.
    val scope = rememberCoroutineScope()
    val editor = binding.editor
    when {
        binding.pickerOpen -> ModelPickerPane(binding, selectedValue)
        binding.editorOpen && editor != null -> ModelsEditorPane(binding, editor) { scope.launch { binding.onModelsEdited() } }
    }
}

@Composable
private fun ModelPickerPane(binding: ModelPickerBinding, selectedValue: String?) {
    val scope = rememberCoroutineScope()
    val controller = remember(binding) { ModelPickerController(scope, binding.source) }
    LaunchedEffect(controller, selectedValue) { controller.setSelected(selectedValue) }
    LaunchedEffect(controller) { controller.ensureLoaded() }
    val state by controller.state.collectAsState()
    val actions = rememberPickerActions(binding, controller)
    LettaPane(LettaPaneKind.Modal, onDismiss = binding::close) {
        ModelPickerContent(
            state = state,
            actions = actions,
            modifier = Modifier.testTag(ModelPickerTags.PANE),
            autoFocusSearch = isPointerHost(),
        )
    }
}

/** One pick per opening: a second tap while the pane animates away is ignored; re-picking the current model changes nothing. */
@Composable
private fun rememberPickerActions(binding: ModelPickerBinding, controller: ModelPickerController): ModelPickerActions {
    var picked by remember { mutableStateOf(false) }
    fun once(pick: () -> Unit) {
        if (!picked) {
            picked = true
            pick()
        }
        binding.close()
    }
    return ModelPickerActions.bind(
        controller = controller,
        onSelect = { entry: ModelPickerEntry -> once { if (!entry.selected) binding.selectModel(entry) } },
        onEditModels = binding.editor?.let { binding::editModels },
        // An effort pick applies even on the current model: it changes the effort.
        onEffortSelected = binding.onEffortSelected?.let { { entry, effort -> once { binding.selectEffort(entry, effort) } } },
        rememberedEffort = binding::rememberedEffort,
    )
}

/** "Models": show or hide each model in the picker; "Add provider…" opens the host's Providers page. */
@Composable
private fun ModelsEditorPane(
    binding: ModelPickerBinding,
    editor: (CoroutineScope) -> ProviderManagementController,
    afterClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(binding) { editor(scope) }
    LaunchedEffect(controller) { if (controller.state.value.sections.isEmpty()) controller.refresh() }
    val state by controller.state.collectAsState()
    val close: () -> Unit = {
        binding.close()
        afterClose()
    }
    val actions = remember(controller, binding) {
        ModelsEditActions.bind(
            controller = controller,
            onClose = close,
            onAddProvider = binding.onAddProvider?.let { add ->
                {
                    binding.close()
                    add()
                }
            },
        )
    }
    LettaPane(LettaPaneKind.Modal, onDismiss = close) {
        ModelsEditContent(state = state, actions = actions, autoFocusSearch = isPointerHost())
    }
}
