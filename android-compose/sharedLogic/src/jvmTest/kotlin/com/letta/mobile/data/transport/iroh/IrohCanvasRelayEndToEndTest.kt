package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.InMemoryCanvasOpLog
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
import org.junit.Assume.assumeTrue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Two apps share a canvas through the host they both dial: a real in-process [IrohNodeEndpoint]
 * with an [IrohCanvasRelay], and two [IrohChannelTransport] clients each with an
 * [IrohCanvasClient] attached, as the Android and desktop apps wire them.
 *
 * OPT-IN like [IrohChannelTransportEndToEndTest] (live loopback QUIC is flaky on CI runners):
 *
 *   ./gradlew :sharedLogic:jvmTest -DrunIrohLiveE2E=true \
 *     --tests 'com.letta.mobile.data.transport.iroh.IrohCanvasRelayEndToEndTest'
 */
class IrohCanvasRelayEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val canvas = CanvasId("relay-e2e-canvas")

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

    @Test
    fun anEditOnOneAppReachesAnotherThroughTheHost() = runBlocking {
        val ticket = startHost()
        val (appA, opLogA) = connectApp(ticket)
        val (appB, _) = connectApp(ticket)

        val receivedByB = CopyOnWriteArrayList<CanvasOp>()
        scope.launch { appB.sync.subscribe(canvas).collect { receivedByB += it } }
        scope.launch { appA.sync.subscribe(canvas).collect { } }

        // As a session does: logged, then published. Whether A reaches the host before or after
        // publishing, the op arrives: live through the relay, or when the host asks A to catch up.
        val op = CanvasOp.SetBackgroundOp(opId = "op-1", actorId = "app-a", lamport = 1L, colorHex = "#123456")
        opLogA.append(canvas, op)
        appA.sync.publish(canvas, op)

        withTimeout(45.seconds) {
            while (receivedByB.none { it.opId == "op-1" }) delay(100.milliseconds)
        }
        assertEquals(listOf("op-1"), receivedByB.map { it.opId }, "B receives A's op, once")
    }

    @Test
    fun aCursorOnOneAppShowsOnAnother() = runBlocking {
        val ticket = startHost()
        val (appA, _) = connectApp(ticket)
        val (appB, _) = connectApp(ticket)

        val seenByB = appB.presence.observePresence(canvas) as kotlinx.coroutines.flow.StateFlow<List<CanvasPresence>>
        // Presence is a stream of heartbeats; keep sending until one gets through.
        withTimeout(45.seconds) {
            while (seenByB.value.none { it.peerId == "app-a" }) {
                appA.presence.updatePresence(
                    canvas,
                    CanvasPresence(peerId = "app-a", displayName = "A", colorHex = "#ff0000", cursorX = 1f, cursorY = 2f),
                )
                delay(300.milliseconds)
            }
        }
        assertTrue(seenByB.value.any { it.peerId == "app-a" }, "B sees A's cursor")
    }

    private suspend fun startHost(): String {
        val server = IrohNodeEndpoint(
            scope = scope,
            authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
            canvasRelay = IrohCanvasRelay(scope, InMemoryCanvasOpLog()),
        )
        server.create()
        server.start(IdleController())
        return server.ticketString()
    }

    private suspend fun connectApp(ticket: String): Pair<IrohCanvasClient, InMemoryCanvasOpLog> {
        val transport = IrohChannelTransport(scope = scope, onConnect = {}, forcedIrohUrl = "iroh://$ticket")
        val opLog = InMemoryCanvasOpLog()
        val client = IrohCanvasClient(scope, opLog)
        client.attach(transport.readyHandle)
        transport.connect(baseShimUrl = "iroh://$ticket", token = "", deviceId = "canvas-e2e", clientVersion = "e2e-1")
        return client to opLog
    }

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
