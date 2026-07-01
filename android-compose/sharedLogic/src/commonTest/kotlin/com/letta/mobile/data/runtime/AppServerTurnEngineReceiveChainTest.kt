package com.letta.mobile.data.runtime

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
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
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject

/**
 * Hermetic CI guard for the Iroh live-receive chain.
 *
 * The device bug was: the wrapper wrote assistant stream_delta frames but the
 * app produced no AssistantMessage draft. The root cause turned out to be send
 * routing (Iroh vs REST), but the receive path itself must stay correct too.
 *
 * This drives the EXACT receive-side unit the Iroh transport uses
 * ([AppServerTurnEngine] over an [AppServerClient]) with a fake client replaying
 * a real App Server assistant `stream_delta` + terminal `stop_reason`, and
 * asserts it produces a [RuntimeEventPayload.RemoteStreamFrame] (the assistant
 * text `IrohChannelTransport.emitDraft` turns into AssistantMessage) followed by
 * a Completed lifecycle (the TurnDone). No device, no QUIC, no backend — this
 * runs headless in CI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineReceiveChainTest {

    @Test
    fun assistantStreamDeltaThenStopReasonYieldsRemoteStreamFrameThenCompleted() = runTest {
        val client = FakeReceiveClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "runtime-start-1" },
        )

        engine.runTurn(command).test {
            // 1. Started lifecycle bookend.
            val started = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Started, started.status)

            // The engine has sent input; now the server streams an assistant reply.
            client.emit(assistantStreamDelta(messageId = "letta-msg-1", content = ASSISTANT_TEXT))

            // 2. Assistant reply arrives as a RemoteStreamFrame (→ AssistantMessage).
            val streamDraft = awaitItem()
            val remote = assertIs<RuntimeEventPayload.RemoteStreamFrame>(streamDraft.payload)
            assertEquals("letta-msg-1", remote.messageId)
            assertEquals("assistant_message", remote.messageType)
            assertTrue(
                remote.body.contains(ASSISTANT_TEXT),
                "RemoteStreamFrame body must carry the assistant text; got ${remote.body.take(200)}",
            )

            // 3. Terminal stop_reason completes the turn (→ TurnDone).
            client.emit(stopReasonDelta(runId = "run-1"))
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun frameForForeignRuntimeIsNotFoldedIntoThisTurn() = runTest {
        val client = FakeReceiveClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "runtime-start-1" },
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)

            // A frame scoped to a DIFFERENT runtime must be ignored (cross-conversation guard).
            client.emit(
                assistantStreamDelta(
                    messageId = "foreign-1",
                    content = "not for us",
                    runtime = AppServerRuntimeScope("agent-other", "conv-other"),
                ),
            )
            // The correct turn's reply still arrives and is the next item.
            client.emit(assistantStreamDelta(messageId = "letta-msg-1", content = ASSISTANT_TEXT))
            val remote = assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload)
            assertEquals("letta-msg-1", remote.messageId)

            client.emit(stopReasonDelta(runId = "run-1"))
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            awaitComplete()
        }
    }

    companion object {
        const val ASSISTANT_TEXT = "Ack: live iroh receive chain works."
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh:test"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )
    }
}

private class FakeReceiveClient : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 16)

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

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        error("sync unused")

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        error("abort unused")

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

    fun emit(frame: AppServerInboundFrame.StreamDelta) {
        // Production raw envelope carries the full stream_delta JSON, including
        // the delta object. The mapper copies raw.toString() into
        // RemoteStreamFrame.body, and IrohChannelTransport.extractAssistantText
        // later unwraps delta.content from it — so the raw MUST embed the delta.
        (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = buildJsonObject {
                    put("type", "stream_delta")
                    put("idempotency_key", frame.idempotencyKey)
                    put("delta", frame.delta.jsonObject)
                },
            ),
        )
    }
}

private fun assistantStreamDelta(
    messageId: String,
    content: String,
    runtime: AppServerRuntimeScope = AppServerTurnEngineReceiveChainTest.runtime,
): AppServerInboundFrame.StreamDelta =
    AppServerInboundFrame.StreamDelta(
        runtime = runtime,
        eventSeq = 1,
        emittedAt = "2026-07-01T00:00:00Z",
        idempotencyKey = "evt-$messageId",
        delta = buildJsonObject {
            put("id", messageId)
            put("message_type", "assistant_message")
            put("content", content)
            put("run_id", "run-1")
        },
    )

private fun stopReasonDelta(
    runId: String,
    runtime: AppServerRuntimeScope = AppServerTurnEngineReceiveChainTest.runtime,
): AppServerInboundFrame.StreamDelta =
    AppServerInboundFrame.StreamDelta(
        runtime = runtime,
        eventSeq = 2,
        emittedAt = "2026-07-01T00:00:01Z",
        idempotencyKey = "evt-stop",
        delta = buildJsonObject {
            put("message_type", "stop_reason")
            put("run_id", runId)
        },
    )
