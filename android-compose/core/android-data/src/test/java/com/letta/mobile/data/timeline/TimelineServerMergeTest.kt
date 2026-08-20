package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("integration")
class TimelineServerMergeTest {
    @Test
    fun `mergeServerMessages inserts by date dedupes and skips tool returns`() {
        val timeline = Timeline("c1")
            .append(
                confirmed(
                    otid = "older",
                    pos = 1.0,
                    type = TimelineMessageType.USER,
                    serverId = "server-older",
                    date = Instant.parse("2026-05-19T06:00:00Z"),
                )
            )
            .append(
                confirmed(
                    otid = "newer",
                    pos = 4.0,
                    type = TimelineMessageType.ASSISTANT,
                    serverId = "server-newer",
                    date = Instant.parse("2026-05-19T06:30:00Z"),
                )
            )

        val (merged, insertedCount) = timeline.mergeServerMessages(
            listOf(
                UserMessage(
                    id = "server-duplicate-otid",
                    contentRaw = JsonPrimitive("duplicate otid"),
                    otid = "older",
                    date = "2026-05-19T06:05:00Z",
                ),
                AssistantMessage(
                    id = "server-newer",
                    contentRaw = JsonPrimitive("duplicate server id"),
                    otid = "fresh-server-id-duplicate",
                    date = "2026-05-19T06:10:00Z",
                ),
                ToolReturnMessage(
                    id = "tool-return",
                    toolCallId = "toolu_1",
                    toolReturnRaw = JsonPrimitive("hidden output"),
                    date = "2026-05-19T06:15:00Z",
                ),
                UserMessage(
                    id = "fresh-between",
                    contentRaw = JsonPrimitive("fresh prompt"),
                    date = "2026-05-19T06:20:00Z",
                ),
            )
        )

        val confirmed = merged.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(1, insertedCount)
        assertEquals(listOf("server-older", "fresh-between", "server-newer"), confirmed.map { it.serverId })
        assertEquals("fresh prompt", confirmed[1].content)
        assertTrue("fresh message should be positioned between existing dated events", confirmed[1].position in 1.0..4.0)
        assertEquals(3, confirmed.size)
        assertNull(merged.findByServerId("tool-return", TimelineMessageType.TOOL_RETURN))
    }

    @Test
    fun `mergeServerMessages dedupes same run assistant content with different server id`() {
        val timeline = Timeline("c1")
            .append(
                confirmed(
                    otid = "live-otid",
                    pos = 1.0,
                    serverId = "ws-assistant",
                    date = Instant.parse("2026-05-26T15:00:00Z"),
                    content = "Let me check the more recent one then.",
                    runId = "run-reopen",
                )
            )

        val (merged, insertedCount) = timeline.mergeServerMessages(
            listOf(
                AssistantMessage(
                    id = "rest-assistant",
                    contentRaw = JsonPrimitive("Let me check the more recent one then."),
                    date = "2026-05-26T15:00:01Z",
                    runId = "run-reopen",
                )
            )
        )

        val confirmed = merged.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(0, insertedCount)
        assertEquals(listOf("ws-assistant"), confirmed.map { it.serverId })
    }

    private fun confirmed(
        otid: String,
        pos: Double,
        type: TimelineMessageType = TimelineMessageType.ASSISTANT,
        serverId: String = "server-$otid",
        date: Instant = Instant.now(),
        content: String = "confirmed",
        runId: String? = null,
    ): TimelineEvent.Confirmed = TimelineEvent.Confirmed(
        position = pos,
        otid = otid,
        content = content,
        serverId = serverId,
        messageType = type,
        date = date,
        runId = runId,
        stepId = null,
    )
}
