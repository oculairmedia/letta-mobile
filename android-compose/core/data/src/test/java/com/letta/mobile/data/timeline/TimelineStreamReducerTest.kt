package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.util.Telemetry
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class TimelineStreamReducerTest {
    @After
    fun tearDown() {
        Telemetry.clear()
    }

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
    fun `approval response marks matching approval request decided`() {
        val approvalRequest = ApprovalRequestMessage(
            id = "approval-1",
            toolCall = ToolCall(toolCallId = "call-approval", name = "danger", arguments = "{}"),
        )
        val seeded = reduce(frame = approvalRequest).next

        val output = reduce(
            prev = seeded,
            frame = ApprovalResponseMessage(
                id = "approval-response-1",
                approvalRequestId = "approval-1",
                approve = true,
            ),
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.approvalRequestId shouldBe "approval-1"
        event.approvalDecided shouldBe true
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("approval-1", "approval_response_message")
        )
        output.notification shouldBe null
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

    @Test
    fun `server id match merges stream deltas into existing confirmed event`() {
        val seeded = reduce(
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hel"))
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("lo")),
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.content shouldBe "Hello"
        output.next.liveCursor shouldBe "assistant-1"
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("assistant-1", "assistant_message")
        )
        output.notification shouldBe null
    }

    @Test
    fun `cumulative frames do not double existing text`() {
        val seeded = reduce(
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hey"), seqId = 1)
        ).next

        val out2 = reduce(
            prev = seeded,
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hey Emmanuel."), seqId = 2),
        )
        (out2.next.events.single() as TimelineEvent.Confirmed).content shouldBe "Hey Emmanuel."

        val out3 = reduce(
            prev = out2.next,
            frame = AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Hey Emmanuel. Most recent thing"),
                seqId = 3,
            ),
        )
        (out3.next.events.single() as TimelineEvent.Confirmed).content shouldBe "Hey Emmanuel. Most recent thing"
    }

    @Test
    fun `stale cumulative prefix frames are dropped instead of appended`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Standing by. Send a message."),
                seqId = 1,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Standing by."),
                seqId = 2,
            ),
        )

        (output.next.events.single() as TimelineEvent.Confirmed).content shouldBe
            "Standing by. Send a message."
    }

    @Test
    fun `matching-tail frames are dropped instead of duplicated`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Standing by. Send a message."),
                seqId = 1,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Send a message."),
                seqId = 2,
            ),
        )

        (output.next.events.single() as TimelineEvent.Confirmed).content shouldBe
            "Standing by. Send a message."
    }

    @Test
    fun `defensive stream merge branches emit named telemetry`() {
        telemetryNamesForMerge("Stand", "Standing by.")
            .contains("streamSubscriber.cumulativeSnapshotReplaced") shouldBe true
        telemetryNamesForMerge("Standing by.", "Stand")
            .contains("streamSubscriber.staleFrameDropped") shouldBe true
        telemetryNamesForMerge("Standing by.", "by.")
            .contains("streamSubscriber.endsWithDropped") shouldBe true
    }

    @Test
    fun `stream text merge only uses snapshot branches when seq ids are available`() {
        mergeStreamText(
            existing = "Hello",
            incoming = "Hello world",
            canUseSnapshotMerge = true,
        ) shouldBe StreamTextMergeResult(
            text = "Hello world",
            branch = StreamTextMergeBranch.CUMULATIVE,
            garbleRisk = false,
        )

        mergeStreamText(
            existing = "Hello",
            incoming = "Hello world",
            canUseSnapshotMerge = false,
        ) shouldBe StreamTextMergeResult(
            text = "HelloHello world",
            branch = StreamTextMergeBranch.APPEND,
            garbleRisk = false,
        )
    }

    @Test
    fun `stream text merge flags suspicious short appends for diagnostics`() {
        mergeStreamText(
            existing = "The previous assistant text is already long",
            incoming = " no",
            canUseSnapshotMerge = false,
        ) shouldBe StreamTextMergeResult(
            text = "The previous assistant text is already long no",
            branch = StreamTextMergeBranch.APPEND,
            garbleRisk = true,
        )
    }

    @Test
    fun `seqId dedup skips already-ingested stream frame`() {
        val seeded = reduce(
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hello"), seqId = 3)
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hel"), seqId = 2),
        )
        (output.next.events.single() as TimelineEvent.Confirmed).content shouldBe "Hello"
        output.emittedEvents shouldBe emptyList()
    }

    @Test
    fun `otid match dedupes duplicate stream frame`() {
        val seeded = reduce(
            frame = UserMessage(id = "server-user-1", contentRaw = JsonPrimitive("hello"), otid = "shared-otid")
        ).next

        val output = reduce(
            prev = seeded,
            frame = UserMessage(id = "server-user-2", contentRaw = JsonPrimitive("hello"), otid = "shared-otid"),
        )

        output.next.events shouldHaveSize 1
        (output.next.events.single() as TimelineEvent.Confirmed).serverId shouldBe "server-user-1"
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }

    @Test
    fun `semantic match dedupes hydrate then ws assistant with different server id`() {
        val hydrated = TimelineHydrationReducer.reduce(
            conversationId = "conv-test",
            serverMessagesChronological = listOf(
                AssistantMessage(
                    id = "rest-assistant",
                    contentRaw = JsonPrimitive("Let me check the more recent one then."),
                    runId = "run-reopen",
                    seqId = 1,
                )
            ),
            timelineBeforeFetch = Timeline("conv-test"),
            currentTimeline = Timeline("conv-test"),
            diskRecords = emptyList(),
        ).timeline
        Telemetry.clear()

        val output = reduce(
            prev = hydrated,
            frame = AssistantMessage(
                id = "ws-assistant",
                contentRaw = JsonPrimitive("Let me check the more recent one then."),
                runId = "run-reopen",
                seqId = 2,
            ),
        )

        output.next.events shouldHaveSize 1
        (output.next.events.single() as TimelineEvent.Confirmed).serverId shouldBe "rest-assistant"
        output.emittedEvents shouldBe emptyList()
        Telemetry.snapshot().any {
            it.tag == "TimelineSync" &&
                it.name == "streamSubscriber.eventDeduped" &&
                it.attrs["reason"] == "semanticIdentitySeen"
        } shouldBe true
    }

    @Test
    fun `late same-run assistant prefix with a new server id is dropped`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-full",
                contentRaw = JsonPrimitive("Opening line\n\nFinal body"),
                runId = "run-1",
                seqId = 20,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-prefix",
                contentRaw = JsonPrimitive("Opening line"),
                runId = "run-1",
                seqId = 21,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-full"
        event.content shouldBe "Opening line\n\nFinal body"
        output.next.liveCursor shouldBe "assistant-full"
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }

    @Test
    fun `late same-run blank assistant with a new server id is dropped after nonblank assistant`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-full",
                contentRaw = JsonPrimitive("Opening line\n\nFinal body"),
                runId = "run-1",
                seqId = 20,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-empty",
                contentRaw = JsonPrimitive(""),
                runId = "run-1",
                seqId = 21,
            ),
        )

        output.next.events shouldHaveSize 1
        (output.next.events.single() as TimelineEvent.Confirmed).serverId shouldBe "assistant-full"
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }

    @Test
    fun `assistant prefix from a different run is preserved`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-full",
                contentRaw = JsonPrimitive("Opening line\n\nFinal body"),
                runId = "run-1",
                seqId = 20,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-prefix-next-run",
                contentRaw = JsonPrimitive("Opening line"),
                runId = "run-2",
                seqId = 1,
            ),
        )

        output.next.events shouldHaveSize 2
        val event = output.next.events.last() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-prefix-next-run"
        event.content shouldBe "Opening line"
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("assistant-prefix-next-run", "assistant_message")
        )
    }

    @Test
    fun `plain append adds new confirmed event and emits notification for assistant`() {
        val output = reduce(
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("hello"))
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-1"
        event.content shouldBe "hello"
        output.next.liveCursor shouldBe "assistant-1"
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("assistant-1", "assistant_message")
        )
        output.notification shouldBe PendingIngestNotification(
            serverId = "assistant-1",
            messageType = "assistant_message",
            contentPreview = "hello",
        )
    }

    private fun reduce(
        prev: Timeline = timeline(),
        frame: com.letta.mobile.data.model.LettaMessage,
        pendingToolReturnsByCallId: Map<String, ToolReturnMessage> = emptyMap(),
    ): TimelineReducerOutput = reduceStreamFrame(
        TimelineReducerInput(
            prev = prev,
            frame = frame,
            pendingToolReturnsByCallId = pendingToolReturnsByCallId,
        )
    )

    private fun timeline(): Timeline = Timeline(conversationId = "conv-test")

    private fun telemetryNamesForMerge(existing: String, incoming: String): Set<String> {
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
}
