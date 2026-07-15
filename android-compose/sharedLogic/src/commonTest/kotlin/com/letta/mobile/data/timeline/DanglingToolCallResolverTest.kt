package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the canonical-record-driven, never-guess dangling tool-call
 * resolver: [DanglingToolCallResolver.scheduleAfterCleanTurn] (post-turn
 * backoff sweep) and [DanglingToolCallResolver.runHydrationGuardIfIdle]
 * (single-pass hydration guard).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DanglingToolCallResolverTest {

    @AfterTest
    fun tearDown() {
        Telemetry.clear()
    }

    private fun toolCallEvent(
        otid: String = "otc-1",
        pos: Double = 1.0,
        callId: String = "call-1",
        returned: Boolean = false,
    ): TimelineEvent.Confirmed = TimelineEvent.Confirmed(
        position = pos,
        otid = otid,
        content = "tool call",
        serverId = "server-$otid",
        messageType = TimelineMessageType.TOOL_CALL,
        date = timelineNow(),
        runId = null,
        stepId = null,
        toolCalls = persistentListOf(ToolCall(id = callId, name = "search")),
        toolReturnContentByCallId = if (returned) {
            kotlinx.collections.immutable.persistentMapOf(callId to "ok")
        } else {
            kotlinx.collections.immutable.persistentMapOf()
        },
        toolReturnIsErrorByCallId = if (returned) {
            kotlinx.collections.immutable.persistentMapOf(callId to false)
        } else {
            kotlinx.collections.immutable.persistentMapOf()
        },
    )

    @Test
    fun `unresolvedToolCallIds finds calls with no attached return`() {
        val timeline = Timeline("c1").append(toolCallEvent(callId = "call-1", returned = false))
        assertEquals(setOf("call-1"), timeline.unresolvedToolCallIds())
    }

    @Test
    fun `unresolvedToolCallIds is empty once a return is attached`() {
        val timeline = Timeline("c1").append(toolCallEvent(callId = "call-1", returned = true))
        assertTrue(timeline.unresolvedToolCallIds().isEmpty())
    }

    @Test
    fun `resolve on first poll stops the sweep without settling failed`() = runTest {
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = false)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ ->
                reconcileCalls++
                // The canonical record now has the real successful return.
                state.value = Timeline("c1").append(toolCallEvent(callId = "call-1", returned = true))
                1
            },
        )

        resolver.scheduleAfterCleanTurn()
        // First backoff step: 2s.
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(1, reconcileCalls)
        val event = state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("ok", event.toolReturnContentByCallId["call-1"])
        assertFalse(event.toolReturnIsErrorByCallId["call-1"] ?: true)

        // No further reconciles even after the whole backoff window elapses —
        // the sweep already stopped.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, reconcileCalls)
    }

    @Test
    fun `exhaustion settles an honest failed return, never guessing mid-sweep`() = runTest {
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = false)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ ->
                reconcileCalls++
                // The canonical record never gets a return for this call id.
                0
            },
        )

        resolver.scheduleAfterCleanTurn()

        // After the first three attempts the call is still unresolved and NOT
        // yet settled failed — the resolver must not guess before backoff exhausts.
        advanceTimeBy(2_000 + 5_000 + 15_000)
        runCurrent()
        assertEquals(3, reconcileCalls)
        var event = state.value.events.single() as TimelineEvent.Confirmed
        assertTrue(event.toolReturnContentByCallId.isEmpty())

        // Final attempt (30s) exhausts the schedule -> honest fail.
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(4, reconcileCalls)
        event = state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("No tool result recorded", event.toolReturnContentByCallId["call-1"])
        assertTrue(event.toolReturnIsErrorByCallId["call-1"] == true)
    }

    @Test
    fun `a new turn starting cancels the pending sweep`() = runTest {
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = false)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ -> reconcileCalls++; 0 },
        )

        resolver.scheduleAfterCleanTurn()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, reconcileCalls)

        // New turn starts on the same conversation: cancel the old sweep.
        resolver.cancelPendingSweep()

        // Advance past the entire remaining backoff window: no further
        // reconciles, and critically no late Failed settle from the
        // superseded sweep.
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, reconcileCalls)
        val event = state.value.events.single() as TimelineEvent.Confirmed
        assertTrue(event.toolReturnContentByCallId.isEmpty())
    }

    @Test
    fun `no unresolved calls means scheduleAfterCleanTurn is a no-op`() = runTest {
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = true)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ -> reconcileCalls++; 0 },
        )

        resolver.scheduleAfterCleanTurn()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, reconcileCalls)
    }

    @Test
    fun `hydration guard fires exactly one reconcile when idle with unresolved calls`() = runTest {
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = false)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { reason, forceRefresh ->
                reconcileCalls++
                assertEquals(true, forceRefresh)
                0
            },
        )

        resolver.runHydrationGuardIfIdle(turnActive = false)
        assertEquals(1, reconcileCalls)

        // A second hydration pass with the same unresolved state should still
        // just be a single reconcile call per invocation (no backoff loop).
        resolver.runHydrationGuardIfIdle(turnActive = false)
        assertEquals(2, reconcileCalls)
    }

    @Test
    fun `hydration guard is skipped while a turn is active`() = runTest {
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = false)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ -> reconcileCalls++; 0 },
        )

        resolver.runHydrationGuardIfIdle(turnActive = true)
        assertEquals(0, reconcileCalls)
    }

    @Test
    fun `hydration guard is a no-op when nothing is unresolved`() = runTest {
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = true)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ -> reconcileCalls++; 0 },
        )

        resolver.runHydrationGuardIfIdle(turnActive = false)
        assertEquals(0, reconcileCalls)
    }
}
