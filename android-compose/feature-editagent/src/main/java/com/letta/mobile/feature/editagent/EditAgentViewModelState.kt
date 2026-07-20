package com.letta.mobile.feature.editagent

import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow

internal class EditAgentViewModelState(
    private val uiState: MutableStateFlow<UiState<EditAgentUiState>>,
) {
    fun successData(): EditAgentUiState? = (uiState.value as? UiState.Success)?.data

    fun setLoading() {
        uiState.value = UiState.Loading
    }

    fun setError(message: String) {
        uiState.value = UiState.Error(message)
    }

    fun setSuccess(state: EditAgentUiState) {
        uiState.value = UiState.Success(state)
    }

    fun updateSuccess(transform: (EditAgentUiState) -> EditAgentUiState) {
        val current = successData() ?: return
        uiState.value = UiState.Success(transform(current))
    }

    inline fun updateField(crossinline transform: EditAgentUiState.() -> EditAgentUiState) {
        updateSuccess { it.transform() }
    }
}
