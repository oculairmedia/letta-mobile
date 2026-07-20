package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.controller.node.iroh.MessageListWireProjection
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UiSubagentDispatch
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SubagentDispatchTimelineProjectionTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val arguments =
        """{"description":"Investigate reload parity","subagent_type":"researcher","run_in_background":true,"prompt":"Trace the persisted Iroh turn"}"""
    private val result = """{"task_id":"task-123","agent_id":"agent-sub-123"}"""
    private val expected = UiSubagentDispatch(
        toolCallId = "call-agent-1",
        description = "Investigate reload parity",
        subagentType = "researcher",
        runInBackground = true,
        prompt = "Trace the persisted Iroh turn",
        taskId = "task-123",
        subagentAgentId = "agent-sub-123",
    )

    @Test
    fun `live TOOL_CALL and TOOL_RETURN preserve Agent dispatch metadata`() {
        val call = ToolCallMessage(
            id = "message-call",
            toolCall = ToolCall(
                toolCallId = "call-agent-1",
                name = "Agent",
                arguments = arguments,
            ),
        )
        val returned = ToolReturnMessage(
            id = "message-return",
            toolCallId = "call-agent-1",
            status = "success",
            toolReturnRaw = JsonPrimitive(result),
        )

        val afterCall = reduceStreamFrame(
            TimelineReducerInput(Timeline("conv-1"), call, persistentMapOf()),
        ).next
        val afterReturn = reduceStreamFrame(
            TimelineReducerInput(afterCall, returned, persistentMapOf()),
        ).next
        val event = afterReturn.events.filterIsInstance<TimelineEvent.Confirmed>().single()
        val dispatch = timelineEventToUiMessage(event)
            ?.toolCalls
            ?.single()
            ?.subagentDispatch

        assertEquals(expected, dispatch)
    }

    @Test
    fun `string-encoded Agent arguments still project dispatch metadata`() {
        val call = ToolCallMessage(
            id = "message-call",
            toolCall = ToolCall(
                toolCallId = "call-agent-1",
                name = "Agent",
                arguments = JsonPrimitive(arguments).toString(),
            ),
        )
        val returned = ToolReturnMessage(
            id = "message-return",
            toolCallId = "call-agent-1",
            status = "success",
            toolReturnRaw = JsonPrimitive(result),
        )
        val afterCall = reduceStreamFrame(
            TimelineReducerInput(Timeline("conv-1"), call, persistentMapOf()),
        ).next
        val afterReturn = reduceStreamFrame(
            TimelineReducerInput(afterCall, returned, persistentMapOf()),
        ).next
        val event = afterReturn.events.filterIsInstance<TimelineEvent.Confirmed>().single()
        assertEquals(
            expected,
            timelineEventToUiMessage(event)?.toolCalls?.single()?.subagentDispatch,
        )
    }

    @Test
    fun `structured object Agent return preserves task metadata`() {
        val call = ToolCallMessage(
            id = "message-call",
            toolCall = ToolCall(
                toolCallId = "call-agent-1",
                name = "Agent",
                arguments = arguments,
            ),
        )
        val returned = ToolReturnMessage(
            id = "message-return",
            toolCallId = "call-agent-1",
            status = "success",
            toolReturnRaw = json.parseToJsonElement(result),
        )
        val afterCall = reduceStreamFrame(
            TimelineReducerInput(Timeline("conv-1"), call, persistentMapOf()),
        ).next
        val afterReturn = reduceStreamFrame(
            TimelineReducerInput(afterCall, returned, persistentMapOf()),
        ).next
        val event = afterReturn.events.filterIsInstance<TimelineEvent.Confirmed>().single()
        val uiToolCall = assertNotNull(timelineEventToUiMessage(event)).toolCalls?.single()
        assertEquals(expected, uiToolCall?.subagentDispatch)
        assertEquals(result, uiToolCall?.result)
    }

    @Test
    fun `task-notification Agent return does not mask notification card path`() {
        val notification = """
            <task-notification>
            <summary>done</summary>
            <status>completed</status>
            </task-notification>
        """.trimIndent()
        val call = ToolCallMessage(
            id = "message-call",
            toolCall = ToolCall(
                toolCallId = "call-agent-1",
                name = "Agent",
                arguments = arguments,
            ),
        )
        val returned = ToolReturnMessage(
            id = "message-return",
            toolCallId = "call-agent-1",
            status = "success",
            toolReturnRaw = JsonPrimitive(notification),
        )
        val afterCall = reduceStreamFrame(
            TimelineReducerInput(Timeline("conv-1"), call, persistentMapOf()),
        ).next
        val afterReturn = reduceStreamFrame(
            TimelineReducerInput(afterCall, returned, persistentMapOf()),
        ).next
        val event = afterReturn.events.filterIsInstance<TimelineEvent.Confirmed>().single()
        val uiToolCall = assertNotNull(timelineEventToUiMessage(event)).toolCalls?.single()
        assertEquals(null, uiToolCall?.subagentDispatch)
        assertEquals(notification, uiToolCall?.result)
    }

    @Test
    fun `message-list hydrated TOOL_CALL and TOOL_RETURN match live Agent dispatch metadata`() {
        val persistedPage = json.parseToJsonElement(
            """
            [
              {
                "id":"message-call",
                "message_type":"tool_call_message",
                "tool_call":{
                  "tool_call_id":"call-agent-1",
                  "name":"Agent",
                  "arguments":${JsonPrimitive(arguments)}
                }
              },
              {
                "id":"message-return",
                "message_type":"tool_return_message",
                "tool_call_id":"call-agent-1",
                "status":"success",
                "tool_return":${JsonPrimitive(result)}
              }
            ]
            """.trimIndent(),
        )
        val projected = MessageListWireProjection.projectMessageList(persistedPage, "conv-1")
        assertEquals(persistedPage, projected)

        val messages = json.decodeFromJsonElement(
            ListSerializer(LettaMessage.serializer()),
            assertIs<JsonArray>(projected),
        )
        val hydrated = TimelineHydrationReducer.reduce(
            conversationId = "conv-1",
            serverMessagesChronological = messages,
            timelineBeforeFetch = Timeline("conv-1"),
            currentTimeline = Timeline("conv-1"),
            diskRecords = emptyList(),
        ).timeline
        val event = hydrated.events.filterIsInstance<TimelineEvent.Confirmed>().single()
        val uiToolCall = assertNotNull(timelineEventToUiMessage(event)).toolCalls?.single()

        assertEquals(result, uiToolCall?.result)
        assertEquals(expected, uiToolCall?.subagentDispatch)
    }
}
