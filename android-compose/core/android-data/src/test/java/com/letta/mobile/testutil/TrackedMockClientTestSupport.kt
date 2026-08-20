package com.letta.mobile.testutil

import io.ktor.client.HttpClient
import org.junit.After

open class TrackedMockClientTestSupport {
    private val trackedClients = mutableListOf<HttpClient>()

    protected fun trackClient(client: HttpClient): HttpClient {
        trackedClients += client
        return client
    }

    @After
    fun closeTrackedClients() {
        trackedClients.forEach { it.close() }
        trackedClients.clear()
    }
}
