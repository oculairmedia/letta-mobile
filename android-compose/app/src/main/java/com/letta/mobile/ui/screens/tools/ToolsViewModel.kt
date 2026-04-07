package com.letta.mobile.ui.screens.tools

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ToolsUiState(
    val tools: List<Tool> = emptyList()
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val toolRepository: ToolRepository,
) : ViewModel() {

    private val agentId: String = savedStateHandle.get<String>("agentId") ?: ""

    private val _uiState = MutableStateFlow<UiState<ToolsUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ToolsUiState>> = _uiState.asStateFlow()

    init {
        loadTools()
    }

    fun loadTools() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                toolRepository.refreshTools()
                val tools = toolRepository.getTools().first()
                _uiState.value = UiState.Success(ToolsUiState(tools = tools))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load tools")
            }
        }
    }

    fun removeTool(toolId: String) {
        viewModelScope.launch {
            try {
                toolRepository.detachTool(agentId, toolId)
                loadTools()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to remove tool")
            }
        }
    }

    fun addTool(toolId: String) {
        viewModelScope.launch {
            try {
                toolRepository.attachTool(agentId, toolId)
                loadTools()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to add tool")
            }
        }
    }
}
