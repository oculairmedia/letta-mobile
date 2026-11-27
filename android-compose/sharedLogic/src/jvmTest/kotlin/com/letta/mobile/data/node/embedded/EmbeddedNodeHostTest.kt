package com.letta.mobile.data.node.embedded

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.node.JvmAppServerHttpClients
import com.letta.mobile.data.node.NodeConfig
import com.letta.mobile.data.node.NodeEndpointConfig
import com.letta.mobile.data.node.NodeLog
import com.letta.mobile.data.node.NodePorts
import com.letta.mobile.data.node.attach.NodeSessionAppServerTransport
import com.letta.mobile.data.node.carrier.NodeAddress
import com.letta.mobile.data.node.carrier.NodeCarrierException
import com.letta.mobile.data.node.carrier.NodeCarrierFailure
import com.letta.mobile.data.node.carrier.NodeCredential
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.node.carrier.NodeOpenRequest
import com.letta.mobile.data.node.carrier.NodeSession
import com.letta.mobile.data.runtime.host.LocalAppServerLease
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The embedded node's lifecycle, driven through the shell's own session: it starts on the first dial, a restart loses
 * the links to its generation and releases that generation's App Server lease, and a closed node refuses dials. The
 * App Server lease points nowhere: the node's controller only reconnects in the background, which these checks never
 * wait on.
 */
class EmbeddedNodeHostTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateDir: File = Files.createTempDirectory("embedded-node").toFile()
    private val leases = AtomicInteger()
    private val released = AtomicInteger()

    private val node = EmbeddedNodeHost(
        EmbeddedNodeSpec(
            config = {
                NodeConfig(
                    modelExposureFile = File(stateDir, "model-exposure.json").path,
                    requestTimeoutMs = 1_000,
                    endpoint = NodeEndpointConfig(bindAddr = "127.0.0.1:0"),
                )
            },
            ports = NodePorts(httpClients = JvmAppServerHttpClients, log = SilentLog),
            tools = { ExternalToolRegistry.factoryDefault() },
            appServer = { lease() },
        ),
        scope,
    )

    @AfterTest
    fun tearDown() {
        node.close()
        scope.cancel()
        stateDir.deleteRecursively()
    }

    @Test
    fun theFirstDialStartsTheNodeAndTheShellIsAdmittedByItsCarrier() = scenario {
        assertFalse(node.running.value)
        val session = open()
        assertTrue(node.running.value)
        assertEquals(EmbeddedNodeHost.NODE_IDENTITY, session.node)
        assertEquals(1, leases.get(), "one generation, one App Server lease")
        assertEquals("admin_rpc_response", session.adminRpc("health.check", null).type, "the node's router answers the shell")
        open()
        assertEquals(1, leases.get(), "a second shell attaches to the running generation")
    }

    @Test
    fun aRestartLosesTheLinksAndReleasesTheLease() = scenario {
        val session = open()
        val transport = NodeSessionAppServerTransport(session, scope)
        node.restart()
        assertEquals(NodeLinkClosure.LOST, session.closure.first { it != null })
        assertIs<AppServerConnectionState.Failed>(transport.connectionState.first { it !is AppServerConnectionState.Ready })
        while (released.get() == 0) delay(10) // the old generation's lease is released once its scope has wound down
        assertEquals(1, released.get())
        open()
        assertEquals(2, leases.get(), "the next dial starts a new generation")
    }

    @Test
    fun aStreamedAdminRpcEndsAfterItsResponse() = scenario {
        val frames = open().adminRpcStream("health.check", null).toList()
        assertEquals("admin_rpc_response", frames.last().type)
    }

    @Test
    fun aClosedNodeRefusesDialsAndAnotherAddressIsUnreachable() = scenario {
        val elsewhere = runCatching { node.dial(NodeAddress("in-process:elsewhere")) }.exceptionOrNull()
        assertIs<NodeCarrierFailure.Unreachable>((elsewhere as NodeCarrierException).failure)
        node.close()
        val closed = runCatching { open() }.exceptionOrNull()
        assertIs<NodeCarrierFailure.Unreachable>((closed as NodeCarrierException).failure)
    }

    private suspend fun open(): NodeSession =
        NodeSession.open(node, NodeOpenRequest(EmbeddedNodeHost.ADDRESS, NodeCredential.CarrierIdentity), scope)

    private fun lease(): LocalAppServerLease {
        leases.incrementAndGet()
        return object : LocalAppServerLease {
            override val serverUrl: String = "ws://127.0.0.1:9"
            override val bearerToken: String = "unused"

            override fun close() {
                released.incrementAndGet()
            }
        }
    }

    private fun scenario(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30.seconds) { body() } }

    private object SilentLog : NodeLog {
        override fun info(line: String) = Unit

        override fun error(line: String) = Unit
    }
}
