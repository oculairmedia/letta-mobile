package com.letta.mobile.data.runtime

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * letta-mobile-4vtng: the desktop on the Iroh route runs its own [AppServerTurnEngine] over the
 * host's relayed `stream_delta` frames. The host's turn processor has already stamped them: each
 * assistant frame carries the FULL text so far plus `logical_message_id` / `turn_id` /
 * `text_seq`. The desktop engine must forward those frames as they are. Re-stamping them as App
 * Server increments appended every snapshot onto the last one, the owner's
 * `"SureSure, this is … checkSure, this is …"` row.
 */
class AppServerTurnEngineStampedRelayTest {

    @Test
    fun hostStampedCumulativeFramesLeaveTheEngineUnchanged() = runTest {
        val client = RelayClient()
        val engine = AppServerTurnEngine(client = client, requestIdFactory = { "runtime-start-1" })
        val snapshots = listOf("Sure", "Sure, this is a medium-length reply.", REPLY)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            snapshots.forEachIndexed { index, text -> client.emit(hostAssistantFrame(text, textSeq = index + 1)) }
            val relayed = snapshots.map { assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload) }
                .map { AppServerProtocol.json.parseToJsonElement(it.body).jsonObject.getValue("delta").jsonObject }

            assertEquals(snapshots, relayed.map { it.str("content") }, "a stamped snapshot is never appended")
            assertEquals(listOf(1, 2, 3), relayed.map { it["text_seq"]?.jsonPrimitive?.intOrNull })
            assertEquals(setOf(HOST_LOGICAL_ID), relayed.map { it.str("logical_message_id") }.toSet(), "the host's id is kept")
            assertEquals(setOf(HOST_TURN_ID), relayed.map { it.str("turn_id") }.toSet())

            client.emit(stopReasonFrame())
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            assertEquals(RuntimeRunStatus.Completed, assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload).status)
            awaitComplete()
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val HOST_LOGICAL_ID = "ui-msg-9184701"
        const val HOST_TURN_ID = "client-turn-1"
        const val REPLY = "Sure, this is a medium-length reply. A few paragraphs in, with a clear answer, " +
            "then a couple of supporting points, and a short close."

        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh:test"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )

        fun hostAssistantFrame(text: String, textSeq: Int): AppServerReceivedFrame = received(
            key = "iroh-delta-$textSeq",
            eventSeq = textSeq.toLong(),
            delta = buildJsonObject {
                put("message_type", "assistant_message")
                put("id", HOST_LOGICAL_ID)
                put("run_id", "run-1")
                put("content", text)
                put("text_seq", textSeq)
                put("logical_message_id", HOST_LOGICAL_ID)
                put("turn_id", HOST_TURN_ID)
            },
        )

        fun stopReasonFrame(): AppServerReceivedFrame = received(
            key = "iroh-delta-stop",
            eventSeq = 99,
            delta = buildJsonObject {
                put("message_type", "stop_reason")
                put("run_id", "run-1")
                put("stop_reason", "end_turn")
            },
        )

        fun received(key: String, eventSeq: Long, delta: JsonObject) = AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = eventSeq,
                emittedAt = "2026-10-03T19:35:00Z",
                idempotencyKey = key,
                delta = delta,
            ),
            raw = buildJsonObject {
                put("type", "stream_delta")
                put("event_seq", eventSeq)
                put("idempotency_key", key)
                put("delta", delta)
            },
        )
    }

    private class RelayClient : AppServerClient {
        private val frames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
        override val events: Flow<AppServerReceivedFrame> = frames

        fun emit(frame: AppServerReceivedFrame) {
            check(frames.tryEmit(frame))
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

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }
}
