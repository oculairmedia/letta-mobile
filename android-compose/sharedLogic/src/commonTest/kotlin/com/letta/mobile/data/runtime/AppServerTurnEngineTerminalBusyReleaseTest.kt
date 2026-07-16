package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-c4igq.1: the App Server turn-busy lock must release atomically on
 * EVERY terminal outcome — not just clean completion. A mid-stream stream-death
 * (the events flow throwing) previously wedged the engine busy, so every later
 * Iroh send bounced with "a turn is already active" until a manual restart.
 *
 * Invariant: after a turn ends by stream-death / error, [AppServerTurnEngine.isBusy]
 * returns to false and the immediate next send is accepted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineTerminalBusyReleaseTest {

    @Test
    fun streamDeathReleasesBusyAndAllowsNextSend() = runTest {
        val client = StreamDeathClient()

        val engine = AppServerTurnEngine(client = client)

        val first = launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        assertTrue(engine.isBusy, "engine must be busy once the turn is running")

        // The QUIC/App Server stream dies mid-turn (no terminal lifecycle frame).
        client.killStream(RuntimeException("iroh stream reset mid-turn"))
        advanceUntilIdle()
        first.join()

        assertFalse(
            engine.isBusy,
            "stream-death terminal must release the busy lock; otherwise every later send is wedged until a manual restart",
        )

        // The immediate next send must be accepted (not bounced as "already active").
        var secondAccepted = false
        val second = launch {
            runCatching { engine.runTurn(secondCommand).collect { secondAccepted = true } }
        }
        runCurrent()
        assertTrue(secondAccepted, "the next send after a stream-death terminal must be accepted, not busy-rejected")
        second.cancel()
    }

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hey"),
        )
        val secondCommand = command.copy(
            input = TurnInput.UserMessage(localMessageId = "local-2", text = "second"),
        )
    }

    private class StreamDeathClient : AppServerClient {
        private val backing = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        private val death = kotlinx.atomicfu.atomic<Throwable?>(null)

        override val events: Flow<AppServerReceivedFrame> = flow {
            backing.collect { frame ->
                death.value?.let { throw it }
                emit(frame)
            }
        }

        fun killStream(cause: Throwable) {
            death.value = cause
            // Nudge the collector so the death is observed.
            backing.tryEmit(nudge())
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) = Unit
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("sync unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse = error("abort unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse = error("adminRpc unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        private fun nudge(): AppServerReceivedFrame {
            val frame = AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = 99,
                emittedAt = "2026-07-16T00:00:00Z",
                idempotencyKey = "nudge",
                delta = buildJsonObject { put("message_type", "assistant_message"); put("run_id", "run-1") },
            )
            return AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = buildJsonObject { put("type", "stream_delta"); put("idempotency_key", "nudge"); put("delta", frame.delta) },
            )
        }
    }
}
