package com.letta.mobile.data.repository

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.repository.api.VibesyncEventStreamSource
import com.letta.mobile.data.repository.api.VibesyncStreamEndpointUnavailableException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.utils.io.ByteReadChannel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class LettaHttpVibesyncEventStreamSource @Inject constructor(
    private val apiClient: LettaApiClient,
) : VibesyncEventStreamSource {
    override suspend fun openStream(): ByteReadChannel {
        val client = apiClient.getClient()
        val baseUrl = apiClient.getBaseUrl().trimEnd('/')
        val response = client.get("$baseUrl/api/events/stream")
        when (response.status.value) {
            in 200..299 -> Unit
            404 -> throw VibesyncStreamEndpointUnavailableException()
            else -> throw IllegalStateException("Vibesync event stream failed with HTTP ${response.status.value}")
        }
        return response.body<ByteReadChannel>()
    }
}
