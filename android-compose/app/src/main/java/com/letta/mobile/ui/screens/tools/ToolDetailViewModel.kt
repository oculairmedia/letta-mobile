package com.letta.mobile.ui.screens.tools

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.repository.ToolRepository
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
    private val toolRepository: ToolRepository,
) : ViewModel() {

    private val toolId: String = savedStateHandle.get<String>("toolId") ?: ""

    private val _uiState = MutableStateFlow<UiState<Tool>>(UiState.Loading)
    val uiState: StateFlow<UiState<Tool>> = _uiState.asStateFlow()

    private val _deleteState = MutableStateFlow<UiState<Unit>?>(null)
    val deleteState: StateFlow<UiState<Unit>?> = _deleteState.asStateFlow()

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

    fun updateTool(name: String, description: String?, sourceCode: String, tags: List<String>?) {
        viewModelScope.launch {
            try {
                val updated = toolRepository.updateTool(
                    toolId = toolId,
                    params = ToolUpdateParams(
                        name = name,
                        description = description,
                        sourceCode = sourceCode,
                        tags = tags,
                    )
                )
                _uiState.value = UiState.Success(updated)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to update tool")
                )
            }
        }
    }

    fun deleteTool() {
        viewModelScope.launch {
            _deleteState.value = UiState.Loading
            try {
                toolRepository.deleteTool(toolId)
                _deleteState.value = UiState.Success(Unit)
            } catch (e: Exception) {
                _deleteState.value = UiState.Error(
                    mapErrorToUserMessage(e, "Failed to delete tool")
                )
            }
        }
    }

    fun clearDeleteState() {
        _deleteState.value = null
    }
}
