package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.reconnect.AppServerNotConnectedException
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.runtime.terminalReasonKind
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.95: an Android Iroh transport keeps ONE turn engine and event router across
 * its dials, over a client that switches from one dial to the next, with today's loss semantics:
 * ending a dial ends every turn it carried (one connection-lost terminal, as the per-dial router's
 * detach did) and the next dial starts from a clean connection (runtime_start again). Virtual time
 * throughout; nothing sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohTransportEngineTest {

    @Test
    fun theEngineAndRouterOutliveTheDialThatBuiltThem() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val engine = host.engine(CLIENT_VERSION)
        val first = host.startDial(DialClient()) { null }
        host.endDial(first)
        host.startDial(DialClient()) { null }

        assertSame(engine, host.engine(CLIENT_VERSION), "one engine per transport, not per dial")
        assertTrue(host.router.isAttached(), "ending a dial must not detach the transport's router")
    }

    @Test
    fun endingADialEndsItsTurnWithOneConnectionLostTerminal() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val dial = DialClient()
        val bound = host.startDial(dial) { null }
        val turn = startTurn(host.engine(CLIENT_VERSION))
        dial.emit(assistantDelta(seq = 1))
        runCurrent()

        host.endDial(bound)
        runCurrent()
        turn.job.join()

        val terminal = turn.singleTerminal()
        assertEquals(RuntimeRunStatus.Failed, terminal.status)
        assertEquals(TurnFailureNotices.CONNECTION_LOST_KIND, terminalReasonKind(terminal.reason))
        assertFalse(host.engine(CLIENT_VERSION).isBusy(AGENT, CONVERSATION), "the lease must be released")
    }

    @Test
    fun aTurnOnTheNextDialStartsItsRuntimeAgainAndCompletesOnThatDialsFrames() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val engine = host.engine(CLIENT_VERSION)
        val firstDial = DialClient()
        val first = host.startDial(firstDial) { null }
        val firstTurn = startTurn(engine)
        host.endDial(first)
        runCurrent()
        firstTurn.job.join()

        val secondDial = DialClient()
        host.startDial(secondDial) { null }
        val secondTurn = startTurn(engine)
        firstDial.emit(stopReasonDelta(seq = 2)) // a late frame of the ended dial reaches no one
        secondDial.emit(assistantDelta(seq = 1))
        secondDial.emit(stopReasonDelta(seq = 2))
        runCurrent()
        advanceTimeBy(AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS + 1)
        runCurrent()
        secondTurn.job.join()

        assertEquals(1, firstDial.runtimeStarts, "the first dial started the runtime once")
        assertEquals(1, secondDial.runtimeStarts, "the next dial must start it again: a dial's runtime scope dies with it")
        assertEquals(1, secondDial.inputs, "the second turn's input goes out on the second dial")
        assertEquals(RuntimeRunStatus.Completed, secondTurn.singleTerminal().status)
    }

    @Test
    fun callsWhileNoDialIsBoundFailAtOnce() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        assertFailsWith<AppServerNotConnectedException> { host.client.runtimeStart(runtimeStart()) }

        val dial = DialClient()
        val bound = host.startDial(dial) { null }
        host.client.runtimeStart(runtimeStart())
        assertEquals(1, dial.runtimeStarts, "a bound dial takes the call")

        host.endDial(bound)
        assertFailsWith<AppServerNotConnectedException> { host.client.runtimeStart(runtimeStart()) }
        assertEquals(1, dial.runtimeStarts, "an ended dial takes no more calls")
    }

    @Test
    fun aDialReplacedBeforeItsCloseRanIsEndedByTheSwitchAndItsLateCloseSparesTheSuccessor() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val engine = host.engine(CLIENT_VERSION)
        val first = host.startDial(DialClient()) { null }
        val firstTurn = startTurn(engine)

        // A config change dials again while the old handle's close is still queued.
        val secondDial = DialClient()
        host.startDial(secondDial) { null }
        runCurrent()
        firstTurn.job.join()
        assertEquals(RuntimeRunStatus.Failed, firstTurn.singleTerminal().status)

        val secondTurn = startTurn(engine)
        host.endDial(first) // the old handle's close finally runs
        runCurrent()
        assertTrue(secondTurn.job.isActive, "a superseded dial's close must not end the successor's turns")

        secondDial.emit(stopReasonDelta(seq = 1))
        runCurrent()
        advanceTimeBy(AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS + 1)
        runCurrent()
        secondTurn.job.join()
        assertEquals(RuntimeRunStatus.Completed, secondTurn.singleTerminal().status)
    }

    @Test
    fun eachDialsFramesAreStampedWithItsOwnGeneration() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val seen = mutableListOf<AppServerReceivedFrame>()
        backgroundScope.launch { host.client.events.collect { seen += it } }
        runCurrent()

        val firstDial = DialClient()
        val first = host.startDial(firstDial) { null }
        firstDial.emit(assistantDelta(seq = 1))
        runCurrent()
        host.endDial(first)
        val secondDial = DialClient()
        host.startDial(secondDial) { null }
        firstDial.emit(assistantDelta(seq = 2))
        secondDial.emit(assistantDelta(seq = 3))
        runCurrent()

        assertEquals(listOf(1L to 1L, 3L to 2L), seen.map { it.frame.eventSeqOf() to it.connectionGeneration })
    }

    @Test
    fun eachDialBindsItsOwnStreamIntegrityLoopAndEndingTheDialStopsIt() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val integrityLoops = mutableListOf<Job>()
        val bindIntegrity = { _: com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter ->
            Job().also { integrityLoops += it }
        }
        val first = host.startDial(DialClient(), bindIntegrity)
        host.endDial(first)
        host.startDial(DialClient(), bindIntegrity)

        assertEquals(2, integrityLoops.size, "one stream-integrity loop per dial")
        assertTrue(integrityLoops[0].isCancelled, "ending a dial stops its loop")
        assertTrue(integrityLoops[1].isActive, "the bound dial's loop runs")
    }

    private class RunningTurn(val drafts: MutableList<RuntimeEventDraft>, val job: Job) {
        fun singleTerminal(): RuntimeEventPayload.RunLifecycleChanged {
            val terminals = drafts.filter { it.isTerminal() }
            assertEquals(1, terminals.size, "exactly one terminal in ${drafts.map { it.payload }}")
            return terminals.single().payload as RuntimeEventPayload.RunLifecycleChanged
        }
    }

    private fun TestScope.startTurn(engine: AppServerTurnEngine): RunningTurn {
        val drafts = mutableListOf<RuntimeEventDraft>()
        val job = launch { engine.runTurn(COMMAND).toList(drafts) }
        runCurrent()
        return RunningTurn(drafts, job)
    }

    /** One dial's App Server client: frames the test emits, and counts of what the engine sent. */
    private class DialClient : AppServerClient {
        private val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = inbound
        var runtimeStarts = 0
        var inputs = 0

        fun emit(frame: AppServerInboundFrame.StreamDelta) {
            check(inbound.tryEmit(receivedFrame(frame)))
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
            runtimeStarts += 1
            return AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = true, runtime = RUNTIME)
        }

        override suspend fun input(command: AppServerCommand.Input) {
            inputs += 1
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("sync unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
        const val CLIENT_VERSION = "test"
        val RUNTIME = AppServerRuntimeScope(AGENT, CONVERSATION)
        val COMMAND = TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh:test"),
            agentId = AgentId(AGENT),
            conversationId = ConversationId(CONVERSATION),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )

        fun runtimeStart() = AppServerCommand.RuntimeStart(requestId = "runtime-start-1", agentId = AGENT, conversationId = CONVERSATION)

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

        fun AppServerInboundFrame.eventSeqOf(): Long? = (this as? AppServerInboundFrame.StreamDelta)?.eventSeq

        fun RuntimeEventDraft.isTerminal(): Boolean =
            (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status in setOf(
                RuntimeRunStatus.Completed,
                RuntimeRunStatus.Failed,
                RuntimeRunStatus.Cancelled,
            )
    }
}
