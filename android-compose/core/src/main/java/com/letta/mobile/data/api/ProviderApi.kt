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
) {
    open suspend fun listProviders(
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
        name: String? = null,
        providerType: String? = null,
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

    open suspend fun retrieveProvider(providerId: String): Provider {
        val (client, baseUrl) = apiClient.session()

        val response = client.get("$baseUrl/v1/providers/$providerId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    open suspend fun createProvider(params: ProviderCreateParams): Provider {
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

    open suspend fun updateProvider(providerId: String, params: ProviderUpdateParams): Provider {
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

    open suspend fun checkProvider(params: ProviderCheckParams) {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/providers/check") {
            contentType(ContentType.Application.Json)
            setBody(params)
        }
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun checkExistingProvider(providerId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.post("$baseUrl/v1/providers/$providerId/check")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    open suspend fun deleteProvider(providerId: String) {
        val (client, baseUrl) = apiClient.session()

        val response = client.delete("$baseUrl/v1/providers/$providerId")
        if (response.status.value !in 200..299) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }
}
