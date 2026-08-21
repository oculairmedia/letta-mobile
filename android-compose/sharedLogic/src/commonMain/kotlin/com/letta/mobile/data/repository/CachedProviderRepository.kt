package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.model.ProviderUpdateParams
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.repository.api.ProviderIrohSource
import com.letta.mobile.data.repository.api.ProviderRemoteSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phase 5g: platform-neutral cached provider repository. Android supplies HTTP
 * [ProviderRemoteSource] and optional [ProviderIrohSource] for list refreshes.
 */
open class CachedProviderRepository(
    private val remote: ProviderRemoteSource,
    private val irohProviderSource: ProviderIrohSource? = null,
) : IProviderRepository {
    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    override val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    override suspend fun refreshProviders(name: String?, providerType: String?) {
        val irohSource = irohProviderSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            _providers.value = irohSource.listProviders()
            return
        }
        _providers.value = exhaustCursorPages(
            pageSize = PaginationConstants.DEFAULT_PAGE_SIZE,
            maxPages = PaginationConstants.DEFAULT_MAX_PAGES,
            fetch = { limit, after ->
                remote.listProviders(
                    limit = limit,
                    before = null,
                    after = after,
                    order = null,
                    name = name,
                    providerType = providerType,
                )
            },
            extractCursor = { provider -> provider.id?.value ?: "" },
            dedupKey = { provider -> provider.id?.value ?: "" },
        )
    }

    override suspend fun getProvider(providerId: ProviderId): Provider {
        return remote.retrieveProvider(providerId.value)
    }

    override suspend fun createProvider(params: ProviderCreateParams): Provider {
        val provider = remote.createProvider(params)
        upsertProvider(provider)
        return provider
    }

    override suspend fun updateProvider(providerId: ProviderId, params: ProviderUpdateParams): Provider {
        val provider = remote.updateProvider(providerId.value, params)
        upsertProvider(provider)
        return provider
    }

    override suspend fun checkProvider(params: ProviderCheckParams) {
        remote.checkProvider(params)
    }

    override suspend fun checkExistingProvider(providerId: ProviderId) {
        remote.checkExistingProvider(providerId.value)
    }

    override suspend fun deleteProvider(providerId: ProviderId) {
        remote.deleteProvider(providerId.value)
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
