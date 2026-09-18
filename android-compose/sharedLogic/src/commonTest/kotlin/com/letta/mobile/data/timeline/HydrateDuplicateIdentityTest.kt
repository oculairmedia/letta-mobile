package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCall
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-zog19 / letta-mobile-hw2l1: hydration is the LAST place a
 * duplicate can be collapsed. Anything that survives here is persisted and
 * replayed into the timeline on every subsequent hydrate, so these cases guard
 * the two identity gaps that let rows accumulate forever.
 */
class HydrateDuplicateIdentityTest {

    private fun hydrate(messages: List<com.letta.mobile.data.model.LettaMessage>) =
        TimelineHydrationReducer.reduce(
            conversationId = CONVERSATION,
            serverMessagesChronological = messages,
            timelineBeforeFetch = Timeline(conversationId = CONVERSATION),
            currentTimeline = Timeline(conversationId = CONVERSATION),
            diskRecords = emptyList(),
        ).timeline.events.filterIsInstance<TimelineEvent.Confirmed>()

    // hw2l1: the reconcile final carries a NULL run id, so it shares no
    // identity key with the streamed row it supersedes. Without a content-based
    // collapse both rows persist and the thought echoes on every hydrate.
    @Test
    fun hydrateCollapsesReasoningReconcileFinalOverStreamedRow() {
        val events = hydrate(
            listOf(
                ReasoningMessage(id = "msg-streamed", reasoning = "Checking the transport", runId = "run-1"),
                ReasoningMessage(id = "msg-final", reasoning = "Checking the transport before send", runId = null),
            ),
        )
        val thoughts = events.filter { it.messageType == TimelineMessageType.REASONING }
        assertEquals(1, thoughts.size)
        assertEquals("Checking the transport before send", thoughts.single().content.trim())
    }

    // The collapse must be a superset relation, never "two thoughts near each
    // other". Distinct thoughts stay distinct even when one has no run id.
    @Test
    fun hydrateKeepsDistinctReasoningRows() {
        val events = hydrate(
            listOf(
                ReasoningMessage(id = "msg-a", reasoning = "Checking the transport layer", runId = "run-1"),
                ReasoningMessage(id = "msg-b", reasoning = "Now verifying the persistence delta", runId = null),
            ),
        )
        assertEquals(2, events.count { it.messageType == TimelineMessageType.REASONING })
    }

    // Two reconcile finals are not a streamed/final pair; neither supersedes the
    // other, so nothing may be dropped on run-id signature alone.
    @Test
    fun hydrateKeepsTwoNullRunReasoningRowsThatDoNotSupersede() {
        val events = hydrate(
            listOf(
                ReasoningMessage(id = "msg-a", reasoning = "Checking the transport layer", runId = null),
                ReasoningMessage(id = "msg-b", reasoning = "Verifying the persistence delta", runId = null),
            ),
        )
        assertEquals(2, events.count { it.messageType == TimelineMessageType.REASONING })
    }

    // zog19: one invocation surfaced as both a tool_call_message and an
    // approval_request_message. Different server ids AND different rendered
    // content, so the SHARED CALL ID is the only thing that can collapse them.
    // This asserts the identity key directly: a full-hydrate scenario is not a
    // regression test here, because approval evidence merges the pair upstream
    // before dedupe ever sees it, which is exactly how the unreachable branch
    // stayed invisible for so long.
    @Test
    fun toolCallIdentityKeysCollideOnSharedCallIdDespiteDifferentContent() {
        val streamed = toolCallEvent(
            serverId = "msg-tool-call",
            otid = "otid-tool-call",
            content = "Bash(command: ls)",
        )
        val approval = toolCallEvent(
            serverId = "msg-approval",
            otid = "otid-approval",
            content = "Approve Bash?",
        )
        val shared = streamed.identityKeys() intersect approval.identityKeys()
        assertTrue(
            shared.isNotEmpty(),
            "one invocation must share an identity key; got ${streamed.identityKeys()} vs ${approval.identityKeys()}",
        )
    }

    // The call-id key must not fuse two genuinely different invocations that
    // merely share a run.
    @Test
    fun toolCallIdentityKeysStayDistinctForDifferentCallIds() {
        val first = toolCallEvent(serverId = "msg-a", otid = "otid-a", content = "Bash(ls)", callId = "call-1")
        val second = toolCallEvent(serverId = "msg-b", otid = "otid-b", content = "Bash(pwd)", callId = "call-2")
        assertEquals(emptySet(), first.identityKeys() intersect second.identityKeys())
    }

    private fun toolCallEvent(
        serverId: String,
        otid: String,
        content: String,
        callId: String = "call-1",
    ) = TimelineEvent.Confirmed(
        position = 1.0,
        otid = otid,
        content = content,
        serverId = serverId,
        messageType = TimelineMessageType.TOOL_CALL,
        date = timelineNow(),
        runId = "run-1",
        stepId = null,
        toolCalls = persistentListOf(ToolCall(toolCallId = callId, name = "Bash", arguments = "{}")),
    )

    private companion object {
        private const val CONVERSATION = "conversation-1"
    }
}
