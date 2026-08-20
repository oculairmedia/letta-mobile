package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ProviderApi
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.model.ProviderUpdateParams
import io.mockk.mockk

class FakeProviderApi : ProviderApi(mockk(relaxed = true)) {
    var providers = mutableListOf<Provider>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listProviders(before: String?, after: String?, limit: Int?, order: String?, name: String?, providerType: String?): List<Provider> {
        calls.add("listProviders")
        if (shouldFail) throw ApiException(500, "Server error")
        return providers.filter { (name == null || it.name == name) && (providerType == null || it.providerType == providerType) }
    }

    override suspend fun retrieveProvider(providerId: String): Provider {
        calls.add("retrieveProvider:$providerId")
        if (shouldFail) throw ApiException(500, "Server error")
        return providers.firstOrNull { it.id?.value == providerId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun createProvider(params: ProviderCreateParams): Provider {
        calls.add("createProvider:${params.name}")
        if (shouldFail) throw ApiException(500, "Server error")
        val provider = Provider(id = ProviderId("provider-${providers.size + 1}"), name = params.name, providerType = params.providerType, apiKey = params.apiKey, baseUrl = params.baseUrl, accessKey = params.accessKey, region = params.region)
        providers.add(provider)
        return provider
    }

    override suspend fun updateProvider(providerId: String, params: ProviderUpdateParams): Provider {
        calls.add("updateProvider:$providerId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = providers.indexOfFirst { it.id?.value == providerId }
        if (index < 0) throw ApiException(404, "Not found")
        val updated = providers[index].copy(apiKey = params.apiKey, baseUrl = params.baseUrl, accessKey = params.accessKey, region = params.region)
        providers[index] = updated
        return updated
    }

    override suspend fun checkProvider(params: ProviderCheckParams) {
        calls.add("checkProvider:${params.providerType}")
        if (shouldFail) throw ApiException(500, "Server error")
    }

    override suspend fun checkExistingProvider(providerId: String) {
        calls.add("checkExistingProvider:$providerId")
        if (shouldFail) throw ApiException(500, "Server error")
    }

    override suspend fun deleteProvider(providerId: String) {
        calls.add("deleteProvider:$providerId")
        if (shouldFail) throw ApiException(500, "Server error")
        providers.removeAll { it.id?.value == providerId }
    }
}
