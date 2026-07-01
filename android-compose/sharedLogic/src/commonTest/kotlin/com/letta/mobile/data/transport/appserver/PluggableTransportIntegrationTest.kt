package com.letta.mobile.data.transport.appserver

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Integration test demonstrating the pluggable transport abstraction.
 *
 * Shows how a future Iroh QUIC adapter (or any other transport) can be
 * swapped in place of the Ktor WebSocket adapter with ZERO changes to
 * the App Server protocol, client, or turn engine layers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluggableTransportIntegrationTest {
    @BeforeTest
    fun setUp() {
        AppServerTransportRegistry.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        AppServerTransportRegistry.clearForTest()
    }

    @Test
    fun clientWorksWithCustomTransportAdapter() = runTest {
        // Register a fake "iroh" transport adapter
        val adapter = FakeIrohTransportAdapter()
        AppServerTransportRegistry.register(adapter)

        // Create endpoint using the custom scheme
        val endpoint = AppServerEndpoint(
            scheme = "iroh",
            address = "node-abc123",
            bearerToken = "quic-token",
        )

        // Create transport via registry
        val transport = AppServerTransportRegistry.createTransport(endpoint, backgroundScope)
        assertIs<FakeIrohTransport>(transport)

        // Verify endpoint details were passed correctly
        val irohTransport = transport as FakeIrohTransport
        assertEquals("node-abc123", irohTransport.endpoint.address)
        assertEquals("quic-token", irohTransport.endpoint.bearerToken)

        // Use standard AppServerClient with the custom transport
        val client = DefaultAppServerClient(irohTransport, requestTimeoutMs = 1_000)

        // The client API is transport-agnostic
        client.events.test {
            irohTransport.emitStream(streamDelta())
            val received = awaitItem()
            assertIs<AppServerInboundFrame.StreamDelta>(received.frame)
            assertEquals(AppServerChannel.Stream, received.channel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun multipleTransportAdaptersCanCoexistAndBeSelectedByScheme() = runTest {
        // Register both fake Iroh and a fake alternative transport
        AppServerTransportRegistry.register(FakeIrohTransportAdapter())
        AppServerTransportRegistry.register(FakeAlternativeTransportAdapter())

        val irohEndpoint = AppServerEndpoint(scheme = "iroh", address = "node-1")
        val altEndpoint = AppServerEndpoint(scheme = "alt", address = "alt-addr")

        val irohTransport = AppServerTransportRegistry.createTransport(irohEndpoint, backgroundScope)
        val altTransport = AppServerTransportRegistry.createTransport(altEndpoint, backgroundScope)

        assertIs<FakeIrohTransport>(irohTransport)
        assertIs<FakeAlternativeTransport>(altTransport)

        // Both work with the same client code
        val irohClient = DefaultAppServerClient(irohTransport, requestTimeoutMs = 500)
        val altClient = DefaultAppServerClient(altTransport, requestTimeoutMs = 500)

        // Subscribe first, then emit
        irohClient.events.test {
            (irohTransport as FakeIrohTransport).emitControl(syncResponse())
            val received = awaitItem()
            assertIs<AppServerInboundFrame.SyncResponse>(received.frame)
            cancelAndIgnoreRemainingEvents()
        }

        altClient.events.test {
            (altTransport as FakeAlternativeTransport).emitStream(streamDelta())
            val received = awaitItem()
            assertIs<AppServerInboundFrame.StreamDelta>(received.frame)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun endpointFactoryMethodsWorkWithRegistry() = runTest {
        AppServerTransportRegistry.register(FakeWebSocketAdapter())

        val endpoint = AppServerEndpoint.fromWebSocketUrl(
            url = "ws://127.0.0.1:4500",
            bearerToken = null,
        )

        val transport = AppServerTransportRegistry.createTransport(endpoint, backgroundScope)
        assertIs<FakeWebSocketTransport>(transport)
    }

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
    }
}

// Example: Future Iroh QUIC transport adapter
private class FakeIrohTransportAdapter : AppServerTransportAdapter {
    override val scheme: String = "iroh"

    override fun createTransport(
        endpoint: AppServerEndpoint,
        scope: CoroutineScope,
        protocol: AppServerProtocol,
    ): AppServerTransport = FakeIrohTransport(endpoint, scope, protocol)
}

private class FakeIrohTransport(
    val endpoint: AppServerEndpoint,
    val scope: CoroutineScope,
    val protocol: AppServerProtocol,
) : AppServerTransport {
    override val controlFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
    override val streamFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)

    override suspend fun sendControl(command: AppServerCommand) {
        // Would send over QUIC here
    }

    fun emitControl(frame: AppServerInboundFrame) {
        controlFrames.tryEmit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Control,
                frame = frame,
                raw = buildJsonObject {},
            ),
        )
    }

    fun emitStream(frame: AppServerInboundFrame) {
        streamFrames.tryEmit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = buildJsonObject {},
            ),
        )
    }
}

// Example: Alternative transport
private class FakeAlternativeTransportAdapter : AppServerTransportAdapter {
    override val scheme: String = "alt"

    override fun createTransport(
        endpoint: AppServerEndpoint,
        scope: CoroutineScope,
        protocol: AppServerProtocol,
    ): AppServerTransport = FakeAlternativeTransport(endpoint, scope, protocol)
}

private class FakeAlternativeTransport(
    val endpoint: AppServerEndpoint,
    val scope: CoroutineScope,
    val protocol: AppServerProtocol,
) : AppServerTransport {
    override val controlFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
    override val streamFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)

    override suspend fun sendControl(command: AppServerCommand) {
        // Alternative transport implementation
    }

    fun emitStream(frame: AppServerInboundFrame) {
        streamFrames.tryEmit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = buildJsonObject {},
            ),
        )
    }
}

// Example: Fake WebSocket for testing fromWebSocketUrl
private class FakeWebSocketAdapter : AppServerTransportAdapter {
    override val scheme: String = "ws"

    override fun createTransport(
        endpoint: AppServerEndpoint,
        scope: CoroutineScope,
        protocol: AppServerProtocol,
    ): AppServerTransport = FakeWebSocketTransport()
}

private class FakeWebSocketTransport : AppServerTransport {
    override val controlFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
    override val streamFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
    override suspend fun sendControl(command: AppServerCommand) {}
}

private fun syncResponse(): AppServerInboundFrame.SyncResponse =
    AppServerInboundFrame.SyncResponse(
        requestId = "sync-1",
        runtime = AppServerRuntimeScope("agent-1", "conv-1"),
        success = true,
    )

private fun streamDelta(): AppServerInboundFrame.StreamDelta =
    AppServerInboundFrame.StreamDelta(
        runtime = AppServerRuntimeScope("agent-1", "conv-1"),
        eventSeq = 1,
        emittedAt = "2026-06-27T00:00:00Z",
        idempotencyKey = "evt-1",
        delta = buildJsonObject {},
    )
