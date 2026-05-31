package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ProviderApi
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.model.ProviderUpdateParams
import com.letta.mobile.data.repository.api.IProviderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ProviderRepository(
    private val providerApi: ProviderApi,
) : IProviderRepository {
    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    override val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    override suspend fun refreshProviders(name: String?, providerType: String?) {
        _providers.value = providerApi.listProviders(limit = 1000, name = name, providerType = providerType)
    }

    override suspend fun getProvider(providerId: ProviderId): Provider {
        return providerApi.retrieveProvider(providerId.value)
    }

    override suspend fun createProvider(params: ProviderCreateParams): Provider {
        val provider = providerApi.createProvider(params)
        upsertProvider(provider)
        return provider
    }

    override suspend fun updateProvider(providerId: ProviderId, params: ProviderUpdateParams): Provider {
        val provider = providerApi.updateProvider(providerId.value, params)
        upsertProvider(provider)
        return provider
    }

    override suspend fun checkProvider(params: ProviderCheckParams) {
        providerApi.checkProvider(params)
    }

    override suspend fun checkExistingProvider(providerId: ProviderId) {
        providerApi.checkExistingProvider(providerId.value)
    }

    override suspend fun deleteProvider(providerId: ProviderId) {
        providerApi.deleteProvider(providerId.value)
        _providers.update { current -> current.filterNot { it.id == providerId } }
    }

    private fun upsertProvider(provider: Provider) {
        val providerId = provider.id ?: return
        _providers.update { current ->
            val index = current.indexOfFirst { it.id == providerId }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = provider }
            } else {
                current + provider
            }
        }
    }
}
