package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.RuntimeEventFanout
import com.letta.mobile.data.model.AgentId
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-by4vm.9: a turn whose App Server session is interrupted keeps its lease and its
 * subscription for [SESSION_RECONNECT_GRACE_MS]. Restored in time, the successor socket's frames
 * finish it normally; not restored, it fails exactly once. A subscriber overflow (by4vm.4) is not
 * an interruption and still fails at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineReconnectGraceTest {

    @Test
    fun restoreInsideTheWindowLetsTheTurnCompleteNormally() = runTest {
        val turn = startTurn()
        turn.client.emit(assistantDelta(seq = 1))
        runCurrent()

        turn.router.interruptSession("wifi blip")
        advanceTimeBy(SESSION_RECONNECT_GRACE_MS - 1)
        runCurrent()
        assertTrue(turn.job.isActive, "the turn is reconnecting, not failed")
        assertTrue(turn.drafts.none { it.isTerminal() }, "no terminal while reconnecting")
        assertTrue(turn.engine.isBusy(AGENT, CONVERSATION), "the lease is kept")
        assertEquals(1, turn.fanout.subscriberCountForRuntime(AgentId(AGENT), ConversationId(CONVERSATION)))

        turn.router.restoreSession()
        // The successor socket carries the rest of the run to the same subscriber.
        turn.client.emit(assistantDelta(seq = 2))
        turn.client.emit(stopReasonDelta(seq = 3))
        advanceUntilIdle()
        turn.job.join()

        assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal().status)
        assertEquals(2, turn.drafts.count { it.messageType() == "assistant_message" })
        assertFalse(turn.engine.isBusy(AGENT, CONVERSATION))
    }

    @Test
    fun anExpiredWindowFailsTheTurnExactlyOnce() = runTest {
        val turn = startTurn()
        turn.client.emit(assistantDelta(seq = 1))
        runCurrent()

        turn.router.interruptSession("socket closed")
        advanceTimeBy(SESSION_RECONNECT_GRACE_MS - 1)
        runCurrent()
        assertTrue(turn.drafts.none { it.isTerminal() })

        advanceTimeBy(2)
        advanceUntilIdle()
        turn.job.join()

        val terminal = turn.singleTerminal()
        assertEquals(RuntimeRunStatus.Failed, terminal.status)
        assertEquals(SESSION_LOST_TERMINAL_REASON, terminal.reason)
        assertEquals(TurnFailureNotices.CONNECTION_LOST_KIND, terminalReasonKind(terminal.reason))
        assertFalse(turn.engine.isBusy(AGENT, CONVERSATION), "the lease is released")
        assertEquals(0, turn.fanout.subscriberCountForRuntime(AgentId(AGENT), ConversationId(CONVERSATION)))
    }

    @Test
    fun aSecondInterruptionStartsAFreshWindow() = runTest {
        val turn = startTurn()

        turn.router.interruptSession("first blip")
        advanceTimeBy(SESSION_RECONNECT_GRACE_MS - 1)
        turn.router.restoreSession()
        runCurrent()
        turn.router.interruptSession("second blip")
        advanceTimeBy(SESSION_RECONNECT_GRACE_MS - 1)
        runCurrent()

        assertTrue(turn.job.isActive, "the first window's elapsed time does not count against the second")
        assertTrue(turn.drafts.none { it.isTerminal() })
        turn.router.restoreSession()
        turn.client.emit(stopReasonDelta(seq = 1))
        advanceUntilIdle()
        turn.job.join()
        assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal().status)
    }

    @Test
    fun subscriberOverflowStillFailsAtOnceWithoutAWindow() = runTest {
        val turn = startTurn()

        repeat(RuntimeEventFanout.SUBSCRIBER_BUFFER_CAPACITY + 2) { i ->
            turn.fanout.route(receivedFrame(assistantDelta(seq = i + 1L)))
        }
        runCurrent()

        assertEquals(0L, testScheduler.currentTime, "no grace window was waited out")
        val terminal = turn.singleTerminal()
        assertEquals(RuntimeRunStatus.Failed, terminal.status)
        assertEquals(SUBSCRIBER_OVERFLOW_TERMINAL_REASON, terminal.reason)
        turn.job.join()
        assertFalse(turn.engine.isBusy(AGENT, CONVERSATION))
    }

    @Test
    fun aPermanentDetachDuringAnInterruptionFailsAtOnce() = runTest {
        val turn = startTurn()
        turn.router.interruptSession("socket closed")
        runCurrent()

        turn.router.detach()
        runCurrent()

        assertEquals(0L, testScheduler.currentTime)
        assertEquals(SESSION_LOST_TERMINAL_REASON, turn.singleTerminal().reason)
        turn.job.join()
    }

    private class RunningTurn(
        val client: RouterFedClient,
        val router: AppServerRuntimeEventRouter,
        val fanout: RuntimeEventFanout,
        val engine: AppServerTurnEngine,
        val drafts: MutableList<RuntimeEventDraft>,
        val job: Job,
    ) {
        fun singleTerminal(): RuntimeEventPayload.RunLifecycleChanged {
            val terminals = drafts.filter { it.isTerminal() }
            assertEquals(1, terminals.size, "exactly one terminal in ${drafts.map { it.payload }}")
            return terminals.single().payload as RuntimeEventPayload.RunLifecycleChanged
        }
    }

    private fun TestScope.startTurn(): RunningTurn {
        val client = RouterFedClient()
        val fanout = RuntimeEventFanout()
        val router = AppServerRuntimeEventRouter(fanout = fanout).also { it.attach(backgroundScope, client.events) }
        val engine = AppServerTurnEngine(
            client = client,
            eventRouter = router,
            nowMs = { testScheduler.currentTime },
        )
        val drafts = mutableListOf<RuntimeEventDraft>()
        val job = launch { engine.runTurn(COMMAND).toList(drafts) }
        runCurrent()
        return RunningTurn(client, router, fanout, engine, drafts, job)
    }

    private class RouterFedClient : AppServerClient {
        private val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = inbound

        fun emit(frame: AppServerInboundFrame.StreamDelta) {
            inbound.tryEmit(receivedFrame(frame))
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) =
            AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = true, runtime = RUNTIME)

        override suspend fun input(command: AppServerCommand.Input) = Unit
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
        val RUNTIME = AppServerRuntimeScope(AGENT, CONVERSATION)
        val COMMAND = TurnCommand(
            backendId = BackendId("app-server"),
            runtimeId = RuntimeId("app-server:test"),
            agentId = AgentId(AGENT),
            conversationId = ConversationId(CONVERSATION),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )

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
            put("id", "letta-msg-$seq")
            put("message_type", "assistant_message")
            put("content", "part $seq")
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
            emittedAt = "2026-10-14T00:${(seq / 60).toString().padStart(2, '0')}:${(seq % 60).toString().padStart(2, '0')}Z",
            idempotencyKey = key,
            delta = buildJsonObject(body),
        )

        fun RuntimeEventDraft.isTerminal(): Boolean =
            (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status in setOf(
                RuntimeRunStatus.Completed,
                RuntimeRunStatus.Failed,
                RuntimeRunStatus.Cancelled,
            )

        fun RuntimeEventDraft.messageType(): String? =
            (payload as? RuntimeEventPayload.RemoteStreamFrame)?.messageType
    }
}
