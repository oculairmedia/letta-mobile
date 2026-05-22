package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderUpdateParams
import kotlinx.coroutines.flow.StateFlow

interface IProviderRepository {
    val providers: StateFlow<List<Provider>>
    suspend fun refreshProviders(name: String? = null, providerType: String? = null)
    suspend fun getProvider(providerId: String): Provider
    suspend fun createProvider(params: ProviderCreateParams): Provider
    suspend fun updateProvider(providerId: String, params: ProviderUpdateParams): Provider
    suspend fun checkProvider(params: ProviderCheckParams)
    suspend fun checkExistingProvider(providerId: String)
    suspend fun deleteProvider(providerId: String)
}
