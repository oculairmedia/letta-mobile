package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTransportAdapterTest {
    @BeforeTest
    fun setUp() {
        AppServerTransportRegistry.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        AppServerTransportRegistry.clearForTest()
    }

    @Test
    fun registryStoresAndRetrievesAdaptersByScheme() {
        val adapter = FakeTransportAdapter("test-scheme")
        AppServerTransportRegistry.register(adapter)

        val retrieved = AppServerTransportRegistry.getAdapter("test-scheme")
        assertEquals(adapter, retrieved)
    }

    @Test
    fun registryReturnsNullForUnregisteredScheme() {
        val adapter = AppServerTransportRegistry.getAdapter("unknown")
        assertNull(adapter)
    }

    @Test
    fun registryOverwritesAdapterForSameScheme() {
        val adapter1 = FakeTransportAdapter("scheme")
        val adapter2 = FakeTransportAdapter("scheme")

        AppServerTransportRegistry.register(adapter1)
        AppServerTransportRegistry.register(adapter2)

        val retrieved = AppServerTransportRegistry.getAdapter("scheme")
        assertEquals(adapter2, retrieved)
    }

    @Test
    fun createTransportUsesRegisteredAdapter() = runTest {
        val adapter = FakeTransportAdapter("fake")
        AppServerTransportRegistry.register(adapter)

        val endpoint = AppServerEndpoint(
            scheme = "fake",
            address = "test-address",
            bearerToken = "test-token",
        )
        val transport = AppServerTransportRegistry.createTransport(
            endpoint = endpoint,
            scope = backgroundScope,
        )

        assertIs<FakeTransport>(transport)
        val fake = transport as FakeTransport
        assertEquals("test-address", fake.endpoint.address)
        assertEquals("test-token", fake.endpoint.bearerToken)
    }

    @Test
    fun createTransportThrowsIfNoAdapterForScheme() = runTest {
        val endpoint = AppServerEndpoint(scheme = "unregistered", address = "addr")

        val exception = assertFailsWith<IllegalArgumentException> {
            AppServerTransportRegistry.createTransport(endpoint, backgroundScope)
        }
        assertNotNull(exception.message)
        assertTrue(exception.message!!.contains("No transport adapter registered"))
        assertTrue(exception.message!!.contains("unregistered"))
    }

    @Test
    fun errorMessageListsAvailableSchemes() = runTest {
        AppServerTransportRegistry.register(FakeTransportAdapter("ws"))
        AppServerTransportRegistry.register(FakeTransportAdapter("iroh"))

        val endpoint = AppServerEndpoint(scheme = "missing", address = "addr")
        val exception = assertFailsWith<IllegalArgumentException> {
            AppServerTransportRegistry.createTransport(endpoint, backgroundScope)
        }
        assertTrue(exception.message!!.contains("iroh"))
        assertTrue(exception.message!!.contains("ws"))
    }

    @Test
    fun multipleAdaptersCanCoexist() = runTest {
        val wsAdapter = FakeTransportAdapter("ws")
        val irohAdapter = FakeTransportAdapter("iroh")

        AppServerTransportRegistry.register(wsAdapter)
        AppServerTransportRegistry.register(irohAdapter)

        val wsEndpoint = AppServerEndpoint(scheme = "ws", address = "ws-addr")
        val irohEndpoint = AppServerEndpoint(scheme = "iroh", address = "iroh-addr")

        val wsTransport = AppServerTransportRegistry.createTransport(wsEndpoint, backgroundScope)
        val irohTransport = AppServerTransportRegistry.createTransport(irohEndpoint, backgroundScope)

        assertIs<FakeTransport>(wsTransport)
        assertIs<FakeTransport>(irohTransport)
        assertEquals("ws-addr", (wsTransport as FakeTransport).endpoint.address)
        assertEquals("iroh-addr", (irohTransport as FakeTransport).endpoint.address)
    }

    @Test
    fun adapterReceivesProtocolParameter() = runTest {
        val adapter = FakeTransportAdapter("test")
        AppServerTransportRegistry.register(adapter)

        val endpoint = AppServerEndpoint(scheme = "test", address = "addr")
        val customProtocol = AppServerProtocol
        val transport = AppServerTransportRegistry.createTransport(
            endpoint = endpoint,
            scope = backgroundScope,
            protocol = customProtocol,
        )

        assertIs<FakeTransport>(transport)
        assertEquals(customProtocol, (transport as FakeTransport).protocol)
    }
}

private class FakeTransportAdapter(override val scheme: String) : AppServerTransportAdapter {
    override fun createTransport(
        endpoint: AppServerEndpoint,
        scope: CoroutineScope,
        protocol: AppServerProtocol,
    ): AppServerTransport = FakeTransport(endpoint, scope, protocol)
}

private class FakeTransport(
    val endpoint: AppServerEndpoint,
    val scope: CoroutineScope,
    val protocol: AppServerProtocol,
) : AppServerTransport {
    override val controlFrames: Flow<AppServerReceivedFrame> =
        MutableSharedFlow(extraBufferCapacity = 16)
    override val streamFrames: Flow<AppServerReceivedFrame> =
        MutableSharedFlow(extraBufferCapacity = 16)

    override suspend fun sendControl(command: AppServerCommand) {
        // No-op for fake
    }
}
