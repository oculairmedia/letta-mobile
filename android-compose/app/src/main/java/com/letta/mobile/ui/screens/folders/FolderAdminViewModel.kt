package com.letta.mobile.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.FileMetadata
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderCreateParams
import com.letta.mobile.data.model.FolderUpdateParams
import com.letta.mobile.data.model.OrganizationSourcesStats
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class FolderAdminUiState(
    val folders: List<Folder> = emptyList(),
    val searchQuery: String = "",
    val selectedFolder: Folder? = null,
    val selectedFolderAgents: List<String> = emptyList(),
    val selectedFolderFiles: List<FileMetadata> = emptyList(),
    val selectedFolderPassages: List<Passage> = emptyList(),
    val folderMetadata: OrganizationSourcesStats? = null,
    val operationError: String? = null,
)

@HiltViewModel
class FolderAdminViewModel @Inject constructor(
    private val folderRepository: FolderRepository,
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
                        folders = folders,
                        searchQuery = current?.searchQuery.orEmpty(),
                        selectedFolder = current?.selectedFolder?.let { selected ->
                            folders.firstOrNull { it.id == selected.id } ?: selected
                        },
                        selectedFolderAgents = current?.selectedFolderAgents.orEmpty(),
                        selectedFolderFiles = current?.selectedFolderFiles.orEmpty(),
                        selectedFolderPassages = current?.selectedFolderPassages.orEmpty(),
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

    fun inspectFolder(folderId: String) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                val folder = folderRepository.getFolder(folderId)
                val agents = folderRepository.listAgentsForFolder(folderId)
                val files = folderRepository.listFolderFiles(folderId)
                val passages = folderRepository.listFolderPassages(folderId)
                _uiState.value = UiState.Success(
                    current.copy(
                        folders = current.folders.replaceFolder(folder),
                        selectedFolder = folder,
                        selectedFolderAgents = agents,
                        selectedFolderFiles = files,
                        selectedFolderPassages = passages,
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
                            folders = current.folders.replaceFolder(folder),
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

    fun updateFolder(folderId: String, name: String, description: String?, instructions: String?, onSuccess: () -> Unit = {}) {
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
                        folders = current.folders.replaceFolder(folder),
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

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            try {
                folderRepository.deleteFolder(folderId)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        folders = current.folders.filterNot { it.id == folderId },
                        selectedFolder = if (current.selectedFolder?.id == folderId) null else current.selectedFolder,
                        selectedFolderAgents = if (current.selectedFolder?.id == folderId) emptyList() else current.selectedFolderAgents,
                        selectedFolderFiles = if (current.selectedFolder?.id == folderId) emptyList() else current.selectedFolderFiles,
                        selectedFolderPassages = if (current.selectedFolder?.id == folderId) emptyList() else current.selectedFolderPassages,
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
                selectedFolderAgents = emptyList(),
                selectedFolderFiles = emptyList(),
                selectedFolderPassages = emptyList(),
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
