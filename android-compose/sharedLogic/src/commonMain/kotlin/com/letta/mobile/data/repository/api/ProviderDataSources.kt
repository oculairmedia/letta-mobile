package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderUpdateParams

/**
 * Remote HTTP (or equivalent) provider admin surface used by
 * [com.letta.mobile.data.repository.CachedProviderRepository].
 * Platform modules supply Ktor/[ProviderApi] bindings; Iroh list traffic goes
 * through [ProviderIrohSource].
 */
interface ProviderRemoteSource {
    suspend fun listProviders(
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
        name: String? = null,
        providerType: String? = null,
    ): List<Provider>

    suspend fun retrieveProvider(providerId: String): Provider
    suspend fun createProvider(params: ProviderCreateParams): Provider
    suspend fun updateProvider(providerId: String, params: ProviderUpdateParams): Provider
    suspend fun checkProvider(params: ProviderCheckParams)
    suspend fun checkExistingProvider(providerId: String)
    suspend fun deleteProvider(providerId: String)
}

/**
 * Iroh admin_rpc provider list surface. Implemented by
 * [com.letta.mobile.data.repository.IrohAdminRpcProviderSource].
 */
interface ProviderIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listProviders(): List<Provider>
}
