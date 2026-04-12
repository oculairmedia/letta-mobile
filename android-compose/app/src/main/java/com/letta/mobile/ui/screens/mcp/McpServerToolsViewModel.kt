package com.letta.mobile.ui.screens.mcp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.McpServerRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@androidx.compose.runtime.Immutable
data class McpToolRunState(
    val activeToolId: String? = null,
    val result: McpToolExecutionResult? = null,
    val errorMessage: String? = null,
)

@androidx.compose.runtime.Immutable
data class McpServerToolsUiState(
    val server: McpServer,
    val tools: ImmutableList<Tool> = persistentListOf(),
    val isRefreshing: Boolean = false,
    val refreshSummary: McpServerResyncResult? = null,
    val toolRunState: McpToolRunState = McpToolRunState(),
)

@HiltViewModel
class McpServerToolsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mcpServerRepository: McpServerRepository,
) : ViewModel() {

    private val serverId: String = savedStateHandle.get<String>("serverId")!!

    private val _uiState = MutableStateFlow<UiState<McpServerToolsUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<McpServerToolsUiState>> = _uiState.asStateFlow()

    init {
        loadServerTools()
    }

    fun loadServerTools() {
        viewModelScope.launch {
            val previous = (_uiState.value as? UiState.Success)?.data
            if (previous == null) {
                _uiState.value = UiState.Loading
            }
            try {
                mcpServerRepository.refreshServers()
                mcpServerRepository.refreshServerTools(serverId)
                _uiState.value = UiState.Success(buildUiState(previous))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load MCP server tools"))
            }
        }
    }

    fun refreshServerTools() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            _uiState.value = UiState.Success(current.copy(isRefreshing = true))
            try {
                val summary = mcpServerRepository.resyncServerTools(serverId)
                val refreshed = buildUiState(current).copy(
                    isRefreshing = false,
                    refreshSummary = summary,
                    toolRunState = current.toolRunState.copy(errorMessage = null),
                )
                _uiState.value = UiState.Success(refreshed)
            } catch (e: Exception) {
                _uiState.value = UiState.Success(
                    current.copy(
                        isRefreshing = false,
                        toolRunState = current.toolRunState.copy(
                            errorMessage = mapErrorToUserMessage(e, "Failed to refresh MCP server tools"),
                        ),
                    )
                )
            }
        }
    }

    fun runTool(toolId: String, rawArgs: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            val parsedArgs = parseArgs(rawArgs)
            if (parsedArgs == null && rawArgs.isNotBlank()) {
                _uiState.value = UiState.Success(
                    current.copy(
                        toolRunState = current.toolRunState.copy(
                            activeToolId = toolId,
                            result = null,
                            errorMessage = "Tool arguments must be a valid JSON object",
                        )
                    )
                )
                return@launch
            }

            _uiState.value = UiState.Success(
                current.copy(
                    toolRunState = McpToolRunState(activeToolId = toolId),
                )
            )

            try {
                val result = mcpServerRepository.runServerTool(
                    serverId = serverId,
                    toolId = toolId,
                    params = McpToolExecuteParams(args = parsedArgs ?: JsonObject(emptyMap())),
                )
                val updated = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    updated.copy(
                        toolRunState = McpToolRunState(
                            activeToolId = toolId,
                            result = result,
                        )
                    )
                )
            } catch (e: Exception) {
                val updated = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    updated.copy(
                        toolRunState = McpToolRunState(
                            activeToolId = toolId,
                            errorMessage = mapErrorToUserMessage(e, "Failed to run MCP tool"),
                        )
                    )
                )
            }
        }
    }

    fun clearToolRunState() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(toolRunState = McpToolRunState()))
    }

    private suspend fun buildUiState(previous: McpServerToolsUiState?): McpServerToolsUiState {
        val server = mcpServerRepository.servers.value.firstOrNull { it.id == serverId }
            ?: throw IllegalStateException("MCP server not found")
        val tools = mcpServerRepository.getServerTools(serverId).first()
        return McpServerToolsUiState(
            server = server,
            tools = tools.toImmutableList(),
            refreshSummary = previous?.refreshSummary,
            toolRunState = previous?.toolRunState ?: McpToolRunState(),
        )
    }

    private fun parseArgs(rawArgs: String): JsonObject? {
        if (rawArgs.isBlank()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(rawArgs).jsonObject }.getOrNull()
    }
}
