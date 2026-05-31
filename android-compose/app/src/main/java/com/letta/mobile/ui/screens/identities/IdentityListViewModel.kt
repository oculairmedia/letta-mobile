package com.letta.mobile.ui.screens.identities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityId
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IIdentityRepository
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
data class IdentityListUiState(
    val identities: ImmutableList<Identity> = persistentListOf(),
    val searchQuery: String = "",
    val selectedIdentity: Identity? = null,
    val knownAgents: ImmutableList<Agent> = persistentListOf(),
    val operationError: String? = null,
)

@HiltViewModel
class IdentityListViewModel @Inject constructor(
    private val identityRepository: IIdentityRepository,
    private val agentRepository: IAgentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<IdentityListUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<IdentityListUiState>> = _uiState.asStateFlow()

    init {
        loadIdentities()
    }

    fun loadIdentities() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            _uiState.value = UiState.Loading
            try {
                identityRepository.refreshIdentities()
                _uiState.value = UiState.Success(
                    IdentityListUiState(
                        identities = identityRepository.identities.value.toImmutableList(),
                        searchQuery = current?.searchQuery.orEmpty(),
                        selectedIdentity = current?.selectedIdentity?.let { selected ->
                            identityRepository.identities.value.firstOrNull { it.id == selected.id } ?: selected
                        },
                        knownAgents = current?.knownAgents ?: persistentListOf(),
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load identities"))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun getFilteredIdentities(): List<Identity> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        if (current.searchQuery.isBlank()) return current.identities
        val q = current.searchQuery.trim().lowercase()
        return current.identities.filter { identity ->
            identity.name.lowercase().contains(q) ||
                identity.identifierKey.lowercase().contains(q) ||
                identity.identityType.lowercase().contains(q)
        }
    }

    fun inspectIdentity(identityId: IdentityId) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                _uiState.value = UiState.Success(current.copy(operationError = null))
                refreshSelectedIdentity(identityId)
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to load identity details"))
            }
        }
    }

    fun clearSelectedIdentity() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedIdentity = null, knownAgents = persistentListOf()))
    }

    fun clearOperationError() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(operationError = null))
    }

    fun createIdentity(params: IdentityCreateParams, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                identityRepository.createIdentity(params)
                loadIdentities()
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to create identity"))
            }
        }
    }

    fun updateIdentity(identityId: IdentityId, params: IdentityUpdateParams, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val updated = identityRepository.updateIdentity(identityId, params)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        identities = current.identities.replaceIdentity(updated).toImmutableList(),
                        selectedIdentity = if (current.selectedIdentity?.id == identityId) updated else current.selectedIdentity,
                        operationError = null,
                    )
                )
                if (current.selectedIdentity?.id == identityId) {
                    refreshSelectedIdentity(identityId)
                }
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to update identity"))
            }
        }
    }

    fun deleteIdentity(identityId: IdentityId) {
        viewModelScope.launch {
            try {
                identityRepository.deleteIdentity(identityId)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        identities = current.identities.filterNot { it.id == identityId }.toImmutableList(),
                        selectedIdentity = if (current.selectedIdentity?.id == identityId) null else current.selectedIdentity,
                        operationError = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to delete identity"))
            }
        }
    }

    fun attachIdentity(agentId: AgentId, identityId: IdentityId, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                identityRepository.attachIdentity(agentId, identityId)
                refreshSelectedIdentity(identityId)
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to attach identity to agent"))
            }
        }
    }

    fun detachIdentity(agentId: AgentId, identityId: IdentityId) {
        viewModelScope.launch {
            try {
                identityRepository.detachIdentity(agentId, identityId)
                refreshSelectedIdentity(identityId)
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to detach identity from agent"))
            }
        }
    }

    private suspend fun refreshSelectedIdentity(identityId: IdentityId) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        val identity = identityRepository.getIdentity(identityId)
        val knownAgents = try {
            loadKnownAgents()
        } catch (_: Exception) {
            emptyList()
        }
        _uiState.value = UiState.Success(
            current.copy(
                identities = current.identities.replaceIdentity(identity).toImmutableList(),
                selectedIdentity = identity,
                knownAgents = knownAgents.toImmutableList(),
                operationError = null,
            )
        )
    }

    private suspend fun loadKnownAgents(): List<Agent> {
        agentRepository.refreshAgents()
        return agentRepository.agents.value.sortedBy { it.name.lowercase() }
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

private fun List<Identity>.replaceIdentity(updated: Identity): List<Identity> {
    val index = indexOfFirst { it.id == updated.id }
    return if (index >= 0) {
        toMutableList().apply { this[index] = updated }
    } else {
        this + updated
    }
}
