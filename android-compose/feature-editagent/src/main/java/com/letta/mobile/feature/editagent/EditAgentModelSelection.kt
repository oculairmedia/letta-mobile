package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.modelvalidation.ModelHandleValidator

internal class EditAgentModelSelection(
    private val state: EditAgentViewModelState,
    private val llmModels: () -> List<LlmModel>,
) {
    fun updateModel(value: String) {
        val currentState = state.successData() ?: return
        val selectedModel = llmModels().firstOrNull { model ->
            model.handle.equals(value, ignoreCase = true) ||
                model.name.equals(value, ignoreCase = true) ||
                model.displayName.equals(value, ignoreCase = true)
        }
        val normalizedProviderType = selectedModel?.let { model ->
            EditAgentUseCases.normalizeModelSettingsProviderType(
                providerType = model.providerType,
                modelHandle = model.handle ?: value,
            )
        }
        val selectedContextWindow = selectedModel?.contextWindow?.takeIf { it > 0 }
        val selectedHandle = selectedModel?.handle ?: value
        val validation = ModelHandleValidator.validate(
            handle = selectedHandle,
            backend = if (selectedHandle.startsWith("lmstudio/", ignoreCase = true)) {
                ModelHandleValidator.Backend.REMOTE
            } else {
                ModelHandleValidator.Backend.ON_DEVICE
            },
            servedModels = llmModels().mapNotNull { model -> model.handle ?: model.name.ifBlank { model.id } },
        )
        if (validation is ModelHandleValidator.Result.Invalid) {
            state.setError(validation.reason)
            return
        }
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
