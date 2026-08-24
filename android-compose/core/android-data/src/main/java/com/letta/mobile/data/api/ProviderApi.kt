package com.letta.mobile.data.api

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderUpdateParams
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class ProviderApi @Inject constructor(
    private val apiClient: LettaApiClient,
) : com.letta.mobile.data.repository.api.ProviderRemoteSource {
    open override suspend fun listProviders(
        before: String?,
        after: String?,
        limit: Int?,
        order: String?,
        name: String?,
        providerType: String?,
    ): List<Provider> {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/providers/") {
            parameter("before", before)
            parameter("after", after)
            parameter("limit", limit)
            parameter("order", order)
            parameter("name", name)
            parameter("provider_type", providerType)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun retrieveProvider(providerId: String): Provider {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/providers/$providerId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun createProvider(params: ProviderCreateParams): Provider {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/providers/") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun updateProvider(providerId: String, params: ProviderUpdateParams): Provider {
        val (client, baseUrl) = apiClient.session()

        val response = client.patch("$baseUrl/v1/providers/$providerId") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open override suspend fun checkProvider(params: ProviderCheckParams) {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/providers/check") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open override suspend fun checkExistingProvider(providerId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/providers/$providerId/check")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open override suspend fun deleteProvider(providerId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/providers/$providerId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
