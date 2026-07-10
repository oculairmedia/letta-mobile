package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
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
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Proves finding 1 (controller-routed send/stream): [DesktopHybridAppServerChatGateway]
 * decodes the outbound [MessageCreateRequest] into a [TurnCommand], seeds an
 * Unrestricted runtime before driving the turn, and projects the resulting
 * [RuntimeEventDraft]s / stream_delta frames through the SAME mappers the
 * Android iroh path uses (RuntimeEventServerFrameMapper + WsFrameMapper).
 */
class DesktopHybridAppServerChatGatewayTest {

    private fun gateway(
        controller: FakeAppServerController,
        client: FakeAppServerClient = FakeAppServerClient(),
        transportResources: DesktopIrohTransportResources? = null,
        heartbeatIntervalMs: Long = 60_000L,
    ): DesktopHybridAppServerChatGateway =
        DesktopHybridAppServerChatGateway(
            controller = controller,
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

    private fun assistantDeltaEnvelope(runId: String = "run-1"): String =
        """{"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":1,"emitted_at":"2026-01-01T00:00:00Z","idempotency_key":"idem-1","delta":{"message_type":"assistant_message","id":"cm-stream-a1","run_id":"$runId","content":"Hello"}}"""

    private fun reasoningDeltaEnvelope(runId: String = "run-1"): String =
        """{"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":2,"emitted_at":"2026-01-01T00:00:01Z","idempotency_key":"idem-2","delta":{"message_type":"reasoning_message","id":"cm-reason-a1","run_id":"$runId","reasoning":"thinking"}}"""

    // Codex-flagged regression: an OTHER client's turn produces client_tool_start
    // / client_tool_end stream_delta frames (not tool_call_message/tool_return_message
    // — those only appear on the initiator's own websocket-shaped frames). The
    // passive observer must route these through AppServerRuntimeEventMapper
    // (same as IrohChannelTransport.ingestObserverFrame) so they become
    // ToolCallObserved/ToolReturnObserved drafts, not dropped RemoteStreamFrames.
    private fun clientToolStartDeltaEnvelope(toolCallId: String = "tc-42", runId: String = "run-1"): String =
        """{"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":3,"emitted_at":"2026-01-01T00:00:02Z","idempotency_key":"idem-3","delta":{"message_type":"client_tool_start","tool_call_id":"$toolCallId","tool_name":"search","run_id":"$runId","input":"{\"q\":\"hi\"}"}}"""

    private fun clientToolEndDeltaEnvelope(toolCallId: String = "tc-42", runId: String = "run-1"): String =
        """{"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":4,"emitted_at":"2026-01-01T00:00:03Z","idempotency_key":"idem-4","delta":{"message_type":"client_tool_end","tool_call_id":"$toolCallId","run_id":"$runId","status":"success","output":"ok"}}"""

    private fun draft(payload: RuntimeEventPayload): RuntimeEventDraft = RuntimeEventDraft(
        backendId = BackendId("desktop-app-server"),
        runtimeId = RuntimeId("desktop-app-server:conv-1"),
        source = RuntimeEventSource.RemoteLetta,
        payload = payload,
    )

    @Test
    fun send_buildsTurnCommandFromRequest() = runTest {
        val controller = FakeAppServerController { flowOf() }
        val gw = gateway(controller)

        gw.sendConversationMessage("conv-1", userMessageRequest("hi", otid = "otid-1")).toList()

        val command = controller.recordedTurnCommand ?: error("runTurn was not invoked")
        assertEquals("agent-1", command.agentId.value)
        assertEquals("conv-1", command.conversationId.value)
        val input = assertIs<TurnInput.UserMessage>(command.input)
        assertEquals("hi", input.text)
        assertEquals("otid-1", input.localMessageId)
        assertEquals(null, input.contentPartsJson)
    }

    @Test
    fun send_preservesContentPartsJson() = runTest {
        val controller = FakeAppServerController { flowOf() }
        val gw = gateway(controller)
        val parts = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "hi") })
        }

        gw.sendConversationMessage("conv-1", userMessageRequest("hi", otid = "otid-1", contentParts = parts)).toList()

        val input = assertIs<TurnInput.UserMessage>(controller.recordedTurnCommand!!.input)
        assertEquals(parts.toString(), input.contentPartsJson)
    }

    @Test
    fun send_seedsUnrestrictedRuntimeBeforeTurn() = runTest {
        val controller = FakeAppServerController { flowOf() }
        val gw = gateway(controller)

        gw.sendConversationMessage("conv-1", userMessageRequest("hi", otid = "otid-1")).toList()

        assertEquals(listOf("startRuntime", "runTurn"), controller.callOrder)
        assertEquals(AppServerPermissionMode.Unrestricted, controller.recordedStartRuntimeMode)
    }

    @Test
    fun send_mapsDraftsToLettaMessages() = runTest {
        val drafts = listOf(
            draft(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Started)),
            draft(RuntimeEventPayload.RemoteStreamFrame(frameId = "f1", body = assistantDeltaEnvelope())),
            draft(RuntimeEventPayload.RemoteStreamFrame(frameId = "f2", body = reasoningDeltaEnvelope())),
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
        val controller = FakeAppServerController { flowOf(*drafts.toTypedArray()) }
        val gw = gateway(controller)

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
        val controller = FakeAppServerController {
            flowOf(draft(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Failed, reason = "boom")))
        }
        val gw = gateway(controller)

        val error = assertFailsWith<TimelineTransportHttpException> {
            gw.sendConversationMessage("conv-1", userMessageRequest("hi", otid = "otid-1")).toList()
        }
        assertEquals(502, error.code)
        assertTrue(error.message.orEmpty().contains("boom"), "message was: ${error.message}")
    }

    @Test
    fun stream_emitsMessagesForOwnConversationOnly() = runTest {
        val controller = FakeAppServerController { flowOf() }
        val client = FakeAppServerClient()
        val gw = gateway(controller, client = client)

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

        client.eventsFlow.emit(streamDeltaFrame(conversationId = "conv-2", envelope = assistantDeltaEnvelope()))
        client.eventsFlow.emit(streamDeltaFrame(conversationId = "conv-1", envelope = assistantDeltaEnvelope()))
        job.join()

        val message = assertIs<TimelineStreamFrame.Message>(results.single())
        val assistant = assertIs<AssistantMessage>(message.message)
        assertEquals("Hello", assistant.content)
    }

    @Test
    fun stream_clientToolFramesProduceToolCards() = runTest {
        val controller = FakeAppServerController { flowOf() }
        val client = FakeAppServerClient()
        val gw = gateway(controller, client = client)

        val results = mutableListOf<TimelineStreamFrame>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            gw.streamConversation("conv-1").take(2).collect { results += it }
        }
        runCurrent()

        client.eventsFlow.emit(streamDeltaFrame(conversationId = "conv-1", envelope = clientToolStartDeltaEnvelope()))
        client.eventsFlow.emit(streamDeltaFrame(conversationId = "conv-1", envelope = clientToolEndDeltaEnvelope()))
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
        val controller = FakeAppServerController { flowOf() }
        val gw = gateway(controller, heartbeatIntervalMs = 10L)

        val first = gw.streamConversation("conv-1").take(1).toList().single()

        assertIs<TimelineStreamFrame.Heartbeat>(first)
    }

    @Test
    fun close_closesTransportResources() = runTest {
        val calls = mutableListOf<String>()
        val resources = DesktopIrohTransportResources(
            closeTransport = { calls += "transport" },
            shutdownEndpoint = { calls += "shutdown" },
            closeEndpoint = { calls += "close" },
        )
        val gw = gateway(FakeAppServerController { flowOf() }, transportResources = resources)

        gw.close()

        assertEquals(listOf("transport", "shutdown", "close"), calls)
    }

    @Test
    fun close_withoutTransportResources_doesNotThrow() {
        val gw = gateway(FakeAppServerController { flowOf() }, transportResources = null)

        gw.close()
    }

    private fun streamDeltaFrame(conversationId: String, envelope: String): AppServerReceivedFrame {
        val body = envelope.replace(""""conversation_id":"conv-1"""", """"conversation_id":"$conversationId"""")
        return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
    }

    private class FakeAppServerController(
        private val runTurnFlow: (TurnCommand) -> Flow<RuntimeEventDraft>,
    ) : AppServerController {
        override val state: Flow<AppServerControllerState> =
            kotlinx.coroutines.flow.MutableStateFlow(AppServerControllerState.Connected)

        val callOrder = mutableListOf<String>()
        var recordedTurnCommand: TurnCommand? = null
            private set
        var recordedStartRuntimeMode: AppServerPermissionMode? = null
            private set

        override suspend fun startRuntime(
            agentId: com.letta.mobile.data.model.AgentId,
            conversationId: ConversationId,
            cwd: String?,
            mode: AppServerPermissionMode?,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): CanonicalRuntime {
            callOrder += "startRuntime"
            recordedStartRuntimeMode = mode
            return CanonicalRuntime(
                scope = AppServerRuntimeScope(agentId = agentId.value, conversationId = conversationId.value),
            )
        }

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> {
            callOrder += "runTurn"
            recordedTurnCommand = command
            return runTurnFlow(command)
        }

        override suspend fun sync(
            runtime: AppServerRuntimeScope,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): AppServerInboundFrame.SyncResponse = error("sync not supported in fake")

        override suspend fun abort(
            runtime: AppServerRuntimeScope,
            runId: String?,
        ): AppServerInboundFrame.AbortMessageResponse = error("abort not supported in fake")
    }

    private class FakeAppServerClient : AppServerClient {
        val eventsFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
        override val events: Flow<AppServerReceivedFrame> = eventsFlow

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
