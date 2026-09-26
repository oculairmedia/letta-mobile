package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
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
import kotlin.test.assertNotEquals
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-qygvv.16: a turn whose transport session is lost (the router detaches on connection
 * close, redial or wrapper restart) ends deterministically with exactly one terminal, and the idle
 * watchdog keeps its own behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineSessionLossTest {

    @Test
    fun sessionLossMidTurnEmitsExactlyOneConnectionLostTerminal() = runTest {
        val turn = startTurn()
        turn.client.emit(assistantDelta())
        runCurrent()

        turn.router.detach()
        advanceUntilIdle()
        turn.job.join()

        val terminal = turn.singleTerminal()
        assertEquals(RuntimeRunStatus.Failed, terminal.status)
        assertEquals(TurnFailureNotices.CONNECTION_LOST_KIND, terminalReasonKind(terminal.reason))
        assertFalse(turn.engine.isBusy(AGENT, CONVERSATION), "the lease must be released")
    }

    @Test
    fun terminalJustBeforeSessionLossIsNotDuplicated() = runTest {
        val turn = startTurn()
        turn.client.emit(assistantDelta())
        turn.client.emit(stopReasonDelta())
        runCurrent()
        // The real terminal is in: 10 ms into its settle window the session drops.
        advanceTimeBy(10)

        turn.router.detach()
        advanceUntilIdle()
        turn.job.join()

        assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal().status)
        val stopIndex = turn.drafts.indexOfFirst { it.messageType() == "stop_reason" }
        val terminalIndex = turn.drafts.indexOfFirst { it.isTerminal() }
        assertTrue(stopIndex in 0 until terminalIndex, "the buffered stop_reason is flushed ahead of the terminal")
        assertFalse(turn.engine.isBusy(AGENT, CONVERSATION))
    }

    @Test
    fun idleWatchdogStillFailsASilentTurnWithoutSessionLoss() = runTest {
        val turn = startTurn(idleTimeoutMs = IDLE_TIMEOUT_MS)

        advanceTimeBy(IDLE_TIMEOUT_MS + 1)
        advanceUntilIdle()
        turn.job.join()

        val terminal = turn.singleTerminal()
        assertEquals(RuntimeRunStatus.Failed, terminal.status)
        assertNotEquals(TurnFailureNotices.CONNECTION_LOST_KIND, terminalReasonKind(terminal.reason))
        assertFalse(turn.engine.isBusy(AGENT, CONVERSATION))
    }

    private class RunningTurn(
        val client: RouterFedClient,
        val router: AppServerRuntimeEventRouter,
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

    private fun TestScope.startTurn(idleTimeoutMs: Long = 600_000): RunningTurn {
        val client = RouterFedClient()
        val router = AppServerRuntimeEventRouter().also { it.attach(backgroundScope, client.events) }
        val engine = AppServerTurnEngine(
            client = client,
            eventRouter = router,
            turnIdleTimeoutMs = idleTimeoutMs,
            nowMs = { testScheduler.currentTime },
        )
        val drafts = mutableListOf<RuntimeEventDraft>()
        val job = launch { engine.runTurn(COMMAND).toList(drafts) }
        runCurrent()
        return RunningTurn(client, router, engine, drafts, job)
    }

    private class RouterFedClient : AppServerClient {
        private val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = inbound

        fun emit(frame: AppServerInboundFrame.StreamDelta) {
            inbound.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    frame = frame,
                    raw = buildJsonObject {
                        put("type", "stream_delta")
                        put("idempotency_key", frame.idempotencyKey)
                        put("delta", frame.delta)
                    },
                ),
            )
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
        const val IDLE_TIMEOUT_MS = 1_000L
        val RUNTIME = AppServerRuntimeScope(AGENT, CONVERSATION)
        val COMMAND = TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh:test"),
            agentId = AgentId(AGENT),
            conversationId = ConversationId(CONVERSATION),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )

        fun assistantDelta() = streamDelta(1, "evt-assistant") {
            put("id", "letta-msg-1")
            put("message_type", "assistant_message")
            put("content", "partial reply")
            put("run_id", "run-1")
        }

        fun stopReasonDelta() = streamDelta(2, "evt-stop") {
            put("message_type", "stop_reason")
            put("stop_reason", "end_turn")
            put("run_id", "run-1")
        }

        fun streamDelta(
            seq: Long,
            key: String,
            body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
        ) = AppServerInboundFrame.StreamDelta(
            runtime = RUNTIME,
            eventSeq = seq,
            emittedAt = "2026-09-25T00:00:0${seq}Z",
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

