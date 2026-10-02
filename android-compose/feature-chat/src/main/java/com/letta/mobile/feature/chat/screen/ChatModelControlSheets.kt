package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.ModelPickerController
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementController
import com.letta.mobile.feature.chat.coordination.EffortSelection
import com.letta.mobile.ui.modelcontrol.ModelControlModal
import com.letta.mobile.ui.modelcontrol.ModelControlPresentation
import com.letta.mobile.ui.modelcontrol.ModelPickerActions
import com.letta.mobile.ui.modelcontrol.ModelPickerContent
import com.letta.mobile.ui.modelcontrol.ModelsEditActions
import com.letta.mobile.ui.modelcontrol.ModelsEditContent

/**
 * The chat's model surfaces on Android (letta-mobile-w4q4p.6.1): the composer
 * chip's picker and, behind its "Edit Models…", the Models sheet. Both are the
 * shared sharedUI content in a bottom sheet.
 */
@Composable
internal fun ChatModelControlSheets(state: AgentScaffoldRuntimeState) {
    var showModelsEditor by remember { mutableStateOf(false) }
    val sheetVisibility = state.params.sheetVisibility
    if (sheetVisibility.showModelPicker) {
        ChatModelPicker(state) {
            sheetVisibility.onShowModelPickerChange(false)
            showModelsEditor = true
        }
    }
    if (showModelsEditor) ChatModelsEditor(state) { showModelsEditor = false }
}

@Composable
private fun ChatModelPicker(state: AgentScaffoldRuntimeState, onEditModels: () -> Unit) {
    val viewModel = state.params.viewModel
    val hide = { state.params.sheetVisibility.onShowModelPickerChange(false) }
    val reasoning = ModelPickerReasoning(
        onEffortSelected = { handle, effort ->
            viewModel.updateActiveAgentModel(handle, EffortSelection.Set(effort))
            hide()
        },
    )
    CompositionLocalProvider(LocalModelPickerReasoning provides reasoning) {
        ModelPickerSheet(
            models = state.availableModels,
            currentModel = state.activeAgentModel,
            callbacks = ModelPickerSheetCallbacks(
                onDismiss = hide,
                onModelSelected = { handle ->
                    viewModel.updateActiveAgentModel(handle)
                    hide()
                },
                onRefresh = viewModel::refreshModels,
                onEditModels = onEditModels,
            ),
            catalogSource = remember(viewModel) { viewModel.modelPickerSource() },
        )
    }
}

/** The Models sheet; closes itself when the host has no exposure to edit. */
@Composable
private fun ChatModelsEditor(state: AgentScaffoldRuntimeState, onClose: () -> Unit) {
    val viewModel = state.params.viewModel
    val scope = rememberCoroutineScope()
    val controller = remember(viewModel) { viewModel.modelsEditController(scope) }
    if (controller == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val navigateToProviders = state.params.navigation.onNavigateToProviders
    val actions = ModelsEditActions.bind(
        controller = controller,
        onClose = {
            onClose()
            // The chat's own model list routes picks; re-read it after exposure changes.
            viewModel.refreshModels()
        },
        onAddProvider = navigateToProviders?.let { navigate ->
            {
                onClose()
                navigate()
            }
        },
    )
    ModelsEditSheet(controller, actions)
}

/**
 * The composer's model picker on Android: the shared [ModelPickerContent] in a
 * bottom sheet — "Search models", exposed models under collapsible provider
 * headers with their reasoning tier, the current one marked, and "Refresh
 * Models" / "Edit Models…". On an Iroh host [catalogSource] is the App Server
 * catalog (exposure, provider names, re-query on refresh); otherwise, or when
 * the host does not answer it, the picker lists [models] as before. A pick goes
 * to [ModelPickerSheetCallbacks.onModelSelected] with the model's handle, which
 * keeps the per-conversation switch semantics.
 */
@Composable
internal fun ModelPickerSheet(
    models: List<LlmModel>,
    currentModel: String?,
    callbacks: ModelPickerSheetCallbacks,
    catalogSource: ModelPickerSource? = null,
) {
    val latestModels = rememberUpdatedState(models)
    val latestCallbacks = rememberUpdatedState(callbacks)
    val scope = rememberCoroutineScope()
    val controller = remember(catalogSource) {
        val fallback = ModelPickerSource.of(snapshotFlow { latestModels.value }) { latestCallbacks.value.onRefresh() }
        ModelPickerController(scope, catalogSource?.let { ModelPickerSource.withFallback(it, fallback) } ?: fallback)
    }
    LaunchedEffect(controller, currentModel) { controller.setSelected(currentModel) }
    LaunchedEffect(controller) { controller.ensureLoaded() }
    val state by controller.state.collectAsState()
    val actions = rememberPickerActions(controller, callbacks)
    ModelControlModal(ModelControlPresentation.Sheet, onDismiss = callbacks.onDismiss) {
        ModelPickerContent(state = state, actions = actions, modifier = Modifier.testTag(AgentScaffoldTestTags.MODEL_PICKER_SHEET))
    }
}

/** One pick per opening: a second tap while the sheet animates away is ignored. */
@Composable
private fun rememberPickerActions(controller: ModelPickerController, callbacks: ModelPickerSheetCallbacks): ModelPickerActions {
    val reasoning = LocalModelPickerReasoning.current
    var picked by remember { mutableStateOf(false) }
    fun once(entry: ModelPickerEntry, pick: () -> Unit) {
        if (picked) return
        picked = true
        if (!entry.selected) pick()
    }
    return ModelPickerActions.bind(
        controller = controller,
        onSelect = { entry ->
            once(entry) { callbacks.onModelSelected(entry.handle.value) }
            callbacks.onDismiss()
        },
        onEditModels = callbacks.onEditModels,
        onEffortSelected = { entry, effort ->
            if (!picked) {
                picked = true
                reasoning.onEffortSelected(entry.handle.value, effort)
            }
        },
    )
}

/** "Models": show or hide each model of the host in the picker; "Add provider…" opens Providers. */
@Composable
internal fun ModelsEditSheet(controller: ProviderManagementController, actions: ModelsEditActions) {
    LaunchedEffect(controller) { if (controller.state.value.sections.isEmpty()) controller.refresh() }
    val state by controller.state.collectAsState()
    ModelControlModal(ModelControlPresentation.Sheet, onDismiss = actions.onClose) {
        ModelsEditContent(state = state, actions = actions)
    }
}
