package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Integration-level coverage of the hydration guard wired into
 * [TimelineSyncLoop.hydrate]: opening a conversation with an unresolved
 * TOOL_CALL card and no active turn should trigger exactly one extra
 * reconcile pass against the canonical record (see letta-mobile-dangling-
 * tool / DanglingToolCallResolver).
 */
class TimelineSyncLoopDanglingToolTest {

    @Test
    fun hydrate_triggers_single_hydration_guard_reconcile_when_idle_with_unresolved_call() = runTest(UnconfinedTestDispatcher()) {
        val transport = DanglingToolTransport()
        val loop = TimelineSyncLoop(
            messageApi = transport,
            conversationId = "conv-dangle-1",
            scope = this,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            startStreamSubscriber = false,
        )

        loop.hydrate()

        // hydrate() itself performs one listConversationMessages call to
        // bootstrap the timeline; the hydration guard's reconcile performs a
        // second one because the timeline it just loaded still has an
        // unresolved TOOL_CALL card and no turn is active.
        assertEquals(2, transport.listCalls)
        loop.close()
    }

    @Test
    fun hydrate_does_not_trigger_hydration_guard_when_a_turn_is_active() = runTest(UnconfinedTestDispatcher()) {
        val transport = DanglingToolTransport()
        val loop = TimelineSyncLoop(
            messageApi = transport,
            conversationId = "conv-dangle-2",
            scope = this,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            startStreamSubscriber = false,
        )

        loop.turnStarted()
        loop.hydrate()

        // Only the bootstrap fetch — no hydration-guard reconcile while a
        // turn is believed active for this conversation.
        assertEquals(1, transport.listCalls)
        loop.close()
    }

    private class DanglingToolTransport : TimelineTransport {
        var listCalls = 0

        override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> = emptyFlow()
        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()

        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> {
            listCalls += 1
            return listOf(
                ToolCallMessage(
                    id = "tc-1",
                    toolCall = ToolCall(id = "call-1", name = "search"),
                    runId = "run-1",
                ),
            )
        }

        override suspend fun listAgentMessages(
            agentId: String,
            limit: Int?,
            order: String?,
            conversationId: String?,
        ): List<LettaMessage> = emptyList()
    }
}
