package com.letta.mobile.data.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketConnectionLivenessTest {

    @Test
    fun `websocket client uses 30s ping interval to keep streams alive and close stale sockets`() = runTest {
        val connection = WebSocketConnection(TestScope(), Json)

        // Access private lazy httpClient to verify configuration
        val delegateField = WebSocketConnection::class.java.getDeclaredField("httpClient\$delegate")
        delegateField.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val lazyClient = delegateField.get(connection) as Lazy<OkHttpClient>
        val client = lazyClient.value

        // Verifies we no longer rely on external 120s no-traffic closes
        // by explicitly setting a 30s ping/pong keepalive.
        assertEquals(30_000, client.pingIntervalMillis)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(0, client.readTimeoutMillis)
    }
}
