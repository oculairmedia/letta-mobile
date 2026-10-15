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
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-by4vm.55: a lease carried into the next connection generation is carried BEFORE
 * the generation provider advances. In that window the lease is ahead of the provider, which must
 * not read as superseded; once the provider catches up, a lease that was not carried is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnLeaseGenerationCarryTest {

    @Test
    fun aLeaseCarriedAheadOfTheProviderKeepsProcessingFrames() = runTest {
        var generation = 0L
        val client = Client()
        val router = AppServerRuntimeEventRouter(connectionGenerationProvider = { generation })
            .also { it.attach(backgroundScope, client.events) }
        val engine = AppServerTurnEngine(
            client = client,
            eventRouter = router,
            connectionGenerationProvider = { generation },
            nowMs = { testScheduler.currentTime },
        )
        val drafts = mutableListOf<RuntimeEventDraft>()
        val job = launch { engine.runTurn(COMMAND).toList(drafts) }
        runCurrent()

        engine.carryActiveLeasesInto(1)
        // The provider has not advanced yet: an unstamped frame in this window continues the turn.
        client.emit(delta(seq = 1, "evt-1") { put("message_type", "assistant_message"); put("content", "a"); put("run_id", "run-1") })
        runCurrent()
        assertTrue(job.isActive, "a carried lease is not superseded while the provider catches up")

        generation = 1
        client.emit(delta(seq = 2, "evt-2") { put("message_type", "stop_reason"); put("stop_reason", "end_turn"); put("run_id", "run-1") })
        advanceUntilIdle()
        job.join()

        val terminal = drafts.mapNotNull { it.payload as? RuntimeEventPayload.RunLifecycleChanged }.last()
        assertEquals(RuntimeRunStatus.Completed, terminal.status)
        assertFalse(engine.isBusy(AGENT, CONVERSATION), "the carried lease is released by token")
    }

    private class Client : AppServerClient {
        private val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
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
        val RUNTIME = AppServerRuntimeScope(AGENT, CONVERSATION)
        val COMMAND = TurnCommand(
            backendId = BackendId("app-server"),
            runtimeId = RuntimeId("app-server:test"),
            agentId = AgentId(AGENT),
            conversationId = ConversationId(CONVERSATION),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )

        fun delta(seq: Long, key: String, body: JsonObjectBuilder.() -> Unit) = AppServerInboundFrame.StreamDelta(
            runtime = RUNTIME,
            eventSeq = seq,
            emittedAt = "2026-10-15T00:00:0${seq}Z",
            idempotencyKey = key,
            delta = buildJsonObject(body),
        )
    }
}
