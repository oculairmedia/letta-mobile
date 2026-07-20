package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.modelvalidation.ModelHandleValidator

internal class EditAgentModelSelection(
    private val state: EditAgentViewModelState,
    private val llmModels: () -> List<LlmModel>,
) {
    fun updateModel(value: String) {
        val currentState = state.successData() ?: return
        val selectedModel = findSelectedModel(value)
        val selectedHandle = selectedModel?.handle ?: value
        val validationError = validateSelectedHandle(selectedHandle)
        if (validationError != null) {
            state.setError(validationError)
            return
        }
        applyModelSelection(currentState, selectedModel, selectedHandle)
    }

    private fun findSelectedModel(value: String): LlmModel? =
        llmModels().firstOrNull { model ->
            model.handle.equals(value, ignoreCase = true) ||
                model.name.equals(value, ignoreCase = true) ||
                model.displayName.equals(value, ignoreCase = true)
        }

    private fun validateSelectedHandle(selectedHandle: String): String? {
        val backend = if (selectedHandle.startsWith("lmstudio/", ignoreCase = true)) {
            ModelHandleValidator.Backend.REMOTE
        } else {
            ModelHandleValidator.Backend.ON_DEVICE
        }
        val validation = ModelHandleValidator.validate(
            handle = selectedHandle,
            backend = backend,
            servedModels = llmModels().mapNotNull { model -> model.handle ?: model.name.ifBlank { model.id } },
        )
        return (validation as? ModelHandleValidator.Result.Invalid)?.reason
    }

    private fun applyModelSelection(
        currentState: EditAgentUiState,
        selectedModel: LlmModel?,
        selectedHandle: String,
    ) {
        val normalizedProviderType = selectedModel?.let { model ->
            EditAgentUseCases.normalizeModelSettingsProviderType(
                providerType = model.providerType,
                modelHandle = model.handle ?: selectedHandle,
            )
        }
        val selectedContextWindow = selectedModel?.contextWindow?.takeIf { it > 0 }
        state.setSuccess(
            currentState.copy(
                model = selectedHandle,
                providerType = normalizedProviderType.orEmpty(),
                contextWindow = selectedContextWindow
                    ?.let { maxContextWindow -> currentState.contextWindow.coerceIn(0, maxContextWindow) }
                    ?: currentState.contextWindow,
            )
        )
    }
}
