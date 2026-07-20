package com.letta.mobile.feature.editagent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool

/**
 * Holder for the mutable open/closed flags of every dialog owned by
 * [EditAgentContent]. Grouping the flags here (rather than declaring
 * seven separate `mutableStateOf` locals in the parent composable) keeps
 * the caller's argument surface small and this file free of a
 * "Global Conditionals" hotspot that CodeScene was flagging previously.
 */
@Stable
internal class EditAgentContentDialogState {
    val showToolPicker = mutableStateOf(false)
    val showAddBlockDialog = mutableStateOf(false)
    val showAttachBlockDialog = mutableStateOf(false)
    val selectedTool = mutableStateOf<Tool?>(null)
    val showLlmPicker = mutableStateOf(false)
    val showEmbeddingPicker = mutableStateOf(false)
    val showCompactionModelPicker = mutableStateOf(false)
}

@Composable
internal fun rememberEditAgentContentDialogState(): EditAgentContentDialogState =
    remember { EditAgentContentDialogState() }

/**
 * Ambient data the extracted dialog block needs. Bundled so
 * [EditAgentContentDialogs] can stay at two arguments — this keeps the
 * dialog wiring off [EditAgentContent]'s function surface and turns the
 * previous top-level conditional blob into a small dispatch.
 */
internal data class EditAgentContentDialogParams(
    val state: EditAgentUiState,
    val llmModels: List<LlmModel>,
    val embeddingDropdownModels: List<LlmModel>,
    val onAddBlock: (NewBlockDraft) -> Unit,
    val onAttachExistingBlocks: (List<String>) -> Unit,
    val onAttachTools: (List<String>) -> Unit,
    val onModelChange: (String) -> Unit,
    val onEmbeddingChange: (String) -> Unit,
    val onCompactionModelChange: (String) -> Unit,
)

@Composable
internal fun EditAgentContentDialogs(
    dialogState: EditAgentContentDialogState,
    params: EditAgentContentDialogParams,
) {
    ToolDetailDialogHost(dialogState.selectedTool)
    AddBlockDialogHost(
        show = dialogState.showAddBlockDialog,
        onAdd = params.onAddBlock,
    )
    AttachBlockDialogHost(
        show = dialogState.showAttachBlockDialog,
        state = params.state,
        onAttachExistingBlocks = params.onAttachExistingBlocks,
    )
    ToolPickerDialogHost(
        show = dialogState.showToolPicker,
        state = params.state,
        onAttachTools = params.onAttachTools,
    )
    ModelPickerDialogHost(
        show = dialogState.showLlmPicker,
        spec = ModelPickerDialogSpec(
            title = stringResource(R.string.common_model),
            models = params.llmModels,
            selectedValue = params.state.model,
            onSelected = params.onModelChange,
        ),
    )
    ModelPickerDialogHost(
        show = dialogState.showEmbeddingPicker,
        spec = ModelPickerDialogSpec(
            title = stringResource(R.string.screen_agent_edit_embedding_model),
            models = params.embeddingDropdownModels,
            selectedValue = params.state.embedding,
            onSelected = params.onEmbeddingChange,
        ),
    )
    ModelPickerDialogHost(
        show = dialogState.showCompactionModelPicker,
        spec = ModelPickerDialogSpec(
            title = stringResource(R.string.screen_agent_edit_compaction_model),
            models = params.llmModels,
            selectedValue = params.state.compactionModel,
            onSelected = params.onCompactionModelChange,
        ),
    )
}

@Composable
private fun ToolDetailDialogHost(selectedTool: MutableState<Tool?>) {
    val tool = selectedTool.value ?: return
    ToolDetailDialog(tool = tool, onDismiss = { selectedTool.value = null })
}

@Composable
private fun AddBlockDialogHost(
    show: MutableState<Boolean>,
    onAdd: (NewBlockDraft) -> Unit,
) {
    if (!show.value) return
    AddBlockDialog(
        onDismiss = { show.value = false },
        onAdd = { label, value, description, limit ->
            onAdd(NewBlockDraft(label, value, description, limit))
            show.value = false
        },
    )
}

@Composable
private fun AttachBlockDialogHost(
    show: MutableState<Boolean>,
    state: EditAgentUiState,
    onAttachExistingBlocks: (List<String>) -> Unit,
) {
    if (!show.value) return
    FullScreenBlockPickerDialog(
        excludedBlockIds = state.blocks.map { it.id },
        availableBlocks = state.availableBlocks,
        onDismiss = { show.value = false },
        onConfirm = { selectedIds ->
            onAttachExistingBlocks(selectedIds)
            show.value = false
        },
    )
}

@Composable
private fun ToolPickerDialogHost(
    show: MutableState<Boolean>,
    state: EditAgentUiState,
    onAttachTools: (List<String>) -> Unit,
) {
    if (!show.value) return
    FullScreenToolPickerDialog(
        tools = state.availableTools.filter { candidate ->
            state.attachedTools.none { attached -> attached.id == candidate.id }
        },
        selectedToolIds = emptyList(),
        title = stringResource(R.string.screen_agent_edit_attach_tools),
        onDismiss = { show.value = false },
        onConfirm = { selectedIds ->
            onAttachTools(selectedIds)
            show.value = false
        },
    )
}

/**
 * Static configuration for a [FullScreenModelPickerDialog]. Bundling keeps
 * [ModelPickerDialogHost] at two arguments so it stays under the CodeScene
 * function-argument threshold.
 */
private data class ModelPickerDialogSpec(
    val title: String,
    val models: List<LlmModel>,
    val selectedValue: String,
    val onSelected: (String) -> Unit,
)

@Composable
private fun ModelPickerDialogHost(
    show: MutableState<Boolean>,
    spec: ModelPickerDialogSpec,
) {
    if (!show.value) return
    FullScreenModelPickerDialog(
        title = spec.title,
        placeholder = stringResource(R.string.screen_models_search_hint),
        models = spec.models,
        selectedValue = spec.selectedValue,
        onDismiss = { show.value = false },
        onModelSelected = {
            spec.onSelected(it)
            show.value = false
        },
    )
}
