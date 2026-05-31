package com.letta.mobile.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderId
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.api.IFolderRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class FolderAdminUiState(
    val folders: ImmutableList<Folder> = persistentListOf(),
    val searchQuery: String = "",
    val selectedFolder: Folder? = null,
    val selectedFolderAgents: ImmutableList<String> = persistentListOf(),
    val selectedFolderFiles: ImmutableList<FileMetadata> = persistentListOf(),
    val selectedFolderPassages: ImmutableList<Passage> = persistentListOf(),
    val folderMetadata: OrganizationSourcesStats? = null,
    val operationError: String? = null,
)

@HiltViewModel
class FolderAdminViewModel @Inject constructor(
    private val folderRepository: IFolderRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<FolderAdminUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<FolderAdminUiState>> = _uiState.asStateFlow()

    init {
        loadFolders()
    }

    fun loadFolders() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            _uiState.value = UiState.Loading
            try {
                folderRepository.refreshFolders()
                val folders = folderRepository.folders.value
                val metadata = try {
                    folderRepository.getFolderMetadata()
                } catch (_: Exception) {
                    current?.folderMetadata
                }
                _uiState.value = UiState.Success(
                    FolderAdminUiState(
                        folders = folders.toImmutableList(),
                        searchQuery = current?.searchQuery.orEmpty(),
                        selectedFolder = current?.selectedFolder?.let { selected ->
                            folders.firstOrNull { it.id == selected.id } ?: selected
                        },
                        selectedFolderAgents = current?.selectedFolderAgents ?: persistentListOf(),
                        selectedFolderFiles = current?.selectedFolderFiles ?: persistentListOf(),
                        selectedFolderPassages = current?.selectedFolderPassages ?: persistentListOf(),
                        folderMetadata = metadata,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load folders"))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun getFilteredFolders(): List<Folder> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        if (current.searchQuery.isBlank()) return current.folders
        val query = current.searchQuery.trim().lowercase()
        return current.folders.filter { folder ->
            folder.name.lowercase().contains(query) ||
                folder.description.orEmpty().lowercase().contains(query) ||
                folder.instructions.orEmpty().lowercase().contains(query)
        }
    }

    fun inspectFolder(folderId: FolderId) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                val folder = folderRepository.getFolder(folderId)
                val agents = folderRepository.listAgentsForFolder(folderId)
                val files = folderRepository.listFolderFiles(folderId)
                val passages = folderRepository.listFolderPassages(folderId)
                _uiState.value = UiState.Success(
                    current.copy(
                        folders = current.folders.replaceFolder(folder).toImmutableList(),
                        selectedFolder = folder,
                        selectedFolderAgents = agents.toImmutableList(),
                        selectedFolderFiles = files.toImmutableList(),
                        selectedFolderPassages = passages.toImmutableList(),
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to load folder details"))
            }
        }
    }

    fun createFolder(name: String, description: String?, instructions: String?, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val folder = folderRepository.createFolder(
                    FolderCreateParams(
                        name = name,
                        description = description?.takeIf { it.isNotBlank() },
                        instructions = instructions?.takeIf { it.isNotBlank() },
                    )
                )
                val current = (_uiState.value as? UiState.Success)?.data
                if (current != null) {
                    _uiState.value = UiState.Success(
                        current.copy(
                            folders = current.folders.replaceFolder(folder).toImmutableList(),
                            operationError = null,
                        )
                    )
                } else {
                    loadFolders()
                }
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to create folder"))
            }
        }
    }

    fun updateFolder(folderId: FolderId, name: String, description: String?, instructions: String?, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val folder = folderRepository.updateFolder(
                    folderId = folderId,
                    params = FolderUpdateParams(
                        name = name,
                        description = description?.takeIf { it.isNotBlank() },
                        instructions = instructions?.takeIf { it.isNotBlank() },
                    )
                )
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        folders = current.folders.replaceFolder(folder).toImmutableList(),
                        selectedFolder = if (current.selectedFolder?.id == folderId) folder else current.selectedFolder,
                        operationError = null,
                    )
                )
                if (current.selectedFolder?.id == folderId) {
                    inspectFolder(folderId)
                }
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to update folder"))
            }
        }
    }

    fun deleteFolder(folderId: FolderId) {
        viewModelScope.launch {
            try {
                folderRepository.deleteFolder(folderId)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        folders = current.folders.filterNot { it.id == folderId }.toImmutableList(),
                        selectedFolder = if (current.selectedFolder?.id == folderId) null else current.selectedFolder,
                        selectedFolderAgents = if (current.selectedFolder?.id == folderId) persistentListOf() else current.selectedFolderAgents,
                        selectedFolderFiles = if (current.selectedFolder?.id == folderId) persistentListOf() else current.selectedFolderFiles,
                        selectedFolderPassages = if (current.selectedFolder?.id == folderId) persistentListOf() else current.selectedFolderPassages,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to delete folder"))
            }
        }
    }

    fun clearSelectedFolder() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            current.copy(
                selectedFolder = null,
                selectedFolderAgents = persistentListOf(),
                selectedFolderFiles = persistentListOf(),
                selectedFolderPassages = persistentListOf(),
            )
        )
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

private fun List<Folder>.replaceFolder(updated: Folder): List<Folder> {
    val index = indexOfFirst { it.id == updated.id }
    return if (index >= 0) {
        toMutableList().apply { this[index] = updated }
    } else {
        this + updated
    }
}
