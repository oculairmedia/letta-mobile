package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.util.Telemetry
import java.time.Instant
import org.junit.After
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineHydrationReducerTest {

    @After
    fun tearDown() {
        Telemetry.clear()
    }

    @Test
    fun `reduce attaches tool returns and drops standalone return bubbles`() {
        val result = TimelineHydrationReducer.reduce(
            conversationId = "conversation-1",
            serverMessagesChronological = listOf(
                ToolCallMessage(
                    id = "tool-call-1",
                    toolCall = ToolCall(toolCallId = "call-1", name = "Bash", arguments = "{}"),
                ),
                ToolReturnMessage(
                    id = "tool-return-1",
                    toolCallId = "call-1",
                    status = "success",
                    toolReturnRaw = JsonPrimitive("ok"),
                ),
            ),
            timelineBeforeFetch = Timeline("conversation-1"),
            currentTimeline = Timeline("conversation-1"),
            diskRecords = emptyList(),
        )

        assertEquals(1, result.visibleEventCount)
        val toolEvent = result.timeline.events.single() as TimelineEvent.Confirmed
        assertEquals(TimelineMessageType.TOOL_CALL, toolEvent.messageType)
        assertEquals("ok", toolEvent.toolReturnContentByCallId["call-1"])
        assertTrue(toolEvent.approvalDecided)
    }

    @Test
    fun `reduce ignores blank tool return ids for synthetic tool calls`() {
        val result = TimelineHydrationReducer.reduce(
            conversationId = "conversation-1",
            serverMessagesChronological = listOf(
                ToolCallMessage(
                    id = "tool-call-blank",
                    toolCall = ToolCall(name = "synthetic_tool", arguments = "{}"),
                ),
                ToolReturnMessage(
                    id = "tool-return-blank",
                    toolCallId = "",
                    status = "error",
                    toolReturnRaw = JsonPrimitive("should_not_attach"),
                    isErr = true,
                ),
            ),
            timelineBeforeFetch = Timeline("conversation-1"),
            currentTimeline = Timeline("conversation-1"),
            diskRecords = emptyList(),
        )

        assertEquals(1, result.visibleEventCount)
        val toolEvent = result.timeline.events.single() as TimelineEvent.Confirmed
        assertEquals(TimelineMessageType.TOOL_CALL, toolEvent.messageType)
        assertEquals(false, toolEvent.approvalDecided)
        assertTrue(toolEvent.toolReturnContentByCallId.isEmpty())
    }

    @Test
    fun `reduce preserves concurrent locals after server snapshot`() {
        val local = TimelineEvent.Local(
            position = 1.0,
            otid = "local-1",
            content = "pending",
            role = Role.USER,
            sentAt = Instant.parse("2026-05-10T00:00:00Z"),
            deliveryState = DeliveryState.SENDING,
        )

        val result = TimelineHydrationReducer.reduce(
            conversationId = "conversation-1",
            serverMessagesChronological = listOf(UserMessage(id = "server-1", contentRaw = JsonPrimitive("confirmed"))),
            timelineBeforeFetch = Timeline("conversation-1"),
            currentTimeline = Timeline("conversation-1", events = listOf(local)),
            diskRecords = emptyList(),
        )

        assertEquals(listOf("server-server-1-user", "local-1"), result.timeline.events.map { it.otid })
        assertEquals(1.0, result.timeline.events.first().position, 0.0)
        assertEquals(2.0, result.timeline.events.last().position, 0.0)
    }

    @Test
    fun `reduce drops duplicate otids preserved from current timeline`() {
        val first = TimelineEvent.Confirmed(
            position = 1.0,
            otid = "dup-otid",
            content = "first",
            serverId = "current-1",
            messageType = TimelineMessageType.ASSISTANT,
            date = Instant.parse("2026-05-10T00:00:00Z"),
            runId = null,
            stepId = null,
        )
        val second = first.copy(
            position = 2.0,
            content = "second",
            serverId = "current-2",
        )
        val current = Timeline("conversation-1", events = listOf(first, second))
        Telemetry.clear()

        val result = TimelineHydrationReducer.reduce(
            conversationId = "conversation-1",
            serverMessagesChronological = emptyList(),
            timelineBeforeFetch = Timeline("conversation-1"),
            currentTimeline = current,
            diskRecords = emptyList(),
        )

        assertEquals(1, result.timeline.events.size)
        assertEquals("first", result.timeline.events.single().content)
        assertTrue(
            "Expected hydration duplicate drop telemetry",
            Telemetry.snapshot().any {
                it.tag == "Timeline" && it.name == "hydrate.duplicateOtidDropped"
            },
        )
    }

    @Test
    fun `reduce drops concurrent confirmed assistant with same run and content as server snapshot`() {
        val currentAssistant = TimelineEvent.Confirmed(
            position = 1.0,
            otid = "server-ws-assistant-assistant",
            content = "Let me check the more recent one then.",
            serverId = "ws-assistant",
            messageType = TimelineMessageType.ASSISTANT,
            date = Instant.parse("2026-05-26T15:00:02Z"),
            runId = "run-reopen",
            stepId = null,
        )
        val result = TimelineHydrationReducer.reduce(
            conversationId = "conversation-1",
            serverMessagesChronological = listOf(
                AssistantMessage(
                    id = "rest-assistant",
                    contentRaw = JsonPrimitive("Let me check the more recent one then."),
                    date = "2026-05-26T15:00:01Z",
                    runId = "run-reopen",
                )
            ),
            timelineBeforeFetch = Timeline("conversation-1"),
            currentTimeline = Timeline("conversation-1", events = listOf(currentAssistant)),
            diskRecords = emptyList(),
        )

        val confirmed = result.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(1, confirmed.size)
        assertEquals("rest-assistant", confirmed.single().serverId)
    }
}
