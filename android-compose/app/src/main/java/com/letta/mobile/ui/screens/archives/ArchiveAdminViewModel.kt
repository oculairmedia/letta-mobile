package com.letta.mobile.ui.screens.archives

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import com.letta.mobile.data.model.EmbeddingConfig
import com.letta.mobile.data.repository.ArchiveRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class ArchiveAdminUiState(
    val archives: List<Archive> = emptyList(),
    val searchQuery: String = "",
    val selectedArchive: Archive? = null,
    val selectedArchiveAgents: List<Agent> = emptyList(),
    val operationError: String? = null,
)

@HiltViewModel
class ArchiveAdminViewModel @Inject constructor(
    private val archiveRepository: ArchiveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<ArchiveAdminUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ArchiveAdminUiState>> = _uiState.asStateFlow()

    init {
        loadArchives()
    }

    fun loadArchives() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            _uiState.value = UiState.Loading
            try {
                archiveRepository.refreshArchives()
                val archives = archiveRepository.archives.value
                _uiState.value = UiState.Success(
                    ArchiveAdminUiState(
                        archives = archives,
                        searchQuery = current?.searchQuery.orEmpty(),
                        selectedArchive = current?.selectedArchive?.let { selected ->
                            archives.firstOrNull { it.id == selected.id } ?: selected
                        },
                        selectedArchiveAgents = current?.selectedArchiveAgents.orEmpty(),
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load archives"))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun getFilteredArchives(): List<Archive> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        if (current.searchQuery.isBlank()) return current.archives
        val query = current.searchQuery.trim().lowercase()
        return current.archives.filter { archive ->
            archive.name.lowercase().contains(query) ||
                archive.description.orEmpty().lowercase().contains(query) ||
                archive.vectorDbProvider.orEmpty().lowercase().contains(query)
        }
    }

    fun inspectArchive(archiveId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                val archive = archiveRepository.getArchive(archiveId)
                val agents = archiveRepository.listAgentsForArchive(archiveId)
                _uiState.value = UiState.Success(
                    current.copy(
                        archives = current.archives.replaceArchive(archive),
                        selectedArchive = archive,
                        selectedArchiveAgents = agents,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to load archive details"))
            }
        }
    }

    fun createArchive(name: String, description: String?, embeddingModel: String?, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val archive = archiveRepository.createArchive(
                    ArchiveCreateParams(
                        name = name,
                        description = description?.takeIf { it.isNotBlank() },
                        embeddingConfig = EmbeddingConfig(embeddingModel = embeddingModel?.takeIf { it.isNotBlank() }),
                    )
                )
                val current = (_uiState.value as? UiState.Success)?.data
                if (current != null) {
                    _uiState.value = UiState.Success(
                        current.copy(
                            archives = current.archives.replaceArchive(archive),
                            operationError = null,
                        )
                    )
                } else {
                    loadArchives()
                }
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to create archive"))
            }
        }
    }

    fun updateArchive(archiveId: String, name: String, description: String?, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val archive = archiveRepository.updateArchive(
                    archiveId = archiveId,
                    params = ArchiveUpdateParams(
                        name = name,
                        description = description?.takeIf { it.isNotBlank() },
                    )
                )
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        archives = current.archives.replaceArchive(archive),
                        selectedArchive = if (current.selectedArchive?.id == archiveId) archive else current.selectedArchive,
                        operationError = null,
                    )
                )
                if (current.selectedArchive?.id == archiveId) {
                    inspectArchive(archiveId)
                }
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to update archive"))
            }
        }
    }

    fun deleteArchive(archiveId: String) {
        viewModelScope.launch {
            try {
                archiveRepository.deleteArchive(archiveId)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        archives = current.archives.filterNot { it.id == archiveId },
                        selectedArchive = if (current.selectedArchive?.id == archiveId) null else current.selectedArchive,
                        selectedArchiveAgents = if (current.selectedArchive?.id == archiveId) emptyList() else current.selectedArchiveAgents,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to delete archive"))
            }
        }
    }

    fun clearSelectedArchive() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedArchive = null, selectedArchiveAgents = emptyList()))
    }

    fun clearOperationError() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(operationError = null))
    }

    private fun setOperationError(message: String) {
        val current = (_uiState.value as? UiState.Success)?.data
        if (current != null) {
            _uiState.value = UiState.Success(current.copy(operationError = message))
        } else {
            _uiState.value = UiState.Error(message)
        }
    }
}

private fun List<Archive>.replaceArchive(updated: Archive): List<Archive> {
    val index = indexOfFirst { it.id == updated.id }
    return if (index >= 0) {
        toMutableList().apply { this[index] = updated }
    } else {
        this + updated
    }
}
