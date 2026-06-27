package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.util.Telemetry
import java.time.Instant
import org.junit.After
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
    fun `reduce attaches tool return image attachments to tool call event`() {
        val result = TimelineHydrationReducer.reduce(
            conversationId = "conversation-1",
            serverMessagesChronological = listOf(
                ToolCallMessage(
                    id = "tool-call-image",
                    toolCall = ToolCall(toolCallId = "call-image", name = "Read", arguments = "{}"),
                ),
                ToolReturnMessage(
                    id = "tool-return-image",
                    toolCallId = "call-image",
                    status = "success",
                    toolReturnRaw = buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("image"))
                            put("source", buildJsonObject {
                                put("type", JsonPrimitive("letta"))
                                put("file_id", JsonPrimitive("file-tool"))
                                put("media_type", JsonPrimitive("image/png"))
                                put("data", JsonPrimitive("HYDRATED_TOOL_IMAGE+/=="))
                            })
                        })
                    },
                ),
            ),
            timelineBeforeFetch = Timeline("conversation-1"),
            currentTimeline = Timeline("conversation-1"),
            diskRecords = emptyList(),
        )

        val toolEvent = result.timeline.events.single() as TimelineEvent.Confirmed
        assertEquals(
            listOf(MessageContentPart.Image(base64 = "HYDRATED_TOOL_IMAGE+/==", mediaType = "image/png")),
            toolEvent.attachments,
        )
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
            currentTimeline = Timeline("conversation-1", events = persistentListOf(local)),
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
        val current = Timeline("conversation-1", events = persistentListOf(first, second))
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
            currentTimeline = Timeline("conversation-1", events = persistentListOf(currentAssistant)),
            diskRecords = emptyList(),
        )

        val confirmed = result.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(1, confirmed.size)
        assertEquals("rest-assistant", confirmed.single().serverId)
    }

    @Test
    fun `reduce collapses duplicate assistant rows within the server snapshot`() {
        // Cold-start replay after a rebuild: the server snapshot itself carries
        // the same logical assistant message twice — same run_id + content, but
        // a different server id (and otids that do not collide). Without semantic
        // dedup in hydration, both rows survive and the UI shows a doubled bubble.
        val result = TimelineHydrationReducer.reduce(
            conversationId = "conversation-1",
            serverMessagesChronological = listOf(
                AssistantMessage(
                    id = "assistant-original",
                    contentRaw = JsonPrimitive("Got it, I can check the latest build."),
                    date = "2026-06-27T07:30:00Z",
                    runId = "run-replay",
                ),
                AssistantMessage(
                    id = "assistant-replayed",
                    contentRaw = JsonPrimitive("Got it, I can check the latest build."),
                    date = "2026-06-27T07:30:01Z",
                    runId = "run-replay",
                ),
            ),
            timelineBeforeFetch = Timeline("conversation-1"),
            currentTimeline = Timeline("conversation-1"),
            diskRecords = emptyList(),
        )

        val confirmed = result.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(1, confirmed.size)
        assertEquals("assistant-original", confirmed.single().serverId)
        assertEquals("Got it, I can check the latest build.", confirmed.single().content)
    }

    @Test
    fun `reduce keeps distinct assistant rows from different runs`() {
        // Guard: two assistant messages with identical content but DIFFERENT
        // run ids are genuinely distinct turns and must both survive.
        val result = TimelineHydrationReducer.reduce(
            conversationId = "conversation-1",
            serverMessagesChronological = listOf(
                AssistantMessage(
                    id = "assistant-run-a",
                    contentRaw = JsonPrimitive("Done."),
                    date = "2026-06-27T07:30:00Z",
                    runId = "run-a",
                ),
                AssistantMessage(
                    id = "assistant-run-b",
                    contentRaw = JsonPrimitive("Done."),
                    date = "2026-06-27T07:31:00Z",
                    runId = "run-b",
                ),
            ),
            timelineBeforeFetch = Timeline("conversation-1"),
            currentTimeline = Timeline("conversation-1"),
            diskRecords = emptyList(),
        )

        val confirmed = result.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(2, confirmed.size)
    }
}
