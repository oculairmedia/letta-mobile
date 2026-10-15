package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.fanout.RuntimeSessionLink
import com.letta.mobile.data.controller.reconnect.AppServerNotConnectedException
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.SESSION_RECONNECT_GRACE_MS
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
 * letta-mobile-by4vm.95 / by4vm.96: an Android Iroh transport keeps ONE turn engine and event router
 * across its dials, over a client that switches from one dial to the next. Ending a dial interrupts
 * the session: a turn keeps its lease and subscription, the next dial re-attaches its runtime and
 * restores the session, and the turn completes on that dial's frames. Only [IrohTransportEngine.endTurns]
 * (the session ending for good) or an expired reconnect grace ends it, with one connection-lost
 * terminal. Virtual time throughout; nothing sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohTransportEngineTest {

    @Test
    fun theEngineAndRouterOutliveTheDialThatBuiltThem() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val engine = host.engine(CLIENT_VERSION)
        val first = host.dial(DialClient())
        host.endDial(first, LOST)
        host.dial(DialClient())

        assertSame(engine, host.engine(CLIENT_VERSION), "one engine per transport, not per dial")
        assertTrue(host.router.isAttached(), "ending a dial must not detach the transport's router")
    }

    @Test
    fun aTurnInterruptedByARedialReattachesItsRuntimeAndCompletesOnTheNextDial() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val firstDial = DialClient()
        val first = host.dial(firstDial)
        val turn = startTurn(host.engine(CLIENT_VERSION))
        firstDial.emit(assistantDelta(seq = 1))
        runCurrent()

        host.endDial(first, LOST)
        runCurrent()
        assertTrue(turn.job.isActive, "a redial must not end the turn")
        assertTrue(host.router.sessionLink.value is RuntimeSessionLink.Interrupted)

        val secondDial = DialClient()
        host.dial(secondDial)
        firstDial.emit(stopReasonDelta(seq = 2)) // a late frame of the ended dial reaches no one
        runCurrent()
        assertTrue(turn.job.isActive, "the dead dial's frame must not end the turn")
        secondDial.emit(stopReasonDelta(seq = 3))
        runCurrent()
        advanceTimeBy(AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS + 1)
        runCurrent()
        turn.job.join()

        assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal().status)
        assertTrue(host.router.sessionLink.value is RuntimeSessionLink.Linked, "the next dial restores the session")
        val reattach = secondDial.runtimeStartCommands.single()
        assertEquals(AGENT to CONVERSATION, reattach.agentId to reattach.conversationId)
        assertEquals(true, reattach.recoverApprovals, "the reattach replays approvals pending on the dead dial")
        assertEquals(true, reattach.forceDeviceStatus)
        assertEquals("letta-mobile-android-iroh", reattach.clientInfo?.name)
        assertEquals(1, secondDial.runtimeStarts, "the surviving turn only reattaches; it does not start again")
    }

    @Test
    fun aSessionNotResumedWithinTheGraceFailsTheTurnExactlyOnce() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val first = host.dial(DialClient())
        val turn = startTurn(host.engine(CLIENT_VERSION))

        host.endDial(first, LOST)
        advanceTimeBy(SESSION_RECONNECT_GRACE_MS - 1)
        runCurrent()
        assertTrue(turn.job.isActive, "the turn waits out the reconnect grace")
        advanceTimeBy(2)
        runCurrent()
        turn.job.join()

        val terminal = turn.singleTerminal()
        assertEquals(RuntimeRunStatus.Failed, terminal.status)
        assertEquals(TurnFailureNotices.CONNECTION_LOST_KIND, terminalReasonKind(terminal.reason))
        assertFalse(host.engine(CLIENT_VERSION).isBusy(AGENT, CONVERSATION), "the lease must be released")
    }

    @Test
    fun endingTheSessionEndsItsTurnAtOnceWithOneConnectionLostTerminal() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val first = host.dial(DialClient())
        val turn = startTurn(host.engine(CLIENT_VERSION))

        host.endTurns()
        host.endDial(first, "config_changed")
        runCurrent()
        turn.job.join()

        val terminal = turn.singleTerminal()
        assertEquals(RuntimeRunStatus.Failed, terminal.status)
        assertEquals(TurnFailureNotices.CONNECTION_LOST_KIND, terminalReasonKind(terminal.reason))
    }

    @Test
    fun aTurnOnTheNextDialStartsItsRuntimeAgainAndCompletesOnThatDialsFrames() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val engine = host.engine(CLIENT_VERSION)
        val firstDial = DialClient()
        val first = host.dial(firstDial)
        val firstTurn = startTurn(engine)
        host.endTurns()
        host.endDial(first, LOST)
        runCurrent()
        firstTurn.job.join()

        val secondDial = DialClient()
        host.dial(secondDial)
        val secondTurn = startTurn(engine)
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
        val bound = host.dial(dial)
        host.client.runtimeStart(runtimeStart())
        assertEquals(1, dial.runtimeStarts, "a bound dial takes the call")

        host.endDial(bound, LOST)
        assertFailsWith<AppServerNotConnectedException> { host.client.runtimeStart(runtimeStart()) }
        assertEquals(1, dial.runtimeStarts, "an ended dial takes no more calls")
    }

    @Test
    fun aDialReplacedBeforeItsCloseRanCarriesItsTurnAndItsLateCloseSparesTheSuccessor() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val engine = host.engine(CLIENT_VERSION)
        val first = host.dial(DialClient())
        val turn = startTurn(engine)

        // A redial binds again while the old handle's close is still queued.
        val secondDial = DialClient()
        host.dial(secondDial)
        host.endDial(first, LOST) // the old handle's close finally runs
        runCurrent()
        assertTrue(turn.job.isActive, "the replaced dial's turn is carried to its successor")
        assertTrue(host.router.sessionLink.value is RuntimeSessionLink.Linked, "a superseded dial's close must not interrupt the successor")

        secondDial.emit(stopReasonDelta(seq = 1))
        runCurrent()
        advanceTimeBy(AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS + 1)
        runCurrent()
        turn.job.join()
        assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal().status)
    }

    @Test
    fun eachDialsFramesAreStampedWithItsOwnGeneration() = runTest {
        val host = IrohTransportEngine(backgroundScope, externalToolRegistry = null)
        val seen = mutableListOf<AppServerReceivedFrame>()
        backgroundScope.launch { host.client.events.collect { seen += it } }
        runCurrent()

        val firstDial = DialClient()
        val first = host.dial(firstDial)
        firstDial.emit(assistantDelta(seq = 1))
        runCurrent()
        host.endDial(first, LOST)
        val secondDial = DialClient()
        host.dial(secondDial)
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
        host.endDial(first, LOST)
        host.startDial(DialClient(), bindIntegrity)

        assertEquals(2, integrityLoops.size, "one stream-integrity loop per dial")
        assertTrue(integrityLoops[0].isCancelled, "ending a dial stops its loop")
        assertTrue(integrityLoops[1].isActive, "the bound dial's loop runs")
    }

    /** What [IrohDialer.attachDial] does with an authenticated dial. */
    private suspend fun IrohTransportEngine.dial(client: DialClient): IrohBoundDial =
        startDial(client) { null }.also { resumeSession() }

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
        val runtimeStartCommands = mutableListOf<AppServerCommand.RuntimeStart>()
        val runtimeStarts: Int get() = runtimeStartCommands.size
        var inputs = 0

        fun emit(frame: AppServerInboundFrame.StreamDelta) {
            check(inbound.tryEmit(receivedFrame(frame)))
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
            runtimeStartCommands += command
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
        const val LOST = "connection_lost"
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
