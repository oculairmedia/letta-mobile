package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel

/**
 * Resolved model handles used by [EditAgentContent] for picker titles and
 * context-window clamping. Kept as a plain data holder so the parent
 * composable stays free of the matching conditionals CodeScene was
 * attributing as file-level "Global Conditionals".
 */
internal data class EditAgentContentModelSelection(
    val embeddingDropdownModels: List<LlmModel>,
    val selectedLlmModel: LlmModel?,
    val selectedEmbeddingModel: LlmModel?,
    val selectedCompactionModel: LlmModel?,
    val maxContextWindow: Int?,
)

internal fun resolveEditAgentContentModels(
    state: EditAgentUiState,
    llmModels: List<LlmModel>,
    embeddingModels: List<EmbeddingModel>,
): EditAgentContentModelSelection {
    val embeddingDropdownModels = embeddingModels.map(::embeddingModelAsLlmModel)
    val selectedLlmModel = findModelByHandle(llmModels, state.model)
    return EditAgentContentModelSelection(
        embeddingDropdownModels = embeddingDropdownModels,
        selectedLlmModel = selectedLlmModel,
        selectedEmbeddingModel = findModelByHandle(embeddingDropdownModels, state.embedding),
        selectedCompactionModel = findModelByHandle(llmModels, state.compactionModel),
        maxContextWindow = resolveMaxContextWindow(selectedLlmModel, state),
    )
}

private fun embeddingModelAsLlmModel(model: EmbeddingModel): LlmModel = LlmModel(
    id = model.id,
    name = model.displayName,
    handle = model.handle ?: model.embeddingModel,
    providerType = model.providerType,
)

private fun findModelByHandle(models: List<LlmModel>, handle: String): LlmModel? =
    models.firstOrNull { model -> modelMatchesHandle(model, handle) }

private fun modelMatchesHandle(model: LlmModel, handle: String): Boolean =
    model.handle.equals(handle, ignoreCase = true) ||
        model.name.equals(handle, ignoreCase = true) ||
        model.displayName.equals(handle, ignoreCase = true)

private fun resolveMaxContextWindow(
    selectedLlmModel: LlmModel?,
    state: EditAgentUiState,
): Int? =
    selectedLlmModel?.contextWindow?.takeIf { it > 0 }
        ?: state.agent?.llmConfig?.contextWindow?.takeIf { it > 0 }
        ?: state.agent?.contextWindowLimit?.takeIf { it > 0 }
