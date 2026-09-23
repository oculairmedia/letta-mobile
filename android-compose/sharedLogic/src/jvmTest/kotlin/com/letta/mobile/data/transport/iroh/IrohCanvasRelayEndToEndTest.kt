package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasRelayClient
import com.letta.mobile.data.canvas.CanvasRelayMessage
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.canvas.CanvasSceneDigest
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSyncHealth
import com.letta.mobile.data.canvas.InMemoryCanvasDeliveryStore
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.InMemoryCanvasOpLog
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.relayTopicOf
import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.controller.node.iroh.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.RelayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Two apps share a conversation canvas through the host they both dial, over real loopback QUIC:
 * an in-process [IrohNodeEndpoint] with an [IrohCanvasRelay], and two [IrohChannelTransport] clients
 * each with an [IrohCanvasRelayClient] attached and a [CanvasSession] editing - as Android and
 * desktop wire them.
 *
 * OPT-IN like [IrohChannelTransportEndToEndTest] (live loopback QUIC is flaky on CI runners): run it
 * with `:sharedLogic:jvmTest -DrunIrohLiveE2E=true --tests '*IrohCanvasRelayEndToEndTest'`.
 */
class IrohCanvasRelayEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(
            "Live-QUIC Iroh E2E is opt-in; pass -DrunIrohLiveE2E=true to run it.",
            System.getProperty("runIrohLiveE2E") == "true",
        )
    }

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    private class App(val client: CanvasRelayClient, val session: CanvasSession, val documents: InMemoryCanvasDocumentStore) {
        suspend fun digest(): String = CanvasSceneDigest.of(documents.get(session.canvasId)!!.sceneJson)
        fun health(): CanvasSyncHealth = client.health(session.canvasId).value
    }

    @Test
    fun editsCrossBothWaysAndBothAppsSettleOnOneScene() = runBlocking {
        val ticket = startHost()
        val android = connectApp(ticket, "conv-live")
        val desktop = connectApp(ticket, "conv-live")
        awaitHealth(android, desktop) { it == CanvasSyncHealth.Synced }

        android.session.applyLocal(note("from-android", 1))
        desktop.session.applyLocal(note("from-desktop", 2))

        withTimeout(30.seconds) {
            while (android.digest() != desktop.digest() || android.health() != CanvasSyncHealth.Synced || desktop.health() != CanvasSyncHealth.Synced) {
                delay(100.milliseconds)
            }
        }
        assertEquals(
            android.client.relayView(android.session.canvasId).hostCursor,
            desktop.client.relayView(desktop.session.canvasId).hostCursor,
        )
    }

    @Test
    fun aCursorOnOneAppShowsOnAnother() = runBlocking {
        val ticket = startHost()
        val android = connectApp(ticket, "conv-cursors")
        val desktop = connectApp(ticket, "conv-cursors")
        awaitHealth(android, desktop) { it == CanvasSyncHealth.Synced }

        var seen = emptyList<CanvasPresence>()
        val watch = scope.launch { desktop.client.observePresence(desktop.session.canvasId).collect { seen = it } }
        withTimeout(30.seconds) {
            while (seen.none { it.displayName == "Android" }) {
                android.client.updatePresence(
                    android.session.canvasId,
                    CanvasPresence(peerId = "cursor", displayName = "Android", colorHex = "#ff0000", cursorX = 1f, cursorY = 2f),
                )
                delay(300.milliseconds)
            }
        }
        watch.cancel()
    }

    @Test
    fun aCanvasDialWithoutAnAuthenticatedAppServerConnectionIsRefused() = runBlocking {
        val ticket = startHost()
        // A peer that never signed in on the App Server dials the canvas protocol directly.
        val stranger = Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode()))
        val connection = stranger.connect(EndpointTicket.fromString(ticket).endpointAddr(), IrohCanvasRelay.CANVAS_RELAY_ALPN)
        // The host closes it on arrival: the write or the read fails, and nothing ever comes back.
        val reply = withTimeoutOrNull(10.seconds) {
            runCatching {
                val stream = connection.openBi()
                CanvasRelayFraming.write(stream.send(), CanvasRelayProtocol.encode(CanvasRelayMessage.Join("conversation:x", "canvas-x")))
                CanvasRelayFraming.read(stream.recv())
            }.getOrNull()
        }
        assertNull(reply, "the host must close an unauthenticated canvas connection without answering")
        stranger.close()
    }

    private suspend fun awaitHealth(vararg apps: App, until: (CanvasSyncHealth) -> Boolean) {
        withTimeout(30.seconds) {
            while (!apps.all { until(it.health()) }) delay(100.milliseconds)
        }
    }

    private suspend fun startHost(): String {
        val server = IrohNodeEndpoint(
            scope = scope,
            authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
            canvasRelay = IrohCanvasRelay(scope, InMemoryCanvasRelayStore()),
        )
        server.create()
        server.start(IdleController())
        return server.ticketString()
    }

    private suspend fun connectApp(ticket: String, conversationId: String): App {
        val transport = IrohChannelTransport(scope = scope, onConnect = {}, forcedIrohUrl = "iroh://$ticket")
        val documents = InMemoryCanvasDocumentStore()
        val opLog = InMemoryCanvasOpLog()
        val client = CanvasRelayClient(opLog, InMemoryCanvasDeliveryStore(), topicOf = { documents.relayTopicOf(it) })
        IrohCanvasRelayClient(scope, client).attach(transport.readyHandle)
        val session = CanvasSession.getOrCreateForConversation(
            documents,
            conversationId,
            CanvasConversationOptions(opLog = opLog, syncTransport = client),
        )
        session.startSync(scope)
        transport.connect(baseShimUrl = "iroh://$ticket", token = "", deviceId = "canvas-e2e", clientVersion = "e2e-1")
        return App(client, session, documents)
    }

    private fun note(id: String, lamport: Long) =
        CanvasOp.AddElementOp(id, CanvasSession.LOCAL_USER_ACTOR_ID, lamport, "el-$id", """{"id":"el-$id","type":"Text","text":"$id"}""")

    /** A host with no agents: the canvas relay is what is under test. */
    private class IdleController : AppServerController {
        override val state = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)

        override suspend fun startRuntime(
            agentId: AgentId,
            conversationId: ConversationId,
            cwd: String?,
            mode: AppServerPermissionMode?,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): CanonicalRuntime = throw UnsupportedOperationException()

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = emptyFlow()

        override suspend fun sync(
            runtime: AppServerRuntimeScope,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): AppServerInboundFrame.SyncResponse = throw UnsupportedOperationException()

        override suspend fun abort(
            runtime: AppServerRuntimeScope,
            runId: String?,
        ): AppServerInboundFrame.AbortMessageResponse = throw UnsupportedOperationException()
    }
}
