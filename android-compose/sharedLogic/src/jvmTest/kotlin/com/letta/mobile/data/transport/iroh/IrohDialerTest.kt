package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IrohDialerTest {

    @Test
    fun dialRequiresValidIrohUrl() = runTest(UnconfinedTestDispatcher()) {
        val dialer = IrohDialer(
            scope = this,
            secretKeyStore = FakeSecretKeyStore(),
            onConnectionLost = { _, _ -> },
            onCloseResources = {},
        )

        val nonIrohConfig = IrohConnectConfig(
            baseShimUrl = "http://localhost:8080",
            token = "token",
            deviceId = "dev",
            clientVersion = "1.0",
        )

        val err = assertFailsWith<IllegalStateException> {
            dialer.dial(nonIrohConfig)
        }
        assertTrue(err.message.orEmpty().contains("requires backend URL iroh://"))
    }

    @Test
    fun dialInvokesConnectingCallbackBeforeBinding() = runTest(UnconfinedTestDispatcher()) {
        var connectingCalled = false
        val dialer = IrohDialer(
            scope = this,
            secretKeyStore = FakeSecretKeyStore(),
            onConnectionLost = { _, _ -> },
            onCloseResources = {},
            bindEndpoint = { _, _ -> throw RuntimeException("mock bind failed") },
        )

        val config = IrohConnectConfig(
            baseShimUrl = "iroh://invalid-ticket",
            token = "token",
            deviceId = "dev",
            clientVersion = "1.0",
        )

        runCatching {
            dialer.dial(config, onConnecting = { connectingCalled = true })
        }

        assertTrue(connectingCalled, "onConnecting callback must be invoked when dial begins")
    }

    @Test
    fun closeResourcesInvokedOnDialFailure() = runTest(UnconfinedTestDispatcher()) {
        var closeReason: String? = null
        val dialer = IrohDialer(
            scope = this,
            secretKeyStore = FakeSecretKeyStore(),
            onConnectionLost = { _, _ -> },
            onCloseResources = { reason -> closeReason = reason },
            bindEndpoint = { _, _ -> throw RuntimeException("mock bind failed") },
        )

        val config = IrohConnectConfig(
            baseShimUrl = "iroh://invalid-ticket-that-fails",
            token = "token",
            deviceId = "dev",
            clientVersion = "1.0",
        )

        runCatching {
            dialer.dial(config)
        }

        assertEquals("dial_failed", closeReason)
    }

    @Test
    fun notebookPeersAreExplicitAndInvalidFilesFailClosed() {
        val root = Files.createTempDirectory("notebook-peers-")
        val file = root.resolve("peers.json")
        val peer = "a".repeat(64)
        assertEquals(null, NotebookPeerProvisioning.read(file))
        Files.writeString(file, """{"peerIds":["$peer"]}""")
        assertEquals(setOf(peer), NotebookPeerProvisioning.read(file))
        Files.writeString(file, """{"peerIds":["$peer","$peer"]}""")
        assertFailsWith<IllegalArgumentException> { NotebookPeerProvisioning.read(file) }
        Files.writeString(file, """{"peerIds":["${"A".repeat(64)}"]}""")
        assertFailsWith<IllegalArgumentException> { NotebookPeerProvisioning.read(file) }
    }

    @Test
    fun inboundNotebookUsesIndependentPolicyAndStopsOnClose(): Unit = runBlocking {
        assumeTrue("Live Iroh requires -DrunIrohLiveE2E=true", System.getProperty("runIrohLiveE2E") == "true")
        val allowed = Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled()))
        val denied = Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled()))
        val client = Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled(), alpns = listOf(AUTOMERGE_REPO_ALPN)))
        val accepted = CompletableDeferred<String>()
        val deniedId = IrohDiagnostics.endpointIdHex(denied.addr().id())
        val allowedId = IrohDiagnostics.endpointIdHex(allowed.addr().id())
        val rejected = CompletableDeferred<Unit>()
        val handler = object : IrohNodeProtocolHandler, AutoCloseable {
            override val alpn = AUTOMERGE_REPO_ALPN
            override fun authorize(remoteEndpointId: String): Boolean {
                if (remoteEndpointId == deniedId) rejected.complete(Unit)
                return remoteEndpointId == allowedId
            }
            override suspend fun accept(connection: Connection, remoteEndpointId: String) {
                accepted.complete(remoteEndpointId)
            }
            override fun close() = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val inbound = IrohDialer.InboundNotebook(client, handler, scope)
        try {
            inbound.start()
            denied.connect(client.addr(), AUTOMERGE_REPO_ALPN).close(0L, ByteArray(0))
            withTimeout(15_000) { rejected.await() }
            allowed.connect(client.addr(), AUTOMERGE_REPO_ALPN).close(0L, ByteArray(0))
            assertEquals(allowedId, withTimeout(15_000) { accepted.await() })
            inbound.close()
            assertFailsWith<IllegalStateException> { inbound.start() }
        } finally {
            inbound.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            listOf(allowed, denied, client).forEach { it.shutdown(); it.close() }
        }
    }

    private class FakeSecretKeyStore(private val key: ByteArray = ByteArray(32)) : com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore {
        override suspend fun loadOrCreate(): ByteArray = key
    }
}
