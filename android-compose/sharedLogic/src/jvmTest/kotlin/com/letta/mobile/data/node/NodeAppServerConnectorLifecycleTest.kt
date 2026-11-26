package com.letta.mobile.data.node

import com.letta.mobile.data.controller.extras.ExternalTool
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ToolSource
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import io.ktor.client.HttpClient
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A node started and stopped inside a long-lived process leaves nothing behind: its HTTP clients
 * close, its controller's collectors stop, and the App Server endpoint it is given is dialled as
 * documented or refused before any connection is attempted. Runs the real connector, the real CIO
 * clients and a real embedded WebSocket server.
 */
class NodeAppServerConnectorLifecycleTest {
    private val stateDir: File = Files.createTempDirectory("node-lifecycle").toFile()
    private val created = CopyOnWriteArrayList<HttpClient>()
    private val scopes = mutableListOf<CoroutineScope>()
    private var server: EmbeddedServer<*, *>? = null

    /** The production client factory, recording every client it hands out. */
    private val ports = NodePorts(
        httpClients = AppServerHttpClients { timeout -> JvmAppServerHttpClients.create(timeout).also(created::add) },
        log = object : NodeLog {
            override fun info(line: String) = Unit

            override fun error(line: String) = Unit
        },
    )

    @AfterTest
    fun tearDown() {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        created.forEach(HttpClient::close)
        server?.stop(0, 0)
        stateDir.deleteRecursively()
    }

    private fun nodeScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)

    private fun node(scope: CoroutineScope) = NodeComposition(
        config = NodeConfig(
            modelExposureFile = File(stateDir, "model-exposure.json").path,
            requestTimeoutMs = 1_000,
            endpoint = NodeEndpointConfig(bindAddr = "127.0.0.1:0"),
        ),
        ports = ports,
        scope = scope,
    )

    private fun HttpClient.isOpen(): Boolean = coroutineContext.job.isActive

    /** Waits for [client] to close; fails, naming the leak, if it is still open after [TIMEOUT]. */
    private suspend fun assertClosesAfterNodeStop(client: HttpClient) {
        val closed = withTimeoutOrNull(TIMEOUT) { client.coroutineContext.job.join() } != null
        assertTrue(closed, "an HttpClient the stopped node created is still open after $TIMEOUT")
    }

    @Test
    fun stoppingANodeClosesTheClientsItCreatedAndARestartedNodeGetsItsOwn() = runBlocking {
        val first = nodeScope()
        node(first).connect(AppServerEndpoint.fromWebSocketUrl("ws://127.0.0.1:$UNUSED_PORT"), ExternalToolRegistry.factoryDefault())
        node(first).connect(appServer = null, tools = ExternalToolRegistry.factoryDefault())
        val firstClients = created.toList()
        assertEquals(2, firstClients.size, "one client for the live connection, one for the stub")

        first.coroutineContext.job.cancelAndJoin()
        firstClients.forEach { assertClosesAfterNodeStop(it) }

        val restarted = nodeScope()
        node(restarted).connect(AppServerEndpoint.fromWebSocketUrl("ws://127.0.0.1:$UNUSED_PORT"), ExternalToolRegistry.factoryDefault())
        val restartedClient = created.last()
        assertTrue(restartedClient !in firstClients)
        assertTrue(restartedClient.isOpen(), "the restarted node's client is open while that node runs")

        restarted.coroutineContext.job.cancelAndJoin()
        assertClosesAfterNodeStop(restartedClient)
        assertTrue(created.none { it.isOpen() }, "no client outlives the node that created it")
    }

    @Test
    fun cancellingTheNodeScopeStopsTheControllersCollectors() = runBlocking {
        // The controller's tool re-advertiser collects the registry, which subscribes to each
        // live source's tools for as long as that collector runs.
        val sourceTools = MutableStateFlow<List<ExternalTool>>(emptyList())
        val tools = ExternalToolRegistry.factoryDefault().apply {
            addSource(
                object : ToolSource {
                    override val id = "lifecycle-probe"
                    override val tools = sourceTools
                },
            )
        }
        val scope = nodeScope()
        node(scope).connect(AppServerEndpoint.fromWebSocketUrl("ws://127.0.0.1:$UNUSED_PORT"), tools)
        val subscribed = withTimeoutOrNull(TIMEOUT) { sourceTools.subscriptionCount.first { it > 0 } } != null
        assertTrue(subscribed, "precondition: the controller's tool re-advertiser subscribes to the live source")
        // A controller runs under its own child job: closing one never stops the node.
        node(scope).connect(appServer = null, tools = ExternalToolRegistry.factoryDefault()).controller.close()
        assertTrue(scope.coroutineContext.job.isActive, "closing a controller leaves the node scope running")

        scope.coroutineContext.job.cancelAndJoin()

        // The leak itself: a controller collector still subscribed after its node scope ended.
        withTimeoutOrNull(TIMEOUT) { sourceTools.subscriptionCount.first { it == 0 } }
        assertEquals(0, sourceTools.subscriptionCount.value, "a controller collector is still subscribed after the node scope was cancelled")
    }

    @Test
    fun aHostPortEndpointDialsTheAppServerAndKeepsItsClientAcrossReconnects() = runBlocking {
        val sessions = MutableStateFlow(0)
        val port = startServer {
            sessions.update { it + 1 }
            // A non-terminal close: the node redials through the same client.
            close(CloseReason(CloseReason.Codes.GOING_AWAY, "restart"))
        }
        val scope = nodeScope()

        node(scope).connect(AppServerEndpoint(scheme = "ws", address = "127.0.0.1:$port"), ExternalToolRegistry.factoryDefault())

        // Both lanes dial, and each redials after the close: three sessions prove a redial happened.
        val dialled = withTimeoutOrNull(TIMEOUT) { sessions.first { it >= REDIALLED_SESSIONS } } != null
        assertTrue(dialled, "a host:port endpoint reached the App Server ${sessions.value} times, expected $REDIALLED_SESSIONS")
        assertEquals(1, created.size, "every generation dials through the connection's one client")
        assertTrue(created.single().isOpen(), "the client stays open across reconnects")
    }

    @Test
    fun anUnsupportedSchemeIsRejectedBeforeAnythingIsCreated() {
        val node = node(nodeScope())
        listOf(
            AppServerEndpoint(scheme = "iroh", address = "node-id"),
            AppServerEndpoint(scheme = "ws", address = "http://127.0.0.1:$UNUSED_PORT"),
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException>(endpoint.toString()) {
                node.connect(endpoint, ExternalToolRegistry.factoryDefault())
            }
        }
        assertEquals(0, created.size, "a rejected endpoint creates no client and starts no reconnect loop")
    }

    private fun startServer(handler: suspend io.ktor.server.websocket.DefaultWebSocketServerSession.() -> Unit): Int {
        val embedded = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing { webSocket("/ws") { handler() } }
        }
        embedded.start(wait = false)
        server = embedded
        return runBlocking { embedded.engine.resolvedConnectors().first().port }
    }

    private companion object {
        val TIMEOUT = 10.seconds
        const val REDIALLED_SESSIONS = 3

        /** A port nothing listens on: every dial is refused and the lane backs off. */
        const val UNUSED_PORT = 1
    }
}
