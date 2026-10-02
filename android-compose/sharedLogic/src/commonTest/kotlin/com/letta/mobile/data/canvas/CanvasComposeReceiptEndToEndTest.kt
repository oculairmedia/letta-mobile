package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.AGENT
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.CONVERSATION
import com.letta.mobile.data.canvas.compose.CanvasComposeIds
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipts
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaMessageSerializer
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.runtime.ExternalToolDispatcher
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.reduceStreamFrame
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The compose call id, end to end in one process (letta-mobile-bglj6.12 with C8's projection): the
 * App Server's `external_tool_call_request` is answered by the real dispatcher through the Iroh
 * host's canvas_compose, the answer comes back on the run as the tool return of the TOOL_CALL with
 * the same `tool_call_id`, and the chat projection puts exactly one receipt card on the message
 * that narrates it, for the artifact the host derived from that id.
 */
class CanvasComposeReceiptEndToEndTest {
    private class RecordingClient : AppServerClient {
        val responses = mutableListOf<AppServerCommand.ExternalToolCallResponse>()
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = Unit
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
            responses += command
        }
    }

    /** One compose call answered by the host, then the run's frames as the App Server streams them. */
    private suspend fun run(input: String): Pair<HostCanvasComposeToolsTest.Host, List<TimelineEvent>> {
        val host = HostCanvasComposeToolsTest.Host()
        val client = RecordingClient()
        val dispatcher = ExternalToolDispatcher(
            client = client,
            externalToolRegistry = host.registry,
            inboundControlRegistry = InboundControlRequestRegistry(),
            connectionGenerationProvider = { 0L },
        )
        val arguments = Json.parseToJsonElement(input).jsonObject
        dispatcher.answer(
            AppServerInboundFrame.ExternalToolCallRequest(
                requestId = "req-1",
                runtime = AppServerRuntimeScope(agentId = AGENT, conversationId = CONVERSATION),
                toolCallId = TOOL_CALL_ID,
                toolName = CanvasToolContract.COMPOSE,
                input = arguments,
            ),
            leaseToken = 1L,
            validatedGeneration = 0L,
        )
        val answered = client.responses.single().result!!
        val frames: List<LettaMessage> = listOf(
            UserMessage(id = "user-1", contentRaw = JsonPrimitive("Put my weekend on the board"), runId = RUN, otid = "otid-user-1"),
            ToolCallMessage(
                id = "msg-call-1", runId = RUN, seqId = 1, otid = "otid-call-1",
                toolCall = ToolCall(id = TOOL_CALL_ID, name = CanvasToolContract.COMPOSE, arguments = arguments.toString()),
            ),
            toolReturnFrame(answered.content.single().text.orEmpty(), isError = answered.isError == true),
            AssistantMessage(id = "msg-assistant-1", contentRaw = JsonPrimitive("It's on the board."), runId = RUN, seqId = 3, otid = "otid-a-1"),
        )
        var timeline = Timeline(conversationId = CONVERSATION)
        var pending = persistentMapOf<String, ToolReturnMessage>()
        frames.forEach { frame ->
            val out = reduceStreamFrame(TimelineReducerInput(prev = timeline, frame = frame, pendingToolReturnsByCallId = pending))
            timeline = out.next
            pending = out.updatedPendingToolReturnsByCallId
        }
        return host to timeline.events
    }

    /**
     * The tool return as letta-code streams it for an external tool (the shape of the golden
     * capture `letta-code-0.32.3-live.jsonl`): the answer's text as `tool_return`, again under
     * `tool_returns`, decoded the way the app decodes every run frame.
     */
    private fun toolReturnFrame(text: String, isError: Boolean): LettaMessage {
        val status = if (isError) "error" else "success"
        val frame = buildJsonObject {
            put("message_type", "tool_return_message")
            put("id", "ret-1")
            put("run_id", RUN)
            put("seq_id", 2)
            put("status", status)
            put("tool_call_id", TOOL_CALL_ID)
            put("tool_return", text)
            put("tool_returns", buildJsonArray {
                add(buildJsonObject {
                    put("tool_call_id", TOOL_CALL_ID)
                    put("status", status)
                    put("tool_return", text)
                })
            })
        }
        return Json { ignoreUnknownKeys = true }.decodeFromJsonElement(LettaMessageSerializer, frame)
    }

    @Test
    fun oneComposeCallIsOneReceiptOnTheNarratingMessage() = runTest {
        val (host, events) = run("""{"title":"Weekend","items":[{"kind":"NOTE","markdown":"Pasta on Saturday"}]}""")

        val attached = CanvasArtifactReceipts.attach(events)
        val narrating = events.filterIsInstance<TimelineEvent.Confirmed>().single { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(setOf(CanvasArtifactReceipts.eventKey(narrating)), attached.keys)
        val receipt = attached.values.single().single()
        assertEquals(CanvasArtifactStatus.Published, receipt.status)
        assertEquals(TOOL_CALL_ID, receipt.toolCallId)
        assertEquals(CanvasComposeIds.derived(TOOL_CALL_ID), receipt.artifactId, "the artifact the host derived from the call id")
        assertEquals(CanvasId.forConversation(CONVERSATION).value, receipt.canvasId)
        assertEquals(host.scene().revision, receipt.revision)
        assertEquals("Weekend", receipt.title)
    }

    @Test
    fun aRefusedCallIsOneFailedReceiptWithTheRefusalsCode() = runTest {
        val (host, events) = run("""{"items":[{"kind":"POEM","text":"x"}]}""")

        val receipt = CanvasArtifactReceipts.attach(events).values.single().single()
        assertEquals(CanvasArtifactStatus.Failed, receipt.status)
        assertEquals("VALIDATION_FAILED", receipt.error?.code)
        assertEquals(TOOL_CALL_ID, receipt.toolCallId)
        assertEquals(0, host.logged().size, "a refused call publishes nothing")
    }

    private companion object {
        const val TOOL_CALL_ID = "toolu_01E2EComposeCall"
        const val RUN = "run-e2e-1"
    }
}
