package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ProviderApi
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderUpdateParams
import com.letta.mobile.data.repository.api.IProviderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRepository @Inject constructor(
    private val providerApi: ProviderApi,
) : IProviderRepository {
    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    override val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    override suspend fun refreshProviders(name: String?, providerType: String?) {
        _providers.value = providerApi.listProviders(limit = 1000, name = name, providerType = providerType)
    }

    override suspend fun getProvider(providerId: String): Provider {
        return providerApi.retrieveProvider(providerId)
    }

    override suspend fun createProvider(params: ProviderCreateParams): Provider {
        val provider = providerApi.createProvider(params)
        upsertProvider(provider)
        return provider
    }

    override suspend fun updateProvider(providerId: String, params: ProviderUpdateParams): Provider {
        val provider = providerApi.updateProvider(providerId, params)
        upsertProvider(provider)
        return provider
    }

    override suspend fun checkProvider(params: ProviderCheckParams) {
        providerApi.checkProvider(params)
    }

    override suspend fun checkExistingProvider(providerId: String) {
        providerApi.checkExistingProvider(providerId)
    }

    override suspend fun deleteProvider(providerId: String) {
        providerApi.deleteProvider(providerId)
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
