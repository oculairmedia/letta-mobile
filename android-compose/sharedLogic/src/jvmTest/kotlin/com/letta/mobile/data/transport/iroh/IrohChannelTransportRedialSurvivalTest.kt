package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.SESSION_RECONNECT_GRACE_MS
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.RedialWhileTurnActive
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.96: an Android Iroh turn in flight across a redial (a Wi-Fi toggle drops the
 * QUIC connection and the supervisor dials again) survives it, through the whole
 * [IrohChannelTransport]: the second dial re-attaches the runtime and the turn completes on that
 * dial's frames, with no recovery announcement. A redial that outlasts the reconnect grace fails
 * the turn exactly once; a config change still ends it at once. Virtual time; nothing sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohChannelTransportRedialSurvivalTest {

    @Test
    fun aTurnInFlightAcrossARedialCompletesOnTheSecondDial() = runTest {
        val harness = Harness(this)
        val firstDial = harness.connectAndStartTurn()
        firstDial.emit(assistantDelta(seq = 1))
        runCurrent()

        harness.dropAndRedial()
        val secondDial = harness.dials[1]
        assertTrue(harness.transport.hasActiveChatTurn(CONVERSATION), "the turn must survive the redial")
        val reattach = secondDial.runtimeStarts.single()
        assertEquals(true, reattach.recoverApprovals, "the second dial re-attaches the turn's runtime")

        secondDial.emit(stopReasonDelta(seq = 2))
        runCurrent()
        advanceTimeBy(AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS + 1)
        runCurrent()

        assertEquals(listOf("completed"), harness.terminalStatuses())
        assertTrue(harness.frames.none { it is ServerFrame.Error }, "a survived redial reports no error")
        assertEquals(emptyList(), harness.recoveries, "a surviving turn must not also be announced for recovery")
        assertEquals(1, firstDial.inputs + secondDial.inputs, "the message is sent once")
    }

    @Test
    fun aRedialThatOutlastsTheGraceFailsTheTurnExactlyOnce() = runTest {
        val harness = Harness(this, redialsHang = true)
        harness.connectAndStartTurn()

        harness.transport.reportConnectionLostForTest(LOST, harness.handles.single())
        advanceTimeBy(SESSION_RECONNECT_GRACE_MS - 1)
        runCurrent()
        assertEquals(emptyList(), harness.terminalStatuses(), "the turn waits out the reconnect grace")

        advanceTimeBy(SESSION_RECONNECT_GRACE_MS)
        runCurrent()

        assertEquals(listOf("failed"), harness.terminalStatuses())
        assertEquals(listOf(TurnFailureNotices.CONNECTION_LOST_KIND), harness.errorCodes())
    }

    @Test
    fun aConfigChangeStillEndsTheTurnAtOnce() = runTest {
        val harness = Harness(this)
        harness.connectAndStartTurn()

        harness.config = harness.config.copy(baseShimUrl = "iroh://other-host")
        harness.transport.connect(harness.config.baseShimUrl, "", "device", "test")
        runCurrent()
        advanceTimeBy(IrohSessionLossCutOff.SESSION_LOSS_TERMINAL_GRACE_MS)
        runCurrent()

        assertEquals(listOf("failed"), harness.terminalStatuses())
        assertEquals(2, harness.dials.size, "the new config dialled")
    }

    private class Harness(private val test: TestScope, private val redialsHang: Boolean = false) {
        var config = IrohConnectConfig("iroh://ticket", "", "device", "test")
        val dials = mutableListOf<ScriptedDial>()
        val handles = mutableListOf<IrohConnectionHandle>()
        val frames = mutableListOf<ServerFrame>()
        val recoveries = mutableListOf<RedialWhileTurnActive>()

        val transport: IrohChannelTransport = IrohChannelTransport(
            scope = test.backgroundScope,
            activeConfigProvider = { config },
            testDialer = ::dial,
            livenessProbeIntervalMs = 0L,
        )

        init {
            test.backgroundScope.launch { transport.events.collect { frames += it } }
            test.backgroundScope.launch { transport.redialWhileTurnActive.collect { recoveries += it } }
            test.runCurrent()
        }

        private suspend fun dial(dialConfig: IrohConnectConfig): IrohConnectionHandle {
            if (redialsHang && dials.isNotEmpty()) awaitCancellation()
            val dial = ScriptedDial().also { dials += it }
            return transport.attachTestDial(dialConfig, dial).also { handles += it }
        }

        suspend fun connectAndStartTurn(): ScriptedDial {
            transport.connect(config.baseShimUrl, "", "device", "test")
            assertTrue(transport.state.value is ChannelTransportState.Connected)
            assertTrue(transport.send(AGENT, CONVERSATION, "hi", "otid-1", null, false))
            test.runCurrent()
            val dial = dials.single()
            assertEquals(1, dial.inputs, "the turn's input went out on the first dial")
            return dial
        }

        /** The connection drops; the supervisor's backoff elapses and it dials again. */
        fun dropAndRedial() {
            transport.reportConnectionLostForTest(LOST, handles.last())
            test.runCurrent()
            test.advanceTimeBy(REDIAL_BACKOFF_CEILING_MS)
            test.runCurrent()
            assertEquals(2, dials.size, "the supervisor redialled")
            assertTrue(transport.state.value is ChannelTransportState.Connected)
        }

        fun terminalStatuses() = frames.filterIsInstance<ServerFrame.TurnDone>().map { it.status }

        fun errorCodes() = frames.filterIsInstance<ServerFrame.Error>().map { it.code }
    }

    /** One dial's authenticated App Server client: frames the test emits, and what the engine sent. */
    private class ScriptedDial : AppServerClient {
        private val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = inbound
        val runtimeStarts = mutableListOf<AppServerCommand.RuntimeStart>()
        var inputs = 0

        fun emit(frame: AppServerInboundFrame.StreamDelta) {
            check(inbound.tryEmit(receivedFrame(frame)))
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
            runtimeStarts += command
            return AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = true, runtime = RUNTIME)
        }

        override suspend fun input(command: AppServerCommand.Input) {
            inputs += 1
        }

        override suspend fun sync(command: AppServerCommand.Sync) =
            AppServerInboundFrame.SyncResponse(requestId = command.requestId.orEmpty(), runtime = command.runtime, success = true)

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
        const val LOST = "connection_lost"

        /** The supervisor's first redial waits its 500 ms base backoff plus up to 250 ms of jitter. */
        const val REDIAL_BACKOFF_CEILING_MS = 1_000L
        val RUNTIME = AppServerRuntimeScope(AGENT, CONVERSATION)

        fun receivedFrame(frame: AppServerInboundFrame.StreamDelta) = AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = frame,
            raw = buildJsonObject {
                put("type", "stream_delta")
                put("idempotency_key", frame.idempotencyKey)
                put("delta", frame.delta)
            },
        )

        fun assistantDelta(seq: Long) = streamDelta(seq, "evt-assistant-$seq") {
            put("id", "letta-msg-1")
            put("message_type", "assistant_message")
            put("content", "partial reply")
            put("run_id", "run-1")
        }

        fun stopReasonDelta(seq: Long) = streamDelta(seq, "evt-stop-$seq") {
            put("message_type", "stop_reason")
            put("stop_reason", "end_turn")
            put("run_id", "run-1")
        }

        fun streamDelta(seq: Long, key: String, body: JsonObjectBuilder.() -> Unit) = AppServerInboundFrame.StreamDelta(
            runtime = RUNTIME,
            eventSeq = seq,
            emittedAt = "2026-10-15T00:00:${seq.toString().padStart(2, '0')}Z",
            idempotencyKey = key,
            delta = buildJsonObject(body),
        )
    }
}
