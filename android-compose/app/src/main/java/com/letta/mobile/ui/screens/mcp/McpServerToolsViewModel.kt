package com.letta.mobile.ui.screens.mcp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.McpServerRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class McpServerToolsUiState(
    val server: McpServer,
    val tools: List<Tool> = emptyList(),
)

@HiltViewModel
class McpServerToolsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mcpServerRepository: McpServerRepository,
) : ViewModel() {

    private val serverId: String = savedStateHandle.get<String>("serverId") ?: ""

    private val _uiState = MutableStateFlow<UiState<McpServerToolsUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<McpServerToolsUiState>> = _uiState.asStateFlow()

    init {
        loadServerTools()
    }

    fun loadServerTools() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                mcpServerRepository.refreshServers()
                mcpServerRepository.refreshServerTools(serverId)
                val server = mcpServerRepository.servers.value.firstOrNull { it.id == serverId }
                    ?: throw IllegalStateException("MCP server not found")
                val tools = mcpServerRepository.getServerTools(serverId).first()
                _uiState.value = UiState.Success(McpServerToolsUiState(server = server, tools = tools))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load MCP server tools"))
            }
        }
    }
}
