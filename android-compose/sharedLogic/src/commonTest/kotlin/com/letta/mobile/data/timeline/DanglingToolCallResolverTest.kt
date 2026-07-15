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
 * resolver: [DanglingToolCallResolver.scheduleSweepIfUnresolved] (bounded
 * backoff sweep, scheduled on every turn end regardless of clean/abnormal —
 * see Codex #902 review finding 3) and
 * [DanglingToolCallResolver.runHydrationGuardIfIdle] (immediate reconcile on
 * hydration, escalating to the same bounded sweep if still unresolved
 * afterward — see Codex #902 review finding 2).
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

        resolver.scheduleSweepIfUnresolved()
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

        resolver.scheduleSweepIfUnresolved()

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

        resolver.scheduleSweepIfUnresolved()
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
    fun `scheduleSweepIfUnresolved with clean=false still schedules and resolves the sweep`() = runTest {
        // Codex #902 review finding 3: an abnormal (cancel/timeout/error)
        // turnEnded must still schedule the sweep for a DIFFERENT, earlier
        // turn's dangling card — clean is telemetry-only and never gates
        // scheduling. This is safe because the sweep only trusts the
        // canonical record; an abnormal turn's OWN calls are already
        // settled synchronously elsewhere and never show up here.
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = false)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ ->
                reconcileCalls++
                state.value = Timeline("c1").append(toolCallEvent(callId = "call-1", returned = true))
                1
            },
        )

        resolver.scheduleSweepIfUnresolved(clean = false)
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(1, reconcileCalls)
        val event = state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("ok", event.toolReturnContentByCallId["call-1"])
    }

    @Test
    fun `no unresolved calls means scheduleSweepIfUnresolved is a no-op`() = runTest {
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = true)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ -> reconcileCalls++; 0 },
        )

        resolver.scheduleSweepIfUnresolved()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, reconcileCalls)
    }

    @Test
    fun `hydration guard fires exactly one immediate reconcile per invocation before any backoff`() = runTest {
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
        // Synchronous portion only: the immediate reconcile ran, and since it
        // didn't resolve anything, a backoff sweep was scheduled but has not
        // fired yet (no time advanced).
        assertEquals(1, reconcileCalls)

        // A second hydration pass with the same unresolved state performs its
        // own immediate reconcile, superseding the still-pending sweep from
        // the first call.
        resolver.runHydrationGuardIfIdle(turnActive = false)
        assertEquals(2, reconcileCalls)
    }

    @Test
    fun `hydration guard escalates to the bounded sweep and settles failed on exhaustion, not just one reconcile`() = runTest {
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = false)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ ->
                reconcileCalls++
                // Canonical record never gets a return: a restart-survivor
                // card whose real return was permanently lost server-side.
                0
            },
        )

        resolver.runHydrationGuardIfIdle(turnActive = false)
        // Immediate reconcile only so far.
        assertEquals(1, reconcileCalls)
        assertTrue(state.value.unresolvedToolCallIds().isNotEmpty())

        // Let the escalated backoff sweep run to exhaustion (4 backoff steps).
        advanceTimeBy(2_000 + 5_000 + 15_000 + 30_000 + 1)
        runCurrent()

        // 1 immediate hydration reconcile + 4 backoff-sweep reconciles.
        assertEquals(5, reconcileCalls)
        val event = state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("No tool result recorded", event.toolReturnContentByCallId["call-1"])
        assertTrue(event.toolReturnIsErrorByCallId["call-1"] == true)
    }

    @Test
    fun `hydration guard does not escalate to a sweep when the immediate reconcile already resolved everything`() = runTest {
        val state = MutableStateFlow(Timeline("c1").append(toolCallEvent(callId = "call-1", returned = false)))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "c1",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ ->
                reconcileCalls++
                state.value = Timeline("c1").append(toolCallEvent(callId = "call-1", returned = true))
                1
            },
        )

        resolver.runHydrationGuardIfIdle(turnActive = false)
        assertEquals(1, reconcileCalls)

        // No sweep was scheduled since the immediate reconcile already
        // cleared the unresolved set — advancing time triggers nothing.
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, reconcileCalls)
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
