package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.controller.node.link.NodeCallExchange
import com.letta.mobile.data.controller.node.link.NodeConnectionWire
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The draining host refuses new `input` control frames with the retryable `input_accepted`
 * rejection the client's engine maps to a failed send (the same wire shape a busy or upstream
 * rejection uses), and starts no turn for them — while an input on a host that is NOT draining
 * still starts its turn, so the drain gate is the only thing that changed.
 *
 * Driven through a REAL [IrohNodeConnection] over a fake [NodeConnectionWire] (the same protocol
 * code an accepted Iroh peer runs, without QUIC), pre-authenticated the way the endpoint admits an
 * allowlisted peer, with the full control-frame gates (auth, capability) in between.
 */
class IrohNodeConnectionDrainingTest {
    private val peerId = "d".repeat(64)
    private val runtime = AppServerRuntimeScope("agent-drain-conn", "conv-drain-conn")

    /**
     * The node's wire as an in-process fake: control frames in on a channel, out into a list. Frames
     * arrive whole, so the carrier-side bounds ([maxReassembledBytes], [peerFrameParts]) have nothing
     * to reassemble, and no call lane is ever opened.
     */
    private class FakeWire(override val peerId: String) : NodeConnectionWire {
        override val localShell: Boolean = false
        val inbound = Channel<String>(Channel.UNLIMITED)
        val outbound = CopyOnWriteArrayList<String>()

        override suspend fun readControl(
            maxReassembledBytes: () -> Int,
            peerFrameParts: () -> Boolean,
            onFrame: suspend (String) -> Unit,
        ) {
            for (frame in inbound) onFrame(frame)
        }
        override suspend fun writeControl(frame: String) {
            outbound += frame
        }
        override suspend fun writePush(frame: String) = Unit
        override suspend fun nextCall(): NodeCallExchange? = null
        override fun onPeerCapabilities(capabilities: Set<String>) = Unit
        override fun close(code: Long, reason: String) = Unit
        override fun recordClosed() = Unit
        override fun closeAfterServe() = Unit
    }

    /** A controller whose turns hang until released, so a started turn stays live. */
    private class HangingTurnController : AppServerController {
        val commands = CopyOnWriteArrayList<TurnCommand>()
        val releaseTurns = CopyOnWriteArrayList<CompletableDeferred<Unit>>()

        override val state: Flow<AppServerControllerState> = flowOf(AppServerControllerState.Connected)

        override suspend fun startRuntime(
            agentId: AgentId,
            conversationId: ConversationId,
            cwd: String?,
            mode: AppServerPermissionMode?,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): CanonicalRuntime = CanonicalRuntime(
            scope = AppServerRuntimeScope(agentId = agentId.value, conversationId = conversationId.value),
        )

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> {
            commands += command
            val release = CompletableDeferred<Unit>()
            releaseTurns += release
            return flow { release.await() }
        }

        override suspend fun sync(
            runtime: AppServerRuntimeScope,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
            resumeInterruptedTurn: Boolean,
        ): AppServerInboundFrame.SyncResponse =
            AppServerInboundFrame.SyncResponse(requestId = "sync", runtime = runtime, success = true)

        override suspend fun abort(runtime: AppServerRuntimeScope, runId: String?): AppServerInboundFrame.AbortMessageResponse =
            AppServerInboundFrame.AbortMessageResponse(requestId = "abort", runtime = runtime, aborted = true, success = true)
    }

    private fun inputFrame(requestId: String?): String = AppServerProtocol.json.encodeToString(
        AppServerCommand.serializer(),
        AppServerCommand.Input(
            runtime = runtime,
            payload = AppServerInputPayload.CreateMessage(
                messages = listOf(AppServerInputMessage.userText(text = "drain me", clientMessageId = "cm-conn-drain")),
            ),
            requestId = requestId,
        ),
    )

    private fun TestScope.serveConnection(controller: AppServerController, host: NodeTurnHost): FakeWire {
        val wire = FakeWire(peerId)
        val connection = IrohNodeConnection(
            wire = wire,
            controller = controller,
            authentication = IrohConnectionAuthentication(
                authPolicy = IrohAuthPolicy.PeerAllowlist(setOf(peerId)),
                remoteEndpointId = peerId,
            ),
            turnHost = host,
        )
        backgroundScope.launch { connection.serve() }
        return wire
    }

    /**
     * A turn host on the test's virtual scheduler (the context, not just its Job — a scope built
     * from the Job alone has no dispatcher, so turn bodies would run on Dispatchers.Default and
     * race the assertions), exactly as [IrohNodeEndpoint.startTurnHost] parents its host.
     */
    private fun TestScope.drainingHost(): NodeTurnHost {
        val context = backgroundScope.coroutineContext
        return NodeTurnHost(CoroutineScope(context + SupervisorJob(context[Job])))
    }

    @Test
    fun anInputOnADrainingHostIsRefusedAndStartsNoTurn() = runTest {
        val controller = HangingTurnController()
        val turnHost = drainingHost()
        val wire = serveConnection(controller, turnHost)

        assertNotNull(turnHost.beginDrain(5.minutes, nowMs = { 0 }), "the drain begins before the input arrives")

        wire.inbound.send(inputFrame(requestId = "req-drain-1"))
        advanceTimeBy(100)
        runCurrent()

        val rejection = assertNotNull(
            wire.outbound.firstOrNull { it.contains("\"input_accepted\"") },
            "the input must be answered with an input_accepted rejection, saw: ${wire.outbound}",
        )
        val frame = AppServerProtocol.json.parseToJsonElement(rejection).jsonObject
        assertEquals("req-drain-1", frame["request_id"]?.jsonPrimitive?.content, "the rejection correlates to the input")
        assertEquals(false, frame["accepted"]?.jsonPrimitive?.boolean, "the input was not accepted")
        assertEquals(
            IrohNodeConnection.SHUTDOWN_DRAIN_INPUT_REJECTION,
            frame["error"]?.jsonPrimitive?.content,
            "the rejection is the retryable draining wording",
        )
        assertEquals(0, turnHost.liveTurnCount, "no turn was started for the refused input")
        assertTrue(controller.commands.isEmpty(), "the controller never saw the refused input")

        wire.inbound.close()
    }

    @Test
    fun inputWithoutRequestIdIsSilentlyRefusedDuringDrain() = runTest {
        val controller = HangingTurnController()
        val host = drainingHost()
        val wire = serveConnection(controller, host)
        assertNotNull(host.beginDrain(5.minutes))
        wire.inbound.send(inputFrame(null))
        advanceTimeBy(100)
        runCurrent()
        assertTrue(controller.commands.isEmpty())
        assertEquals(0, host.liveTurnCount)
        assertTrue(wire.outbound.none { it.contains("input_accepted") })
        wire.inbound.close()
    }

    @Test
    fun redeliveryDuringDrainAcknowledgesTheRunningTurn() = runTest {
        val controller = HangingTurnController()
        val host = drainingHost()
        val wire = serveConnection(controller, host)
        wire.inbound.send(inputFrame("req-redelivery"))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, controller.commands.size)
        assertNotNull(host.beginDrain(5.minutes))
        wire.inbound.send(inputFrame("req-redelivery"))
        advanceTimeBy(100)
        runCurrent()
        val acknowledgements = wire.outbound.filter { it.contains("input_accepted") }
        assertTrue(acknowledgements.any { it.contains("\"accepted\":true") })
        assertTrue(acknowledgements.none { it.contains("\"accepted\":false") })
        assertEquals(1, controller.commands.size)
        controller.releaseTurns.single().complete(Unit)
        wire.inbound.close()
    }

    @Test
    fun anInputOnAHostThatIsNotDrainingStillStartsItsTurn() = runTest {
        val controller = HangingTurnController()
        val turnHost = drainingHost()
        val wire = serveConnection(controller, turnHost)

        assertFalse(turnHost.isDraining, "no drain began")
        wire.inbound.send(inputFrame(requestId = "req-live-1"))
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, turnHost.liveTurnCount, "the input starts its turn when no drain is in progress")
        assertEquals(1, controller.commands.size)
        assertEquals(0, wire.outbound.count { it.contains("\"accepted\":false") }, "no rejection was sent")

        controller.releaseTurns.single().complete(Unit)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, turnHost.liveTurnCount, "the turn ends when its controller flow ends")

        wire.inbound.close()
    }
}
