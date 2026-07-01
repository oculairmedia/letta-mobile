package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppServerEndpointTest {
    @Test
    fun endpointDescriptorCarriesSchemeAddressAndAuth() {
        val endpoint = AppServerEndpoint(
            scheme = "ws",
            address = "127.0.0.1:4500",
            bearerToken = "test-token",
        )
        assertEquals("ws", endpoint.scheme)
        assertEquals("127.0.0.1:4500", endpoint.address)
        assertEquals("test-token", endpoint.bearerToken)
    }

    @Test
    fun endpointBearerTokenIsOptional() {
        val endpoint = AppServerEndpoint(
            scheme = "ws",
            address = "127.0.0.1:4500",
        )
        assertNull(endpoint.bearerToken)
    }

    @Test
    fun fromWebSocketUrlExtractsSchemeForWs() {
        val endpoint = AppServerEndpoint.fromWebSocketUrl("ws://127.0.0.1:4500")
        assertEquals("ws", endpoint.scheme)
        assertEquals("ws://127.0.0.1:4500", endpoint.address)
        assertNull(endpoint.bearerToken)
    }

    @Test
    fun fromWebSocketUrlExtractsSchemeForWss() {
        val endpoint = AppServerEndpoint.fromWebSocketUrl(
            url = "wss://example.com/path",
            bearerToken = "token-123",
        )
        assertEquals("wss", endpoint.scheme)
        assertEquals("wss://example.com/path", endpoint.address)
        assertEquals("token-123", endpoint.bearerToken)
    }

    @Test
    fun fromWebSocketUrlRejectsInvalidSchemes() {
        assertFailsWith<IllegalArgumentException> {
            AppServerEndpoint.fromWebSocketUrl("http://example.com")
        }
        assertFailsWith<IllegalArgumentException> {
            AppServerEndpoint.fromWebSocketUrl("iroh://node-id")
        }
    }

    @Test
    fun arbitrarySchemesSupportedForFutureTransports() {
        val endpoint = AppServerEndpoint(
            scheme = "iroh",
            address = "node-abc123",
            bearerToken = "secret",
        )
        assertEquals("iroh", endpoint.scheme)
        assertEquals("node-abc123", endpoint.address)
    }
}
