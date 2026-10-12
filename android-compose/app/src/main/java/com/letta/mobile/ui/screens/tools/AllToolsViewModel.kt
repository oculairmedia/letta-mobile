package com.letta.mobile.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.tools.ToolLibraryController
import com.letta.mobile.data.tools.ToolLibraryState
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class AllToolsUiState(
    val tools: ImmutableList<Tool> = persistentListOf(),
    val mcpToolIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val selectedTags: Set<String> = emptySet(),
    val isLoadingMore: Boolean = false,
    val isLoadingMcpTools: Boolean = false,
    val hasMorePages: Boolean = true,
    val currentOffset: Int = 0,
)

@HiltViewModel
class AllToolsViewModel @Inject constructor(
    private val toolRepository: IToolRepository,
    mcpServerRepository: IMcpServerRepository,
) : ViewModel() {
    private val controller = ToolLibraryController(
        toolRepository = toolRepository,
        mcpServerRepository = mcpServerRepository,
        scope = viewModelScope,
        errorMessageMapper = { throwable ->
            (throwable as? Exception)?.let { mapErrorToUserMessage(it, "Failed to load tools") }
                ?: throwable.message
                ?: "Failed to load tools"
        },
    )

    /** The raw library, for the shared Skills page's Tools section (letta-mobile-zxk2n.32). */
    val toolState: StateFlow<ToolLibraryState> = controller.state

    val uiState: StateFlow<UiState<AllToolsUiState>> = controller.state
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STATE_STOP_TIMEOUT_MS), UiState.Loading)

    init {
        controller.start()
    }

    fun loadTools() {
        controller.loadTools()
    }

    fun loadMoreTools() {
        controller.loadMoreTools()
    }

    fun createTool(sourceCode: String) {
        viewModelScope.launch {
            try {
                toolRepository.upsertTool(ToolCreateParams(sourceCode = sourceCode))
                controller.loadTools()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controller.showError(mapErrorToUserMessage(e, "Failed to create tool"))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        controller.updateSearchQuery(query)
    }

    fun getFilteredTools(): List<Tool> {
        return controller.state.value.filteredTools
    }

    fun getAllTags(): List<String> {
        return controller.state.value.allTags
    }

    fun toggleTag(tag: String) {
        controller.toggleTag(tag)
    }

    fun clearTags() {
        controller.clearTags()
    }

    override fun onCleared() {
        controller.close()
    }
}

/** Keeps the mapped state alive across a configuration change, then lets it stop. */
private const val UI_STATE_STOP_TIMEOUT_MS = 5_000L

private fun ToolLibraryState.toUiState(): UiState<AllToolsUiState> {
    val message = errorMessage
    return when {
        isLoading && tools.isEmpty() -> UiState.Loading
        message != null && tools.isEmpty() -> UiState.Error(message)
        else -> UiState.Success(
            AllToolsUiState(
                tools = tools.toImmutableList(),
                mcpToolIds = mcpToolIds,
                searchQuery = searchQuery,
                selectedTags = selectedTags,
                isLoadingMore = isLoadingMore,
                isLoadingMcpTools = isLoadingMcpTools,
                hasMorePages = hasMorePages,
                currentOffset = currentOffset,
            ),
        )
    }
}
