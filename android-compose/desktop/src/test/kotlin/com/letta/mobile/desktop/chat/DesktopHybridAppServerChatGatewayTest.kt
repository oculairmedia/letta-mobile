package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Proves finding 1 (controller-routed send/stream): [DesktopHybridAppServerChatGateway]
 * decodes the outbound [MessageCreateRequest] into a [TurnCommand] and drives it
 * through a [TurnEngine], and projects the resulting [RuntimeEventDraft]s /
 * stream_delta frames through the SAME mappers the Android iroh path uses
 * (RuntimeEventServerFrameMapper + WsFrameMapper).
 */
class DesktopHybridAppServerChatGatewayTest {

    private fun gateway(
        turnEngine: FakeTurnEngine,
        client: FakeAppServerClient = FakeAppServerClient(),
        transportResources: DesktopTransportResources? = null,
        heartbeatIntervalMs: Long = 60_000L,
    ): DesktopHybridAppServerChatGateway =
        DesktopHybridAppServerChatGateway(
            turnEngine = turnEngine,
            client = client,
            httpGateway = DesktopLettaHttpChatGateway(
                config = LettaConfig(id = "t", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "http://unused.invalid"),
            ),
            transportResources = transportResources,
            heartbeatIntervalMs = heartbeatIntervalMs,
            agentIdResolver = { "agent-1" },
        )

    private fun userMessageRequest(text: String, otid: String?, contentParts: JsonArray? = null): MessageCreateRequest {
        val content = contentParts ?: kotlinx.serialization.json.JsonPrimitive(text)
        val element = Json.encodeToJsonElement(
            MessageCreate.serializer(),
            MessageCreate(role = "user", content = content, otid = otid),
        )
        return MessageCreateRequest(messages = listOf(element))
    }

    // Codex-flagged regression: an OTHER client's turn produces client_tool_start
    // / client_tool_end stream_delta frames (not tool_call_message/tool_return_message
    // — those only appear on the initiator's own websocket-shaped frames). The
    // passive observer must route these through AppServerRuntimeEventMapper
    // (same as IrohChannelTransport.ingestObserverFrame) so they become
    // ToolCallObserved/ToolReturnObserved drafts, not dropped RemoteStreamFrames.

    /**
     * Fixture for a single `stream_delta` wire envelope. Consolidates what
     * used to be several multi-String-param envelope builders (one per
     * message_type) into one spec object + [streamDeltaEnvelope] builder, so
     * fixture construction doesn't push the file's primitive/string
     * function-argument ratio over the CodeScene "Primitive Obsession" /
     * "String Heavy Function Arguments" thresholds.
     */
    private data class DeltaSpec(
        val messageType: String,
        val agentId: String = "agent-1",
        val conversationId: String = "conv-1",
        val messageId: String? = null,
        val runId: String? = null,
        val content: String? = null,
        val toolCallId: String? = null,
        val toolName: String? = null,
        val status: String? = null,
        val output: String? = null,
    )

    private fun streamDeltaEnvelope(spec: DeltaSpec): String {
        val delta = buildJsonObject {
            put("message_type", spec.messageType)
            spec.messageId?.let { put("id", it) }
            spec.runId?.let { put("run_id", it) }
            spec.toolCallId?.let { put("tool_call_id", it) }
            spec.toolName?.let { put("tool_name", it) }
            spec.status?.let { put("status", it) }
            spec.output?.let { put("output", it) }
            spec.content?.let {
                val key = when (spec.messageType) {
                    "reasoning_message" -> "reasoning"
                    "client_tool_start" -> "input"
                    else -> "content"
                }
                put(key, it)
            }
        }
        val runtime = buildJsonObject {
            put("agent_id", spec.agentId)
            put("conversation_id", spec.conversationId)
        }
        val envelope = buildJsonObject {
            put("type", "stream_delta")
            put("runtime", runtime)
            put("event_seq", 1)
            put("emitted_at", "2026-01-01T00:00:00Z")
            put("idempotency_key", "idem-1")
            put("delta", delta)
        }
        return envelope.toString()
    }

    // Fixed fixtures (no test call site varies these) — plain vals rather than
    // wrapper functions, so the file doesn't just relocate the retired
    // multi-String-param builders one level down.
    private val assistantDelta =
        DeltaSpec(messageType = "assistant_message", messageId = "cm-stream-a1", runId = "run-1", content = "Hello")
    private val reasoningDelta =
        DeltaSpec(messageType = "reasoning_message", messageId = "cm-reason-a1", runId = "run-1", content = "thinking")
    private val clientToolStartDelta = DeltaSpec(
        messageType = "client_tool_start",
        toolCallId = "tc-42",
        toolName = "search",
        runId = "run-1",
        content = "{\"q\":\"hi\"}",
    )
    private val clientToolEndDelta = DeltaSpec(
        messageType = "client_tool_end",
        toolCallId = "tc-42",
        runId = "run-1",
        status = "success",
        output = "ok",
    )

    private fun draft(payload: RuntimeEventPayload): RuntimeEventDraft = RuntimeEventDraft(
        backendId = BackendId("desktop-app-server"),
        runtimeId = RuntimeId("desktop-app-server:conv-1"),
        source = RuntimeEventSource.RemoteLetta,
        payload = payload,
    )

    @Test
    fun gateway_isChatGatewayExtras_viaHttpDelegate() {
        assertIs<ChatGatewayExtras>(gateway(FakeTurnEngine { flowOf() }))
    }

    @Test
    fun send_buildsTurnCommandFromRequest() = runTest {
        val turnEngine = FakeTurnEngine { flowOf() }
        val gw = gateway(turnEngine)

        gw.sendConversationMessage("conv-1", userMessageRequest("hi", otid = "otid-1")).toList()

        val command = turnEngine.recordedTurnCommand ?: error("runTurn was not invoked")
        assertEquals("agent-1", command.agentId.value)
        assertEquals("conv-1", command.conversationId.value)
        val input = assertIs<TurnInput.UserMessage>(command.input)
        assertEquals("hi", input.text)
        assertEquals("otid-1", input.localMessageId)
        assertEquals(null, input.contentPartsJson)
    }

    @Test
    fun send_preservesContentPartsJson() = runTest {
        val turnEngine = FakeTurnEngine { flowOf() }
        val gw = gateway(turnEngine)
        val parts = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "hi") })
        }

        gw.sendConversationMessage("conv-1", userMessageRequest("hi", otid = "otid-1", contentParts = parts)).toList()

        val input = assertIs<TurnInput.UserMessage>(turnEngine.recordedTurnCommand!!.input)
        assertEquals(parts.toString(), input.contentPartsJson)
    }

    @Test
    fun send_mapsDraftsToLettaMessages() = runTest {
        val drafts = listOf(
            draft(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Started)),
            draft(RuntimeEventPayload.RemoteStreamFrame(frameId = "f1", body = streamDeltaEnvelope(assistantDelta))),
            draft(RuntimeEventPayload.RemoteStreamFrame(frameId = "f2", body = streamDeltaEnvelope(reasoningDelta))),
            draft(
                RuntimeEventPayload.ToolCallObserved(
                    toolCallId = ToolCallId("tc1"),
                    toolName = ToolName("search"),
                    argumentsJson = "{}",
                ),
            ),
            draft(
                RuntimeEventPayload.ToolReturnObserved(
                    toolCallId = ToolCallId("tc1"),
                    status = ToolExecutionStatus.Succeeded,
                    body = "ok",
                ),
            ),
            draft(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed)),
        )
        val turnEngine = FakeTurnEngine { flowOf(*drafts.toTypedArray()) }
        val gw = gateway(turnEngine)

        val messages = gw.sendConversationMessage("conv-1", userMessageRequest("hi", otid = "otid-1")).toList()

        val assistant = assertIs<AssistantMessage>(messages.single { it is AssistantMessage })
        assertEquals("cm-stream-a1", assistant.id)
        assertEquals("Hello", assistant.content)
        assertEquals("run-1", assistant.runId)
        assertEquals("iroh-assistant-cm-stream-a1", assistant.otid)

        assertTrue(messages.any { it is ReasoningMessage }, "reasoning message present: $messages")

        val toolCall = assertIs<ToolCallMessage>(messages.single { it is ToolCallMessage })
        assertEquals("toolcall-tc1", toolCall.id)

        val toolReturn = assertIs<ToolReturnMessage>(messages.single { it is ToolReturnMessage })
        assertEquals("toolreturn-tc1", toolReturn.id)
        assertEquals("success", toolReturn.status)
    }

    @Test
    fun send_failedLifecycleThrows() = runTest {
        val turnEngine = FakeTurnEngine {
            flowOf(draft(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Failed, reason = "boom")))
        }
        val gw = gateway(turnEngine)

        val error = assertFailsWith<TimelineTransportHttpException> {
            gw.sendConversationMessage("conv-1", userMessageRequest("hi", otid = "otid-1")).toList()
        }
        assertEquals(502, error.code)
        assertTrue(error.message.orEmpty().contains("boom"), "message was: ${error.message}")
    }

    @Test
    fun stream_emitsMessagesForOwnConversationOnly() = runTest {
        val turnEngine = FakeTurnEngine { flowOf() }
        val client = FakeAppServerClient()
        val gw = gateway(turnEngine, client = client)

        val results = mutableListOf<TimelineStreamFrame>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            gw.streamConversation("conv-1").take(1).collect { results += it }
        }
        // merge() fans the frames/heartbeats sources out onto separately
        // scheduled child coroutines rather than subscribing inline under the
        // UNDISPATCHED start above — drain the scheduler so the collector is
        // actually subscribed to client.events before emitting, otherwise the
        // (replay=0) SharedFlow drops these emissions on the floor.
        runCurrent()

        client.eventsFlow.emit(streamDeltaFrame(conversationId = "conv-2", envelope = streamDeltaEnvelope(assistantDelta)))
        client.eventsFlow.emit(streamDeltaFrame(conversationId = "conv-1", envelope = streamDeltaEnvelope(assistantDelta)))
        job.join()

        val message = assertIs<TimelineStreamFrame.Message>(results.single())
        val assistant = assertIs<AssistantMessage>(message.message)
        assertEquals("Hello", assistant.content)
    }

    @Test
    fun stream_clientToolFramesProduceToolCards() = runTest {
        val turnEngine = FakeTurnEngine { flowOf() }
        val client = FakeAppServerClient()
        val gw = gateway(turnEngine, client = client)

        val results = mutableListOf<TimelineStreamFrame>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            gw.streamConversation("conv-1").take(2).collect { results += it }
        }
        runCurrent()

        client.eventsFlow.emit(streamDeltaFrame(conversationId = "conv-1", envelope = streamDeltaEnvelope(clientToolStartDelta)))
        client.eventsFlow.emit(streamDeltaFrame(conversationId = "conv-1", envelope = streamDeltaEnvelope(clientToolEndDelta)))
        job.join()

        val toolCallMessage = assertIs<TimelineStreamFrame.Message>(results[0])
        val toolCall = assertIs<ToolCallMessage>(toolCallMessage.message)
        assertEquals("toolcall-tc-42", toolCall.id)

        val toolReturnMessage = assertIs<TimelineStreamFrame.Message>(results[1])
        val toolReturn = assertIs<ToolReturnMessage>(toolReturnMessage.message)
        assertEquals("toolreturn-tc-42", toolReturn.id)
        assertEquals("success", toolReturn.status)
    }

    @Test
    fun stream_synthesizesHeartbeats() = runTest {
        val turnEngine = FakeTurnEngine { flowOf() }
        val gw = gateway(turnEngine, heartbeatIntervalMs = 10L)

        val first = gw.streamConversation("conv-1").take(1).toList().single()

        assertIs<TimelineStreamFrame.Heartbeat>(first)
    }

    @Test
    fun stream_heartbeatsStopAfterDisconnect() = runTest {
        val client = FakeAppServerClient()
        val gw = gateway(FakeTurnEngine { flowOf() }, client = client, heartbeatIntervalMs = 10L)
        val frames = mutableListOf<TimelineStreamFrame>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { gw.streamConversation("conv-1").collect { frames += it } }
        runCurrent()
        advanceTimeBy(35L)
        val beforeDrop = frames.size
        assertTrue(beforeDrop >= 1)
        client.connected.value = false
        advanceTimeBy(200L)
        assertEquals(beforeDrop, frames.size)
        job.cancel()
    }

    @Test
    fun close_closesTransportResources() = runTest {
        val calls = mutableListOf<String>()
        val resources = DesktopTransportResources(
            teardownSteps = listOf(
                { calls += "transport" },
                { calls += "shutdown" },
                { calls += "close" },
            ),
        )
        val gw = gateway(FakeTurnEngine { flowOf() }, transportResources = resources)

        gw.close()

        assertEquals(listOf("transport", "shutdown", "close"), calls)
    }

    @Test
    fun close_withoutTransportResources_doesNotThrow() {
        val gw = gateway(FakeTurnEngine { flowOf() }, transportResources = null)

        gw.close()
    }

    private fun streamDeltaFrame(conversationId: String, envelope: String): AppServerReceivedFrame {
        val body = envelope.replace(""""conversation_id":"conv-1"""", """"conversation_id":"$conversationId"""")
        return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
    }

    private class FakeTurnEngine(
        private val runTurnFlow: (TurnCommand) -> Flow<RuntimeEventDraft>,
    ) : TurnEngine {
        var recordedTurnCommand: TurnCommand? = null
            private set

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> {
            recordedTurnCommand = command
            return runTurnFlow(command)
        }
    }

    private class FakeAppServerClient : AppServerClient {
        val eventsFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
        override val events: Flow<AppServerReceivedFrame> = eventsFlow

        val connected = MutableStateFlow(true)
        override val isConnected: Flow<Boolean> get() = connected

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            TODO("not needed for hybrid gateway tests")

        override suspend fun input(command: AppServerCommand.Input) = TODO("not needed for hybrid gateway tests")

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            TODO("not needed for hybrid gateway tests")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            TODO("not needed for hybrid gateway tests")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            TODO("not needed for hybrid gateway tests")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
            TODO("not needed for hybrid gateway tests")
    }
}
