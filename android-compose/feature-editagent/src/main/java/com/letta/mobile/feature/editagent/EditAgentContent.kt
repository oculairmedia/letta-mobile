package com.letta.mobile.feature.editagent

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel

/**
 * Edit-agent form body. Orchestrates model resolution, the scrollable
 * section list, and dialog hosts. Section UI lives in
 * [EditAgentContentList]; dialogs in [EditAgentContentDialogs] — this
 * keeps the file free of the file-level "Global Conditionals" blob
 * CodeScene attributes to large expression-body composables.
 */
@Composable
internal fun EditAgentContent(
    state: EditAgentUiState,
    llmModels: List<LlmModel>,
    embeddingModels: List<EmbeddingModel>,
    callbacks: EditAgentContentCallbacks,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    lazyListState: LazyListState = rememberLazyListState(),
) {
    val dialogState = rememberEditAgentContentDialogState()
    val selection = remember(state, llmModels, embeddingModels) {
        resolveEditAgentContentModels(state, llmModels, embeddingModels)
    }
    ClampEditAgentContextWindow(
        maxContextWindow = selection.maxContextWindow,
        contextWindow = state.contextWindow,
        onContextWindowChange = callbacks.onContextWindowChange,
    )
    EditAgentContentList(
        params = EditAgentContentListParams(
            state = state,
            selection = selection,
            callbacks = callbacks,
            dialogState = dialogState,
            contentPadding = contentPadding,
            lazyListState = lazyListState,
            modifier = modifier,
        ),
    )
    EditAgentContentDialogs(
        dialogState = dialogState,
        params = EditAgentContentDialogParams(
            state = state,
            llmModels = llmModels,
            embeddingDropdownModels = selection.embeddingDropdownModels,
            onAddBlock = callbacks.onAddBlock,
            onAttachExistingBlocks = callbacks.onAttachExistingBlocks,
            onAttachTools = callbacks.onAttachTools,
            onModelChange = callbacks.onModelChange,
            onEmbeddingChange = callbacks.onEmbeddingChange,
            onCompactionModelChange = callbacks.onCompactionModelChange,
        ),
    )
}

@Composable
private fun ClampEditAgentContextWindow(
    maxContextWindow: Int?,
    contextWindow: Int,
    onContextWindowChange: (Int) -> Unit,
) {
    LaunchedEffect(maxContextWindow, contextWindow) {
        if (maxContextWindow != null && contextWindow > maxContextWindow) {
            onContextWindowChange(maxContextWindow)
        }
    }
}
