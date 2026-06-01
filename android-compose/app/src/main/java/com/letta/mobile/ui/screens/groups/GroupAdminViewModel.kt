package com.letta.mobile.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.repository.api.IGroupRepository
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
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class GroupAdminUiState(
    val groups: ImmutableList<Group> = persistentListOf(),
    val searchQuery: String = "",
    val selectedGroup: Group? = null,
    val selectedMessages: ImmutableList<LettaMessage> = persistentListOf(),
    val operationError: String? = null,
    val operationMessage: String? = null,
)

@HiltViewModel
class GroupAdminViewModel @Inject constructor(
    private val groupRepository: IGroupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<GroupAdminUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<GroupAdminUiState>> = _uiState.asStateFlow()

    init {
        loadGroups()
    }

    fun loadGroups() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            _uiState.value = UiState.Loading
            try {
                groupRepository.refreshGroups()
                val groups = groupRepository.groups.value
                _uiState.value = UiState.Success(
                    GroupAdminUiState(
                        groups = groups.toImmutableList(),
                        searchQuery = current?.searchQuery.orEmpty(),
                        selectedGroup = current?.selectedGroup?.let { selected ->
                            groups.firstOrNull { it.id == selected.id } ?: selected
                        },
                        selectedMessages = current?.selectedMessages ?: persistentListOf(),
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load groups"))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun getFilteredGroups(): List<Group> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        if (current.searchQuery.isBlank()) return current.groups
        val query = current.searchQuery.trim().lowercase()
        return current.groups.filter { group ->
            group.description.lowercase().contains(query) ||
                group.id.value.lowercase().contains(query) ||
                group.managerType.lowercase().contains(query) ||
                group.projectId?.value.orEmpty().lowercase().contains(query)
        }
    }

    fun inspectGroup(groupId: GroupId) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                val group = groupRepository.getGroup(groupId)
                val messages = groupRepository.listGroupMessages(groupId)
                _uiState.value = UiState.Success(
                    current.copy(
                        groups = current.groups.replaceGroup(group).toImmutableList(),
                        selectedGroup = group,
                        selectedMessages = messages.toImmutableList(),
                        operationError = null,
                        operationMessage = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to load group details"))
            }
        }
    }

    fun createGroup(
        description: String,
        agentIdsText: String,
        projectId: String?,
        sharedBlockIdsText: String,
        hidden: Boolean,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                val group = groupRepository.createGroup(
                    GroupCreateParams(
                        description = description,
                        agentIds = agentIdsText.toAgentIdList(),
                        projectId = projectId?.takeIf { it.isNotBlank() }?.let(::ProjectId),
                        sharedBlockIds = sharedBlockIdsText.toBlockIdList().takeIf { it.isNotEmpty() },
                        hidden = hidden,
                    )
                )
                val current = (_uiState.value as? UiState.Success)?.data
                if (current != null) {
                    _uiState.value = UiState.Success(
                        current.copy(
                            groups = current.groups.replaceGroup(group).toImmutableList(),
                            operationError = null,
                            operationMessage = null,
                        )
                    )
                } else {
                    loadGroups()
                }
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to create group"))
            }
        }
    }

    fun updateGroup(
        groupId: GroupId,
        description: String,
        agentIdsText: String,
        projectId: String?,
        sharedBlockIdsText: String,
        hidden: Boolean,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                val group = groupRepository.updateGroup(
                    groupId = groupId,
                    params = GroupUpdateParams(
                        description = description,
                        agentIds = agentIdsText.toAgentIdList(),
                        projectId = projectId?.takeIf { it.isNotBlank() }?.let(::ProjectId),
                        sharedBlockIds = sharedBlockIdsText.toBlockIdList(),
                        hidden = hidden,
                    )
                )
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        groups = current.groups.replaceGroup(group).toImmutableList(),
                        selectedGroup = if (current.selectedGroup?.id == groupId) group else current.selectedGroup,
                        operationError = null,
                        operationMessage = null,
                    )
                )
                if (current.selectedGroup?.id == groupId) {
                    inspectGroup(groupId)
                }
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to update group"))
            }
        }
    }

    fun deleteGroup(groupId: GroupId) {
        viewModelScope.launch {
            try {
                groupRepository.deleteGroup(groupId)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                val deletingSelected = current.selectedGroup?.id == groupId
                _uiState.value = UiState.Success(
                    current.copy(
                        groups = current.groups.filterNot { it.id == groupId }.toImmutableList(),
                        selectedGroup = if (deletingSelected) null else current.selectedGroup,
                        selectedMessages = if (deletingSelected) persistentListOf() else current.selectedMessages,
                        operationError = null,
                        operationMessage = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to delete group"))
            }
        }
    }

    fun sendMessage(groupId: GroupId, input: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                groupRepository.sendGroupMessage(groupId, MessageCreateRequest(input = input))
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        operationError = null,
                        operationMessage = "Message sent",
                    )
                )
                inspectGroup(groupId)
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to send group message"))
            }
        }
    }

    fun resetMessages(groupId: GroupId) {
        viewModelScope.launch {
            try {
                groupRepository.resetGroupMessages(groupId)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        selectedMessages = persistentListOf(),
                        operationError = null,
                        operationMessage = "Messages reset",
                    )
                )
                inspectGroup(groupId)
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to reset group messages"))
            }
        }
    }

    fun clearSelectedGroup() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedGroup = null, selectedMessages = persistentListOf(), operationMessage = null))
    }

    fun clearOperationError() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(operationError = null))
    }

    fun clearOperationMessage() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(operationMessage = null))
    }

    private fun setOperationError(message: String) {
        val current = (_uiState.value as? UiState.Success)?.data
        if (current != null) {
            _uiState.value = UiState.Success(current.copy(operationError = message, operationMessage = null))
        } else {
            _uiState.value = UiState.Error(message)
        }
    }
}

private fun List<Group>.replaceGroup(updated: Group): List<Group> {
    val index = indexOfFirst { it.id == updated.id }
    return if (index >= 0) {
        toMutableList().apply { this[index] = updated }
    } else {
        this + updated
    }
}

private fun String.toAgentIdList(): List<AgentId> =
    toCsvList().map(::AgentId)

private fun String.toBlockIdList(): List<BlockId> =
    toCsvList().map(::BlockId)

private fun String.toCsvList(): List<String> =
    split(',').mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() } }
