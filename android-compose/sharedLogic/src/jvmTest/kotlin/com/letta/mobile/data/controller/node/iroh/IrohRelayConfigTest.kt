package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.IrohRelayConfig
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Tests for iroh relay configuration.
 *
 * These tests verify that:
 * 1. Default relay config (n0 public relays) works
 * 2. Custom relay URLs can be configured
 * 3. Relay-disabled (direct-only) mode works
 * 4. Endpoints can be created with each config mode
 *
 * Note: These tests verify that the configuration is accepted and endpoints bind
 * successfully. Actual relay failover between two NAT-ed nodes requires a networked
 * integration test environment (two separate hosts behind different NATs) and cannot
 * be verified in unit tests.
 */
class IrohRelayConfigTest {

    @Test
    fun defaultRelayConfigCreatesEndpoint() = runTest {
        val config = IrohRelayConfig.Default
        val relayMode = IrohRelayConfigMapper.toRelayMode(config)
        
        val endpoint = Endpoint.bind(
            EndpointOptions(
                alpns = listOf("/test/0".toByteArray()),
                relayMode = relayMode,
            )
        )
        
        try {
            assertNotNull(endpoint, "Endpoint with default relay config should bind")
            assertNotNull(endpoint.addr(), "Endpoint should have an address")
        } finally {
            runCatching { endpoint.shutdown() }
            runCatching { endpoint.close() }
        }
    }

    @Test
    fun customRelayConfigCreatesEndpoint() = runTest {
        // Use a custom relay URL (doesn't need to be reachable for binding to work)
        val config = IrohRelayConfig.Custom(listOf("https://relay.example.com:443"))
        val relayMode = IrohRelayConfigMapper.toRelayMode(config)
        
        val endpoint = Endpoint.bind(
            EndpointOptions(
                alpns = listOf("/test/0".toByteArray()),
                relayMode = relayMode,
            )
        )
        
        try {
            assertNotNull(endpoint, "Endpoint with custom relay config should bind")
            assertNotNull(endpoint.addr(), "Endpoint should have an address")
        } finally {
            runCatching { endpoint.shutdown() }
            runCatching { endpoint.close() }
        }
    }

    @Test
    fun disabledRelayConfigCreatesEndpoint() = runTest {
        val config = IrohRelayConfig.Disabled
        val relayMode = IrohRelayConfigMapper.toRelayMode(config)
        
        val endpoint = Endpoint.bind(
            EndpointOptions(
                alpns = listOf("/test/0".toByteArray()),
                relayMode = relayMode,
            )
        )
        
        try {
            assertNotNull(endpoint, "Endpoint with disabled relay should bind (direct-only)")
            assertNotNull(endpoint.addr(), "Endpoint should have an address")
        } finally {
            runCatching { endpoint.shutdown() }
            runCatching { endpoint.close() }
        }
    }

    @Test
    fun customRelayConfigRequiresNonEmptyUrls() {
        assertFailsWith<IllegalArgumentException> {
            IrohRelayConfig.Custom(emptyList())
        }
    }

    @Test
    fun irohNodeEndpointUsesDefaultRelayByDefault() = runTest {
        val scope = TestScope()
        val endpoint = IrohNodeEndpoint(
            alpn = "/test/0".toByteArray(),
            scope = scope,
            // relayConfig not specified — should default to IrohRelayConfig.Default
        )
        
        endpoint.create()
        
        try {
            assertNotNull(endpoint.addr(), "Endpoint with default relay should bind")
        } finally {
            endpoint.shutdown()
        }
    }

    @Test
    fun irohNodeEndpointAcceptsCustomRelayConfig() = runTest {
        val scope = TestScope()
        val customConfig = IrohRelayConfig.Custom(listOf("https://my-relay.example.com:443"))
        
        val endpoint = IrohNodeEndpoint(
            alpn = "/test/0".toByteArray(),
            scope = scope,
            relayConfig = customConfig,
        )
        
        endpoint.create()
        
        try {
            assertNotNull(endpoint.addr(), "Endpoint with custom relay should bind")
        } finally {
            endpoint.shutdown()
        }
    }

    @Test
    fun irohNodeEndpointAcceptsDisabledRelayConfig() = runTest {
        val scope = TestScope()
        val endpoint = IrohNodeEndpoint(
            alpn = "/test/0".toByteArray(),
            scope = scope,
            relayConfig = IrohRelayConfig.Disabled,
        )
        
        endpoint.create()
        
        try {
            assertNotNull(endpoint.addr(), "Endpoint with disabled relay should bind")
        } finally {
            endpoint.shutdown()
        }
    }

    @Test
    fun multipleCustomRelayUrlsAreAccepted() = runTest {
        val config = IrohRelayConfig.Custom(
            listOf(
                "https://relay1.example.com:443",
                "https://relay2.example.com:443",
            )
        )
        val relayMode = IrohRelayConfigMapper.toRelayMode(config)
        
        val endpoint = Endpoint.bind(
            EndpointOptions(
                alpns = listOf("/test/0".toByteArray()),
                relayMode = relayMode,
            )
        )
        
        try {
            assertNotNull(endpoint, "Endpoint with multiple custom relays should bind")
            assertNotNull(endpoint.addr(), "Endpoint should have an address")
        } finally {
            runCatching { endpoint.shutdown() }
            runCatching { endpoint.close() }
        }
    }
}
