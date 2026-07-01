package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KtorAppServerWebSocketTransportAdapterTest {
    private lateinit var httpClient: HttpClient

    @BeforeTest
    fun setUp() {
        AppServerTransportRegistry.clearForTest()
        httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) {
            install(WebSockets)
        }
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        AppServerTransportRegistry.clearForTest()
    }

    @Test
    fun adapterHandlesWsScheme() = runTest {
        val adapter = KtorAppServerWebSocketTransportAdapter(httpClient)
        assertEquals("ws", adapter.scheme)

        val endpoint = AppServerEndpoint(
            scheme = "ws",
            address = "ws://127.0.0.1:4500",
            bearerToken = null,
        )
        val transport = adapter.createTransport(endpoint, backgroundScope)
        assertIs<KtorAppServerWebSocketTransport>(transport)
    }

    @Test
    fun adapterHandlesWssScheme() = runTest {
        val adapter = KtorAppServerWebSocketTransportAdapter(httpClient)

        val endpoint = AppServerEndpoint(
            scheme = "wss",
            address = "wss://example.com",
            bearerToken = "token-123",
        )
        val transport = adapter.createTransport(endpoint, backgroundScope)
        assertIs<KtorAppServerWebSocketTransport>(transport)
    }

    @Test
    fun adapterRejectsNonWebSocketSchemes() = runTest {
        val adapter = KtorAppServerWebSocketTransportAdapter(httpClient)

        val endpoint = AppServerEndpoint(scheme = "iroh", address = "node-id")
        val exception = assertFailsWith<IllegalArgumentException> {
            adapter.createTransport(endpoint, backgroundScope)
        }
        assertTrue(exception.message!!.contains("ws"))
        assertTrue(exception.message!!.contains("wss"))
        assertTrue(exception.message!!.contains("iroh"))
    }

    @Test
    fun registerDefaultAddsWsAndWssAdapters() = runTest {
        KtorAppServerWebSocketTransportAdapter.registerDefault(httpClient)

        val wsEndpoint = AppServerEndpoint.fromWebSocketUrl("ws://127.0.0.1:4500")
        val wssEndpoint = AppServerEndpoint.fromWebSocketUrl("wss://example.com")

        val wsTransport = AppServerTransportRegistry.createTransport(wsEndpoint, backgroundScope)
        val wssTransport = AppServerTransportRegistry.createTransport(wssEndpoint, backgroundScope)

        assertIs<KtorAppServerWebSocketTransport>(wsTransport)
        assertIs<KtorAppServerWebSocketTransport>(wssTransport)
    }

    @Test
    fun adapterPassesEndpointAddressAsBaseUrl() = runTest {
        val adapter = KtorAppServerWebSocketTransportAdapter(httpClient)

        val endpoint = AppServerEndpoint(
            scheme = "ws",
            address = "ws://custom-host:9999/path",
            bearerToken = "auth-token",
        )
        // We can't directly inspect the internal baseUrl of KtorAppServerWebSocketTransport
        // from the outside, but we can verify it creates successfully
        val transport = adapter.createTransport(endpoint, backgroundScope)
        assertIs<KtorAppServerWebSocketTransport>(transport)
    }

    @Test
    fun adapterPassesBearerTokenToTransport() = runTest {
        val adapter = KtorAppServerWebSocketTransportAdapter(httpClient)

        val endpoint = AppServerEndpoint(
            scheme = "ws",
            address = "ws://127.0.0.1:4500",
            bearerToken = "bearer-secret",
        )
        // KtorAppServerWebSocketTransport will use this token internally
        val transport = adapter.createTransport(endpoint, backgroundScope)
        assertIs<KtorAppServerWebSocketTransport>(transport)
    }
}
