package com.letta.mobile.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.model.ProviderUpdateParams
import com.letta.mobile.data.repository.api.IProviderRepository
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
data class ProviderAdminUiState(
    val providers: ImmutableList<Provider> = persistentListOf(),
    val searchQuery: String = "",
    val selectedProvider: Provider? = null,
    val operationError: String? = null,
    val operationMessage: String? = null,
)

@HiltViewModel
class ProviderAdminViewModel @Inject constructor(
    private val providerRepository: IProviderRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<ProviderAdminUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ProviderAdminUiState>> = _uiState.asStateFlow()

    init {
        loadProviders()
    }

    fun loadProviders() {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data
            _uiState.value = UiState.Loading
            try {
                providerRepository.refreshProviders()
                val providers = providerRepository.providers.value
                _uiState.value = UiState.Success(
                    ProviderAdminUiState(
                        providers = providers.toImmutableList(),
                        searchQuery = current?.searchQuery.orEmpty(),
                        selectedProvider = current?.selectedProvider?.let { selected ->
                            providers.firstOrNull { it.id == selected.id } ?: selected
                        },
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to load providers"))
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(searchQuery = query))
    }

    fun getFilteredProviders(): List<Provider> {
        val current = (_uiState.value as? UiState.Success)?.data ?: return emptyList()
        if (current.searchQuery.isBlank()) return current.providers
        val query = current.searchQuery.trim().lowercase()
        return current.providers.filter { provider ->
            provider.name.lowercase().contains(query) ||
                provider.providerType.lowercase().contains(query) ||
                provider.providerCategory.orEmpty().lowercase().contains(query) ||
                provider.baseUrl.orEmpty().lowercase().contains(query) ||
                provider.region.orEmpty().lowercase().contains(query)
        }
    }

    fun inspectProvider(providerId: ProviderId) {
        viewModelScope.launch {
            val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
            try {
                val provider = providerRepository.getProvider(providerId)
                _uiState.value = UiState.Success(
                    current.copy(
                        providers = current.providers.replaceProvider(provider).toImmutableList(),
                        selectedProvider = provider,
                        operationError = null,
                        operationMessage = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to load provider details"))
            }
        }
    }

    fun createProvider(
        name: String,
        providerType: String,
        apiKey: String,
        baseUrl: String,
        accessKey: String,
        region: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                val provider = providerRepository.createProvider(
                    ProviderCreateParams(
                        name = name,
                        providerType = providerType,
                        apiKey = apiKey,
                        baseUrl = baseUrl.takeIf { it.isNotBlank() },
                        accessKey = accessKey.takeIf { it.isNotBlank() },
                        region = region.takeIf { it.isNotBlank() },
                    )
                )
                val current = (_uiState.value as? UiState.Success)?.data
                if (current != null) {
                    _uiState.value = UiState.Success(
                        current.copy(
                            providers = current.providers.replaceProvider(provider).toImmutableList(),
                            operationError = null,
                            operationMessage = null,
                        )
                    )
                } else {
                    loadProviders()
                }
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to create provider"))
            }
        }
    }

    fun updateProvider(
        providerId: ProviderId,
        apiKey: String,
        baseUrl: String,
        accessKey: String,
        region: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                val provider = providerRepository.updateProvider(
                    providerId = providerId,
                    params = ProviderUpdateParams(
                        apiKey = apiKey,
                        baseUrl = baseUrl.takeIf { it.isNotBlank() },
                        accessKey = accessKey.takeIf { it.isNotBlank() },
                        region = region.takeIf { it.isNotBlank() },
                    )
                )
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        providers = current.providers.replaceProvider(provider).toImmutableList(),
                        selectedProvider = if (current.selectedProvider?.id == providerId) provider else current.selectedProvider,
                        operationError = null,
                        operationMessage = null,
                    )
                )
                if (current.selectedProvider?.id == providerId) {
                    inspectProvider(providerId)
                }
                onSuccess()
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to update provider"))
            }
        }
    }

    fun deleteProvider(providerId: ProviderId) {
        viewModelScope.launch {
            try {
                providerRepository.deleteProvider(providerId)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                val deletingSelected = current.selectedProvider?.id == providerId
                _uiState.value = UiState.Success(
                    current.copy(
                        providers = current.providers.filterNot { it.id == providerId }.toImmutableList(),
                        selectedProvider = if (deletingSelected) null else current.selectedProvider,
                        operationError = null,
                        operationMessage = null,
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to delete provider"))
            }
        }
    }

    fun checkProvider(providerId: ProviderId) {
        viewModelScope.launch {
            try {
                providerRepository.checkExistingProvider(providerId)
                val current = (_uiState.value as? UiState.Success)?.data ?: return@launch
                _uiState.value = UiState.Success(
                    current.copy(
                        operationError = null,
                        operationMessage = "Provider check succeeded",
                    )
                )
            } catch (e: Exception) {
                setOperationError(mapErrorToUserMessage(e, "Failed to validate provider"))
            }
        }
    }

    fun clearSelectedProvider() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(current.copy(selectedProvider = null, operationMessage = null))
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

private fun List<Provider>.replaceProvider(updated: Provider): List<Provider> {
    val providerId = updated.id ?: return this
    val index = indexOfFirst { it.id == providerId }
    return if (index >= 0) {
        toMutableList().apply { this[index] = updated }
    } else {
        this + updated
    }
}
