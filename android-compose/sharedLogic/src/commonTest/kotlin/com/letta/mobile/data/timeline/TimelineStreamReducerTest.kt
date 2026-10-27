package com.letta.mobile.data.timeline


import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals


class TimelineStreamReducerTest : TimelineStreamReducerFixture() {
    @Test
    fun `ws approval request maps through timeline with approval request id`() {
        val mapped = WsFrameMapper.toLettaMessage(
            ServerFrame.ToolCallMessage(
                type = "approval_request_message",
                id = "approval-1",
                ts = "2026-05-23T00:00:00Z",
                agentId = "agent-1",
                conversationId = "conv-1",
                turnId = "turn-1",
                runId = "run-1",
                toolCall = ToolCallPayload(toolCallId = "call-approval", name = "danger", arguments = "{}"),
            )
        )!!

        val event = mapped.toTimelineEvent(position = 1.0)!!

        event.serverId shouldBe "approval-1"
        event.approvalRequestId shouldBe "approval-1"
        event.toolCalls.single().effectiveId shouldBe "call-approval"
    }

    @Test
    fun `tool return attaches to matching tool call and emits notification`() {
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-a", name = "read", arguments = "a"),
                    ToolCall(toolCallId = "call-b", name = "write", arguments = "b"),
                ),
            )
        ).next
        val toolReturn = ToolReturnMessage(
            id = "return-b",
            toolCallId = "call-b",
            status = "success",
            toolReturnRaw = JsonPrimitive("done"),
        )

        val output = reduce(prev = seeded, frame = toolReturn)

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.toolReturnContentByCallId["call-b"] shouldBe "done"
        event.toolReturnIsErrorByCallId["call-b"] shouldBe false
        event.approvalDecided shouldBe true
        output.updatedPendingToolReturnsByCallId shouldBe emptyMap()
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("tool-batch", toolReturn.messageType)
        )
        output.notification shouldBe PendingIngestNotification(
            serverId = "tool-batch",
            messageType = "tool_return_message",
            contentPreview = "done",
        )
    }

    @Test
    fun `replayed tool call with a different run id does not append a second row`() {
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "toolcall-edit-1",
                runId = "run-real",
                toolCalls = listOf(ToolCall(toolCallId = "call-edit-1", name = "Edit", arguments = "{\"path\":\"a\"}")),
            ),
        ).next
        val replay = reduce(
            prev = seeded,
            frame = ToolCallMessage(
                id = "letta-msg-replay-9",
                runId = "desktop-stream-run-conv",
                toolCalls = listOf(ToolCall(toolCallId = "call-edit-1", name = "Edit", arguments = "{\"path\":\"a\"}")),
            ),
        )

        replay.next.events.size shouldBe 1
        (replay.next.events.single() as TimelineEvent.Confirmed).serverId shouldBe "toolcall-edit-1"
    }

    @Test
    fun `tool return image attaches to matching tool call attachments`() {
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCall = ToolCall(toolCallId = "call-image", name = "Read", arguments = "{}"),
            )
        ).next
        val toolReturn = ToolReturnMessage(
            id = "return-image",
            toolCallId = "call-image",
            status = "success",
            toolReturnRaw = buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("source", buildJsonObject {
                        put("type", JsonPrimitive("letta"))
                        put("file_id", JsonPrimitive("file-tool"))
                        put("media_type", JsonPrimitive("image/png"))
                        put("data", JsonPrimitive("STREAM_TOOL_IMAGE+/=="))
                    })
                })
            },
        )

        val output = reduce(prev = seeded, frame = toolReturn)

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.attachments shouldBe listOf(
            MessageContentPart.Image(base64 = "STREAM_TOOL_IMAGE+/==", mediaType = "image/png")
        )
    }

    @Test
    fun `generate image tool return attaches inline image to matching tool call`() {
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-generate-image",
                toolCall = ToolCall(toolCallId = "call-generate-image", name = "generate_image", arguments = "{}"),
            )
        ).next
        val toolReturn = ToolReturnMessage(
            id = "return-generate-image",
            toolCallId = "call-generate-image",
            status = "success",
            toolReturnRaw = buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("""
                        {
                          "path": "/tmp/generated-image.png",
                          "mime_type": "image/png",
                          "model": "gpt-image-2-medium",
                          "size": "1024x1024",
                          "quality": "medium",
                          "prompt": "a small brass robot"
                        }
                    """.trimIndent()))
                })
                add(buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("source", buildJsonObject {
                        put("type", JsonPrimitive("base64"))
                        put("media_type", JsonPrimitive("image/png"))
                        put("data", JsonPrimitive("STREAM_GENERATED_IMAGE+/=="))
                    })
                })
            },
        )

        val output = reduce(prev = seeded, frame = toolReturn)

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.toolReturnContentByCallId["call-generate-image"].orEmpty().contains("gpt-image-2-medium") shouldBe true
        event.attachments shouldBe listOf(
            MessageContentPart.Image(base64 = "STREAM_GENERATED_IMAGE+/==", mediaType = "image/png")
        )
    }

    @Test
    fun `tool return without matching call is buffered without rendering`() {
        val toolReturn = ToolReturnMessage(
            id = "return-first",
            toolCallId = "call-late",
            status = "success",
            toolReturnRaw = JsonPrimitive("late result"),
        )

        val output = reduce(frame = toolReturn)

        output.next.events shouldBe emptyList()
        output.updatedPendingToolReturnsByCallId shouldBe mapOf("call-late" to toolReturn)
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }

    @Test
    fun `blank tool return id does not attach to blank synthetic tool call`() {
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-blank",
                toolCall = ToolCall(name = "synthetic_tool", arguments = "{}"),
            )
        ).next
        val toolReturn = ToolReturnMessage(
            id = "return-blank",
            toolCallId = "",
            status = "error",
            toolReturnRaw = JsonPrimitive("should_not_attach"),
            isErr = true,
        )

        val output = reduce(prev = seeded, frame = toolReturn)

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.approvalDecided shouldBe false
        event.toolReturnContentByCallId shouldBe emptyMap()
        output.updatedPendingToolReturnsByCallId shouldBe emptyMap()
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }
}






abstract class TimelineStreamReducerFixture : com.letta.mobile.util.TelemetryRecordingTest() {
    @AfterTest
    fun tearDown() {
        Telemetry.clear()
        Telemetry.chatHotPathDebugEnabled.set(false)
    }

    protected fun textAssistant(
        id: String,
        content: String,
        runId: String? = null,
        seqId: Int? = null,
        otid: String? = null,
        logicalMessageId: String? = null,
    ): AssistantMessage = AssistantMessage(
        id = id, contentRaw = JsonPrimitive(content), runId = runId,
        seqId = seqId, otid = otid, logicalMessageId = logicalMessageId,
    )

    protected fun seedAssistant(frame: AssistantMessage): Timeline = reduce(frame = frame).next

    protected fun reduce(
        prev: Timeline = timeline(),
        frame: com.letta.mobile.data.model.LettaMessage,
        pendingToolReturnsByCallId: PersistentMap<String, ToolReturnMessage> = persistentMapOf(),
        agentId: String? = null,
    ): TimelineReducerOutput = reduceStreamFrame(
        TimelineReducerInput(
            prev = prev,
            frame = frame,
            pendingToolReturnsByCallId = pendingToolReturnsByCallId,
            agentId = agentId,
        )
    )

    protected fun timeline(): Timeline = Timeline(conversationId = "conv-test")

    protected fun telemetryNamesForMerge(existing: String, incoming: String): Set<String> {
        Telemetry.clear()
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-telemetry",
                contentRaw = JsonPrimitive(existing),
                seqId = 1,
            )
        ).next
        reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-telemetry",
                contentRaw = JsonPrimitive(incoming),
                seqId = 2,
            ),
        )
        return Telemetry.snapshot().map { it.name }.toSet()
    }

    protected fun realCapturedSkillEnvelope(): String = """
        <asus-router>
        name: asus-router
        description: Pull stats from ASUS RT-AX82U router and summarize WAN/LAN status.
        ---
        # ASUS Router

        This skill connects to the ASUS router API and reports useful status.

        ## Usage

        Ask for router status, WAN uptime, connected clients, or traffic counters.

        ```json
        { "action": "status" }
        ```

        ## Notes

        The router credentials are configured by the host environment.

        ARGUMENTS: status
        </asus-router>
    """.trimIndent()

}


internal infix fun <T> T.shouldBe(expected: T) {
    assertEquals(expected, this)
}

internal infix fun Collection<*>.shouldHaveSize(expected: Int) {
    assertEquals(expected, size)
}
