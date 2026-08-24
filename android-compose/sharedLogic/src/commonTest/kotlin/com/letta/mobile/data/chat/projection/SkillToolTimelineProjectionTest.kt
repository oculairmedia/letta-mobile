package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineHydrationReducer
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.parseTimelineInstant
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillToolTimelineProjectionTest {

    @Test
    fun `direct and double-wrapped skill arguments render a normal Skill tool card`() {
        val direct = projectLiveToolCall(
            ToolCall(
                id = "tc-direct",
                name = "Skill",
                arguments = """{"skill":"searxng","query":"weather","language":"en"}""",
            ),
        )
        val wrappedArguments = JsonPrimitive(
            JsonPrimitive("""{"skill":"ghost","tag":"updates"}""").toString(),
        ).toString()
        val wrapped = projectLiveToolCall(ToolCall(id = "tc-wrapped", name = "Skill", arguments = wrappedArguments))

        assertEquals("Skill", direct.name)
        assertEquals("tc-direct", direct.toolCallId)
        assertEquals("searxng", direct.displayTarget)
        assertEquals("success", direct.status)
        assertTrue(direct.arguments.contains("\"query\""))
        assertFalse(direct.arguments.contains("\"skill\""))
        assertFalse(direct.arguments.contains("\\\""))
        assertEquals("Skill", wrapped.name)
        assertEquals("tc-wrapped", wrapped.toolCallId)
        assertEquals("ghost", wrapped.displayTarget)
        assertEquals("success", wrapped.status)
        assertTrue(wrapped.arguments.contains("\"tag\""))
        assertFalse(wrapped.arguments.contains("\"skill\""))
        assertFalse(wrapped.arguments.contains("\\\""))
    }

    @Test
    fun `ordinary tool arguments retain their original tool card`() {
        val arguments = """{"command":"echo hi"}"""
        val call = projectLiveToolCall(ToolCall(id = "tc-normal", name = "Bash", arguments = arguments))

        assertEquals("Bash", call.name)
        assertEquals("tc-normal", call.toolCallId)
        assertEquals(null, call.displayTarget)
        assertEquals(arguments, call.arguments)
    }

    @Test
    fun `hydrated skill invocation drops its synthetic envelope and keeps one tool row`() {
        val rendered = HydratedSkillScenario().render()

        assertEquals(1, rendered.visibleMessages.size)
        assertEquals(1, rendered.renderItems.size)
        assertTrue(rendered.renderItems.single() is ChatRenderItem.Single)
        val call = rendered.visibleMessages.single().toolCalls!!.single()
        assertEquals("Skill", call.name)
        assertEquals("tc-hydrated", call.toolCallId)
        assertEquals("searxng", call.displayTarget)
        assertEquals("success", call.status)
        assertFalse(call.arguments.contains("\"skill\""))
    }

    @Test
    fun `live and hydrated skill invocation projections have the same visible fields`() {
        val scenario = HydratedSkillScenario()
        val live = projectLiveToolCall(scenario.toolCall)
        val hydrated = scenario.render().visibleMessages.single().toolCalls!!.single()

        assertEquals(live.toolCallId, hydrated.toolCallId)
        assertEquals(live.name, hydrated.name)
        assertEquals(live.arguments, hydrated.arguments)
        assertEquals(live.displayTarget, hydrated.displayTarget)
        assertEquals(live.result, hydrated.result)
        assertEquals(live.status, hydrated.status)
    }

    private fun projectLiveToolCall(toolCall: ToolCall) = timelineEventToUiMessage(
        TimelineEvent.Confirmed(
            position = 1.0,
            otid = "otid-${toolCall.id}",
            content = "",
            serverId = "msg-${toolCall.id}",
            messageType = TimelineMessageType.TOOL_CALL,
            runId = "run-${toolCall.id}",
            stepId = null,
            date = parseTimelineInstant("2026-08-24T00:00:00Z"),
            toolCalls = listOf(toolCall).toPersistentList(),
            toolReturnContent = "Search results for weather",
            toolReturnIsError = false,
        ),
    )!!.toolCalls!!.single()

    private class HydratedSkillScenario {
        val toolCall = ToolCall(
            id = "tc-hydrated",
            name = "Skill",
            arguments = """{"skill":"searxng","query":"weather","language":"en"}""",
        )
        private val runId = "run-hydrated"

        fun render(): ChatRenderModel {
            val messages = buildList {
                add(
                    UserMessage(
                        id = "envelope-${toolCall.id}",
                        contentRaw = JsonPrimitive(skillEnvelope()),
                        runId = runId,
                        otid = "otid-envelope-${toolCall.id}",
                    ),
                )
                add(
                    ToolCallMessage(
                        id = "msg-${toolCall.id}",
                        runId = runId,
                        toolCall = toolCall,
                        seqId = 1,
                        otid = "otid-${toolCall.id}",
                    ),
                )
                add(
                    ToolReturnMessage(
                        id = "return-${toolCall.id}",
                        toolCallId = toolCall.id!!,
                        status = "success",
                        toolReturnRaw = JsonPrimitive("Search results for weather"),
                        runId = runId,
                        seqId = 2,
                    ),
                )
            }
            val timeline = TimelineHydrationReducer.reduce(
                conversationId = "conversation-${toolCall.id}",
                serverMessagesChronological = messages,
                timelineBeforeFetch = Timeline("conversation-${toolCall.id}"),
                currentTimeline = Timeline("conversation-${toolCall.id}"),
                diskRecords = emptyList(),
            ).timeline
            val event = timeline.events.single() as TimelineEvent.Confirmed
            return buildChatRenderModel(
                listOfNotNull(timelineEventToUiMessage(event)),
                ChatDisplayMode.Interactive,
            )
        }

    }
}

private fun skillEnvelope(): String = buildString {
    append("<skill>\n")
    append("name: skill\n")
    append("description: A skill used for hydration-path rendering characterization tests.\n")
    append("---\n\n")
    append("## Usage\n\n")
    append("This is test skill documentation with sufficient length to exceed the minimum envelope threshold.\n")
    repeat(4) { append("Additional documentation content on line $it.\n") }
    append("\nARGUMENTS: test-args\n")
    append("</skill>")
}
