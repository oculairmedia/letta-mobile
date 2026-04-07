package com.letta.mobile.ui.screens.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.repository.McpServerRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val isHealthy: Boolean,
    val tools: List<String>
)

@androidx.compose.runtime.Immutable
data class McpUiState(
    val selectedTab: Int = 0,
    val servers: List<McpServer> = emptyList(),
    val allTools: List<com.letta.mobile.data.model.Tool> = emptyList()
)

@HiltViewModel
class McpViewModel @Inject constructor(
    private val mcpServerRepository: McpServerRepository,
    private val toolRepository: ToolRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<McpUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<McpUiState>> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                mcpServerRepository.refreshServers()
                toolRepository.refreshTools()
                val servers = mcpServerRepository.servers.value.map {
                    McpServer(
                        id = it.id,
                        name = it.serverName,
                        url = it.serverUrl ?: "",
                        isHealthy = true,
                        tools = emptyList()
                    )
                }
                val tools = toolRepository.getTools().first()
                _uiState.value = UiState.Success(McpUiState(servers = servers, allTools = tools))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load MCP data")
            }
        }
    }

    fun selectTab(index: Int) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(selectedTab = index))
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            try {
                mcpServerRepository.deleteServer(serverId)
                loadData()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to delete server")
            }
        }
    }

    fun addServer(name: String, url: String) {
        viewModelScope.launch {
            try {
                val params = McpServerCreateParams(
                    serverName = name,
                    config = buildJsonObject { put("url", JsonPrimitive(url)) }
                )
                mcpServerRepository.createServer(params)
                loadData()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to add server")
            }
        }
    }
}
