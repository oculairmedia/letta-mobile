package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Integration-level coverage of the hydration guard wired into
 * [TimelineSyncLoop.hydrate]: opening a conversation with an unresolved
 * TOOL_CALL card and no active turn should trigger an extra immediate
 * reconcile pass against the canonical record, escalating to the bounded
 * backoff sweep (and eventually an honest failed settle) when the server
 * transcript never produces a return (see letta-mobile-dangling-tool /
 * DanglingToolCallResolver, Codex #902 review finding 2).
 */
class TimelineSyncLoopDanglingToolTest {

    @Test
    fun hydrate_escalates_to_sweep_and_settles_failed_when_server_never_resolves_the_call() = runTest(UnconfinedTestDispatcher()) {
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
        runCurrent()

        // hydrate() itself performs one listConversationMessages call to
        // bootstrap the timeline (1); the hydration guard's immediate
        // reconcile performs a second one because the timeline it just
        // loaded still has an unresolved TOOL_CALL card and no turn is
        // active (2). Since the canonical record here NEVER produces a
        // return for that call, the hydration guard escalates to the same
        // bounded backoff sweep turnEnded uses, which fires 4 more
        // reconciles before exhausting and settling the card failed —
        // not just the single hydration-guard pass.
        advanceTimeBy(2_000 + 5_000 + 15_000 + 30_000 + 1)
        runCurrent()

        assertEquals(6, transport.listCalls)
        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("No tool result recorded", event.toolReturnContentByCallId["call-1"])
        assertTrue(event.toolReturnIsErrorByCallId["call-1"] == true)
        loop.close()
    }

    @Test
    fun abnormal_turnEnded_still_schedules_the_sweep_for_a_prior_turns_dangling_call() = runTest(UnconfinedTestDispatcher()) {
        // Codex #902 review finding 3: turnStarted() for a NEW turn cancels
        // whatever sweep the PREVIOUS turn's clean completion scheduled. If
        // that new turn then ends ABNORMALLY (clean = false), the fix must
        // still reschedule the sweep so the previous turn's dangling card
        // doesn't spin forever with nothing left to ever resolve it.
        val transport = DanglingToolTransport()
        val loop = TimelineSyncLoop(
            messageApi = transport,
            conversationId = "conv-dangle-3",
            scope = this,
            pendingLocalStore = NoOpPendingLocalStore,
            conversationCursorStore = NoOpConversationCursorStore,
            startStreamSubscriber = false,
        )

        // Turn A: bootstraps the unresolved TOOL_CALL card, then ends
        // cleanly, scheduling a sweep for call-1.
        loop.turnStarted()
        loop.hydrate() // turnActive == true here, so the hydration guard itself is skipped.
        assertEquals(1, transport.listCalls)
        loop.turnEnded(clean = true)

        // Turn B starts before that sweep's first backoff step fires,
        // superseding (cancelling) it — this is the supersede-loses-the-
        // sweep hazard from finding 3.
        loop.turnStarted()
        // Turn B ends ABNORMALLY. Without the fix, nothing reschedules the
        // sweep here and call-1 (from turn A, unrelated to turn B) spins
        // forever.
        loop.turnEnded(clean = false)

        // No reconcile has fired yet — everything so far was synchronous or
        // gated behind the first backoff delay.
        assertEquals(1, transport.listCalls)

        // Let the rescheduled sweep run its full bounded backoff. The
        // canonical record never resolves call-1, so it exhausts and
        // settles an honest failed return.
        advanceTimeBy(2_000 + 5_000 + 15_000 + 30_000 + 1)
        runCurrent()

        assertEquals(5, transport.listCalls)
        val event = loop.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("No tool result recorded", event.toolReturnContentByCallId["call-1"])
        assertTrue(event.toolReturnIsErrorByCallId["call-1"] == true)
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
