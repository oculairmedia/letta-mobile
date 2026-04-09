package com.letta.mobile.ui.screens.tools

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ToolDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val toolApi: ToolApi,
) : ViewModel() {

    private val toolId: String = savedStateHandle.get<String>("toolId") ?: ""

    private val _uiState = MutableStateFlow<UiState<Tool>>(UiState.Loading)
    val uiState: StateFlow<UiState<Tool>> = _uiState.asStateFlow()

    init {
        loadTool()
    }

    fun loadTool() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val tool = toolApi.getTool(toolId)
                _uiState.value = UiState.Success(tool)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to load tool")
                )
            }
        }
    }
}
