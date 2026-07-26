package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineHydrationReducer
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.ToolTimelineFixtures
import com.letta.mobile.data.timeline.reduceStreamFrame
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolTimelineProjectionTest {

    @Test
    fun unknownStatusIsNotFailure() {
        val callNullStatus = UiToolCall(
            name = "test_tool",
            arguments = """{"key":"val"}""",
            result = null,
            status = null,
            toolCallId = "call-null-1",
        )
        val stateNull = classifyToolCallState(callNullStatus)
        assertEquals(ToolTimelineState.Running, stateNull)

        val callUnknownStatus = UiToolCall(
            name = "test_tool",
            arguments = """{"key":"val"}""",
            result = null,
            status = "custom_unknown_status",
            toolCallId = "call-unk-1",
        )
        val stateUnknown = classifyToolCallState(callUnknownStatus)
        assertEquals(ToolTimelineState.Running, stateUnknown)

        val callCompletedUnknownStatus = UiToolCall(
            name = "test_tool",
            arguments = """{"key":"val"}""",
            result = "some output",
            status = "custom_unknown_status",
            toolCallId = "call-unk-2",
        )
        val stateCompletedUnknown = classifyToolCallState(callCompletedUnknownStatus)
        assertEquals(ToolTimelineState.Succeeded, stateCompletedUnknown)
    }

    @Test
    fun liveAndHydratedProjectionsMatch() {
        var liveTimeline = Timeline(conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID)
        var pendingReturns = persistentMapOf<String, com.letta.mobile.data.model.ToolReturnMessage>()

        for (msg in ToolTimelineFixtures.LiveVsHydrated.messagesSequence) {
            val out = reduceStreamFrame(
                TimelineReducerInput(
                    prev = liveTimeline,
                    frame = msg,
                    pendingToolReturnsByCallId = pendingReturns,
                )
            )
            liveTimeline = out.next
            pendingReturns = out.updatedPendingToolReturnsByCallId
        }

        val hydratedTimeline = TimelineHydrationReducer.reduce(
            conversationId = ToolTimelineFixtures.TEST_CONVERSATION_ID,
            serverMessagesChronological = ToolTimelineFixtures.LiveVsHydrated.messagesSequence,
            timelineBeforeFetch = Timeline(ToolTimelineFixtures.TEST_CONVERSATION_ID),
            currentTimeline = Timeline(ToolTimelineFixtures.TEST_CONVERSATION_ID),
            diskRecords = emptyList(),
        ).timeline

        val liveUiMessages = liveTimeline.events.mapNotNull { timelineEventToUiMessage(it) }
        val hydratedUiMessages = hydratedTimeline.events.mapNotNull { timelineEventToUiMessage(it) }

        val liveGroups = projectToolTimelineGroups(liveUiMessages)
        val hydratedGroups = projectToolTimelineGroups(hydratedUiMessages)

        assertEquals(liveGroups.size, hydratedGroups.size)
        assertEquals(liveGroups.map { it.key }, hydratedGroups.map { it.key })
        assertEquals(liveGroups.map { it.state }, hydratedGroups.map { it.state })

        val liveCalls = liveGroups.flatMap { it.calls }
        val hydratedCalls = hydratedGroups.flatMap { it.calls }
        assertEquals(liveCalls.size, hydratedCalls.size)
        assertEquals(liveCalls.map { it.key }, hydratedCalls.map { it.key })
        assertEquals(liveCalls.map { it.state }, hydratedCalls.map { it.state })
        assertEquals(liveCalls.map { it.result }, hydratedCalls.map { it.result })
    }

    @Test
    fun settledValuesRetainReferentialIdentity() {
        val uiMessage = UiMessage(
            id = "msg-identity-1",
            role = "assistant",
            content = "",
            timestamp = "1000",
            toolCalls = listOf(
                UiToolCall(
                    name = "read_file",
                    arguments = """{"path":"a.txt"}""",
                    result = "hello",
                    status = "success",
                    toolCallId = "call-id-1",
                )
            )
        )

        val messages = listOf(uiMessage)

        val firstProjection = projectToolTimelineGroups(messages)
        val secondProjection = projectToolTimelineGroups(messages, previousGroups = firstProjection)

        assertSame(firstProjection, secondProjection)
        assertSame(firstProjection.single(), secondProjection.single())
        assertSame(firstProjection.single().calls.single(), secondProjection.single().calls.single())
    }

    @Test
    fun syntheticUpdateChangesOnlyOwningGroupAndCall() {
        val messages = ArrayList<UiMessage>()
        for (i in 0 until 100) {
            val msgId = "msg-synth-$i"
            val hasToolCalls = i % 5 == 0 // 20 messages have tool calls
            val toolCalls = if (hasToolCalls) {
                listOf(
                    UiToolCall(
                        name = "tool_$i",
                        arguments = """{"index":$i}""",
                        result = "result_$i",
                        status = "success",
                        toolCallId = "call-synth-$i-1",
                    ),
                    UiToolCall(
                        name = "tool_${i}_b",
                        arguments = """{"index":$i}""",
                        result = "result_${i}_b",
                        status = "success",
                        toolCallId = "call-synth-$i-2",
                    ),
                )
            } else null

            messages.add(
                UiMessage(
                    id = msgId,
                    role = if (hasToolCalls) "assistant" else "user",
                    content = "content $i",
                    timestamp = (1000 + i).toString(),
                    toolCalls = toolCalls,
                )
            )
        }

        val projector = ToolTimelineProjector()
        val groupsPass1 = projector.project(messages)
        assertEquals(20, groupsPass1.size)

        // Mutate only 1 call in message index 50 (which is group index 10)
        val targetMessageIndex = 50
        val targetGroupIndex = 10
        val originalMessage = messages[targetMessageIndex]
        val updatedMessage = originalMessage.copy(
            toolCalls = listOf(
                originalMessage.toolCalls!![0], // Unchanged call 0
                originalMessage.toolCalls!![1].copy(result = "NEW_UPDATED_RESULT"), // Changed call 1
            )
        )
        messages[targetMessageIndex] = updatedMessage

        val groupsPass2 = projector.project(messages)
        assertEquals(20, groupsPass2.size)

        // Verify all 19 unchanged groups retained referential identity
        for (gIdx in 0 until 20) {
            if (gIdx != targetGroupIndex) {
                assertSame(groupsPass1[gIdx], groupsPass2[gIdx])
                assertSame(groupsPass1[gIdx].calls[0], groupsPass2[gIdx].calls[0])
                assertSame(groupsPass1[gIdx].calls[1], groupsPass2[gIdx].calls[1])
            }
        }

        // Verify target group was recreated
        val oldTargetGroup = groupsPass1[targetGroupIndex]
        val newTargetGroup = groupsPass2[targetGroupIndex]
        assertTrue(oldTargetGroup !== newTargetGroup)

        // Inside target group: call 0 was unchanged so it retained referential identity!
        assertSame(oldTargetGroup.calls[0], newTargetGroup.calls[0])

        // Call 1 was updated so it has a new instance
        assertTrue(oldTargetGroup.calls[1] !== newTargetGroup.calls[1])
        assertEquals("NEW_UPDATED_RESULT", newTargetGroup.calls[1].result)
    }

    @Test
    fun blankAndDuplicateToolCallIdsHandledDeterministically() {
        val blankCallUi = UiMessage(
            id = "msg-tool-blank",
            role = "assistant",
            content = "",
            timestamp = "1000",
            toolCalls = listOf(
                UiToolCall(
                    name = "synthetic_blank",
                    arguments = "{}",
                    result = null,
                    status = null,
                    toolCallId = "",
                )
            )
        )

        val blankGroup = projectToolTimelineGroup(blankCallUi)
        assertNotNull(blankGroup)
        assertEquals("group:msg-tool-blank", blankGroup.key)
        assertEquals("call::msg-tool-blank-0", blankGroup.calls.single().key)

        val duplicateCallUi = UiMessage(
            id = "msg-tool-dup",
            role = "assistant",
            content = "",
            timestamp = "1000",
            toolCalls = listOf(
                UiToolCall(
                    name = "tool_alpha",
                    arguments = """{"x":1}""",
                    result = "shared result",
                    status = "success",
                    toolCallId = "call-dup-shared",
                ),
                UiToolCall(
                    name = "tool_beta",
                    arguments = """{"x":2}""",
                    result = "shared result",
                    status = "success",
                    toolCallId = "call-dup-shared",
                ),
            )
        )

        val dupGroup = projectToolTimelineGroup(duplicateCallUi)
        assertNotNull(dupGroup)
        assertEquals(2, dupGroup.calls.size)
        assertEquals("call:call-dup-shared", dupGroup.calls[0].key)
        assertEquals("call:call-dup-shared#1", dupGroup.calls[1].key)
    }

    @Test
    fun groupKeysStayStableThroughOneToManyGrowth() {
        val msgWithOneCall = UiMessage(
            id = "msg-growing-1",
            role = "assistant",
            content = "",
            timestamp = "1000",
            toolCalls = listOf(
                UiToolCall(name = "step1", arguments = """{"arg":1}""", result = null, toolCallId = "call-inc-1")
            )
        )

        val group1 = projectToolTimelineGroup(msgWithOneCall)
        assertNotNull(group1)
        assertEquals("group:msg-growing-1", group1.key)
        assertEquals(1, group1.calls.size)

        val msgWithTwoCalls = msgWithOneCall.copy(
            toolCalls = listOf(
                UiToolCall(name = "step1", arguments = """{"arg":1}""", result = null, toolCallId = "call-inc-1"),
                UiToolCall(name = "step2", arguments = """{"arg":2}""", result = null, toolCallId = "call-inc-2"),
            )
        )

        val group2 = projectToolTimelineGroup(msgWithTwoCalls, previousGroup = group1)
        assertNotNull(group2)
        assertEquals("group:msg-growing-1", group2.key) // Stable key!
        assertEquals(2, group2.calls.size)

        // Call 1 retained referential identity
        assertSame(group1.calls[0], group2.calls[0])
    }

    @Test
    fun approvalPendingApprovedRunningSuccessLifecycle() {
        val reqMsg = UiMessage(
            id = "msg-appr-1",
            role = "assistant",
            content = "",
            timestamp = "1000",
            approvalRequest = UiApprovalRequest(
                requestId = "appr-req-1",
                toolCalls = listOf(
                    UiApprovalToolCall(toolCallId = "call-appr-1", name = "delete_file", arguments = """{"path":"old.txt"}""")
                )
            ),
            toolCalls = listOf(
                UiToolCall(name = "delete_file", arguments = """{"path":"old.txt"}""", result = null, status = null, toolCallId = "call-appr-1")
            )
        )

        val g1 = projectToolTimelineGroup(reqMsg)
        assertNotNull(g1)
        assertEquals(ToolTimelineState.AwaitingApproval, g1.state)
        assertEquals(ToolTimelineState.AwaitingApproval, g1.calls.single().state)

        val approvedMsg = reqMsg.copy(
            approvalRequest = null,
            toolCalls = listOf(
                UiToolCall(name = "delete_file", arguments = """{"path":"old.txt"}""", result = null, status = null, toolCallId = "call-appr-1", approvalDecision = UiToolApprovalDecision.Approved)
            )
        )

        val g2 = projectToolTimelineGroup(approvedMsg, previousGroup = g1)
        assertNotNull(g2)
        assertEquals(ToolTimelineState.Running, g2.state)
        assertEquals(ToolTimelineState.Running, g2.calls.single().state)

        val successMsg = approvedMsg.copy(
            toolCalls = listOf(
                UiToolCall(name = "delete_file", arguments = """{"path":"old.txt"}""", result = "deleted", status = "success", toolCallId = "call-appr-1", approvalDecision = UiToolApprovalDecision.Approved)
            )
        )

        val g3 = projectToolTimelineGroup(successMsg, previousGroup = g2)
        assertNotNull(g3)
        assertEquals(ToolTimelineState.Succeeded, g3.state)
        assertEquals(ToolTimelineState.Succeeded, g3.calls.single().state)
    }

    @Test
    fun rejectionStateIsMappedCorrectly() {
        val rejMsg = UiMessage(
            id = "msg-rej-1",
            role = "assistant",
            content = "",
            timestamp = "1000",
            toolCalls = listOf(
                UiToolCall(name = "wipe_database", arguments = "{}", result = null, status = null, toolCallId = "call-rej-1", approvalDecision = UiToolApprovalDecision.Rejected)
            )
        )

        val group = projectToolTimelineGroup(rejMsg)
        assertNotNull(group)
        assertEquals(ToolTimelineState.Rejected, group.state)
        assertEquals(ToolTimelineState.Rejected, group.calls.single().state)
    }

    @Test
    fun safeSummariesAreDerivedCorrectly() {
        val s1 = deriveToolCallSummary("read_file", """{"path":"src/main.kt"}""")
        assertEquals("read_file(src/main.kt)", s1)

        val s2 = deriveToolCallSummary("exec_cmd", """{"command":"ls -la"}""")
        assertEquals("exec_cmd(ls -la)", s2)

        val s3 = deriveToolCallSummary("blank_args", "")
        assertEquals("blank_args", s3)
    }
}
