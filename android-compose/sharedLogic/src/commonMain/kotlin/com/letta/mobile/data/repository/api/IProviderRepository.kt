package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.model.ProviderUpdateParams
import kotlinx.coroutines.flow.StateFlow

interface IProviderRepository {
    val providers: StateFlow<List<Provider>>
    suspend fun refreshProviders(name: String? = null, providerType: String? = null)
    suspend fun getProvider(providerId: ProviderId): Provider
    suspend fun createProvider(params: ProviderCreateParams): Provider
    suspend fun updateProvider(providerId: ProviderId, params: ProviderUpdateParams): Provider
    suspend fun checkProvider(params: ProviderCheckParams)
    suspend fun checkExistingProvider(providerId: ProviderId)
    suspend fun deleteProvider(providerId: ProviderId)
}
