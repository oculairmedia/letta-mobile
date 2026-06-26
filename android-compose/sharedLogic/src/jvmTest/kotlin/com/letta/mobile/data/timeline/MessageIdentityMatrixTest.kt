package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.reduceStreamFrame
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageIdentityMatrixTest {

    @Test
    fun `reducer and projector matrix for logical message identity`() {
        var timeline = Timeline("conv-1")
        var pendingReturns = persistentMapOf<String, ToolReturnMessage>()

        fun feed(msg: com.letta.mobile.data.model.LettaMessage) {
            val out = reduceStreamFrame(
                TimelineReducerInput(
                    prev = timeline,
                    frame = msg,
                    pendingToolReturnsByCallId = pendingReturns
                )
            )
            timeline = out.next
            pendingReturns = out.updatedPendingToolReturnsByCallId
        }

        val projector = ChatTimelineProjector()
        fun project(): List<com.letta.mobile.data.model.UiMessage> {
            return projector.project(timeline, emptyList(), ChatUiState()).ui
        }

        // 1. Initial user message
        feed(UserMessage(id = "user-1", contentRaw = JsonPrimitive("Hello"), otid = "otid-user-1", seqId = 1))
        assertEquals(1, project().size, "Should have 1 user message")

        // 2. same otid with different serverId
        feed(UserMessage(id = "user-2", contentRaw = JsonPrimitive("Hello"), otid = "otid-user-1", seqId = 1))
        assertEquals(1, project().size, "No duplicate UI messages for same otid")

        // 3. same serverId (update) -> Use seqId > 1 so text merge can use snapshot strategy if possible
        feed(UserMessage(id = "user-1", contentRaw = JsonPrimitive("Hello updated"), otid = "otid-user-1", seqId = 2))
        assertEquals(1, project().size, "Should still have 1 user message after update")
        assertEquals("Hello updated", project()[0].content)

        // 4. tool_call/tool_return matching
        feed(ToolCallMessage(
            id = "tool-1",
            toolCall = ToolCall(toolCallId = "call-1", name = "read", arguments = "{}")
        ))

        feed(ToolReturnMessage(
            id = "return-1",
            toolCallId = "call-1",
            status = "success",
            toolReturnRaw = JsonPrimitive("read result")
        ))

        // Ensure no duplication or orphan drops, tool return attached
        var ui = project()
        assertEquals(2, ui.size, "Should have 1 user message and 1 tool message")
        assertTrue(ui[1].toolCalls != null && ui[1].toolCalls!!.isNotEmpty(), "Second message should be a tool call")
        assertEquals("read result", ui[1].toolCalls!![0].result, "Tool result should be attached")

        // 5. same runId post-tool assistant segments
        // seq = 1 first segment
        feed(AssistantMessage(
            id = "asst-post-tool-1",
            contentRaw = JsonPrimitive("Post"),
            runId = "run-1",
            seqId = 1,
            otid = "post-tool-otid"
        ))

        feed(AssistantMessage(
            id = "asst-post-tool-1",
            contentRaw = JsonPrimitive("Post tool"),
            runId = "run-1",
            seqId = 2,
            otid = "post-tool-otid"
        ))

        // 6. out-of-order seq replay (should be ignored/merged without messing up text)
        feed(AssistantMessage(
            id = "asst-post-tool-1",
            contentRaw = JsonPrimitive("Po"),
            runId = "run-1",
            seqId = 1, // older seq
            otid = "post-tool-otid"
        ))

        ui = project()
        assertEquals(3, ui.size, "Should have user, tool, and assistant messages")
        assertEquals("Post tool", ui.last().content, "Out of order seq should not garble the text")

        // check that a late prefix from same run doesn't drop anything or duplicate
        feed(AssistantMessage(
            id = "asst-post-tool-2",
            contentRaw = JsonPrimitive("Post"),
            runId = "run-1",
            seqId = 21, // New serverId, but same runId and is a prefix of existing
            otid = "post-tool-otid-new"
        ))

        ui = project()
        assertEquals(3, ui.size, "No duplicate UI messages for prefix orphan")
        assertEquals("Post tool", ui.last().content, "No prefix orphan drops")
    }
}
