package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.util.Telemetry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-mxwtn: dedicated tests for the synchronous optimistic Local
 * append path that the platform send coordinator drives BEFORE the transport
 * call. The bead's acceptance criteria call out a reconciliation test that
 * asserts the Local bubble collapses against a simulated server echo with a
 * DIFFERENT id shape — i.e. not a trivial "setter+getter" round trip. These
 * tests cover:
 *
 *  - [appendOptimisticLocalSync] is synchronous (visible in state right after
 *    the call returns) and idempotent (a second insert with the same otid
 *    leaves the timeline unchanged).
 *  - The Local event is reconciled with a server `UserMessage` whose `id`
 *    (`serverId`) is structurally different from the otid — the
 *    `replaceByOtid(otid, confirmed)` swap the platform path relies on
 *    collapses them into a single `Confirmed` row with the otid preserved.
 *  - `markOptimisticLocalFailedSync` flips the Local event's `deliveryState`
 *    from SENDING to FAILED in place (no replacement row) so the UI sees a
 *    retry affordance tied to the user's actual bubble.
 *  - `markOptimisticLocalSentSync` flips SENDING to SENT for the same
 *    in-place invariant.
 *  - The `appendOptimisticLocalSync` Telemetry event fires exactly once
 *    across multiple calls (no duplicate emission on the idempotent path).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OptimisticLocalAppendTest {

    @BeforeTest
    fun setUp() {
        Telemetry.clear()
    }

    @AfterTest
    fun tearDown() {
        Telemetry.clear()
    }

    @Test
    fun `appendOptimisticLocalSync is visible in state synchronously_letmamxwtn`() = runTest {
        val state = MutableStateFlow(Timeline("conv-sync"))
        val handler = newHandler(state, backgroundScope)
        val otid = "ot-uuid-1"

        val appended = handler.appendOptimisticLocalSync(
            otid = otid,
            content = "hello",
            attachments = persistentListOf(),
            sentAt = timelineNow(),
        )

        assertTrue(appended, "expected first insert to return true")
        val local = state.value.findByOtid(otid)
        assertNotNull(local)
        assertIs<TimelineEvent.Local>(local)
        assertEquals("hello", local.content)
        assertEquals(DeliveryState.SENDING, local.deliveryState)
    }

    @Test
    fun `appendOptimisticLocalSync is idempotent on the same otid_letmamxwtn`() = runTest {
        val state = MutableStateFlow(Timeline("conv-sync"))
        val handler = newHandler(state, backgroundScope)
        val otid = "ot-uuid-2"

        handler.appendOptimisticLocalSync(otid, "first", persistentListOf(), timelineNow())
        val firstSize = state.value.events.size

        // Second insert with the SAME otid must be a no-op — duplicate send
        // paths (e.g. retry click + the pending queue) cannot fork the
        // timeline.
        val second = handler.appendOptimisticLocalSync(otid, "second", persistentListOf(), timelineNow())

        assertFalse(second, "duplicate insert should return false")
        assertEquals(firstSize, state.value.events.size, "duplicate insert must not grow the timeline")
        val kept = state.value.findByOtid(otid)
        assertIs<TimelineEvent.Local>(kept)
        // The first insert WINS — the duplicate "second" content must not
        // overwrite the original "first" content (idempotency is strict).
        assertEquals("first", kept.content)
    }

    @Test
    fun `local bubble reconciles with server echo of different id shape_letmamxwtn`() = runTest {
        // letta-mobile-mxwtn: the acceptance test. The Local bubble was
        // minted with otid="ot-uuid-3". The server echoes back a
        // UserMessage with serverId="cm-abc-789" (a structurally different
        // id format) carrying the SAME otid. replaceByOtid must collapse
        // them into one Confirmed row with the otid preserved — not two
        // rows (one Local, one Confirmed) and not a copy with a fresh otid.
        val state = MutableStateFlow(Timeline("conv-sync"))
        val handler = newHandler(state, backgroundScope)
        val otid = "ot-uuid-3"
        handler.appendOptimisticLocalSync(otid, "hello", persistentListOf(), timelineNow())

        val serverEcho = UserMessage(
            id = "cm-abc-789",
            otid = otid,
            date = null,
            contentRaw = kotlinx.serialization.json.JsonPrimitive("hello"),
        )
        val confirmed = serverEcho.toTimelineEvent(position = 0.0)
        assertNotNull(confirmed)
        val asConfirmed = assertIs<TimelineEvent.Confirmed>(confirmed)
        assertEquals("cm-abc-789", asConfirmed.serverId, "precondition: server echo id is a different shape than the otid")

        // Drive the same reconciliation seam the production path uses.
        val before = state.value.events.size
        val afterState = state.value.replaceLocal(otid, asConfirmed)
        // Detect the no-op: if the Local wasn't there, the swap falls back
        // to insertOrdered (which dedupes by otid) and the timeline grows
        // by at most 1. We assert the explicit collapse path to be sure
        // the Local row was found and replaced.
        assertEquals(before, afterState.events.size, "reconcile must not grow the timeline")
        val after = afterState.findByOtid(otid)
        assertIs<TimelineEvent.Confirmed>(after)
        assertEquals("cm-abc-789", after.serverId, "reconciled row carries the server id")
        // otid is preserved — the UI's per-bubble identity stays stable
        // through the Local→Confirmed transition.
        assertEquals(otid, after.otid)
        assertEquals(TimelineMessageType.USER, after.messageType)
    }

    @Test
    fun `markOptimisticLocalFailedSync flips SENDING to FAILED in place_letmamxwtn`() = runTest {
        val state = MutableStateFlow(Timeline("conv-sync"))
        val handler = newHandler(state, backgroundScope)
        val otid = "ot-uuid-4"
        handler.appendOptimisticLocalSync(otid, "hello", persistentListOf(), timelineNow())

        handler.markOptimisticLocalFailedSync(otid)

        val after = state.value.findByOtid(otid)
        assertIs<TimelineEvent.Local>(after)
        assertEquals(DeliveryState.FAILED, after.deliveryState, "bubble must flip to FAILED in place")
        // The number of events must be unchanged — markFailed is a state
        // transition, not a row replacement.
        assertEquals(1, state.value.events.size)
    }

    @Test
    fun `markOptimisticLocalFailedSync on unknown otid is a no-op_letmamxwtn`() = runTest {
        // letta-mobile-mxwtn: when the failing send's optimistic insert
        // never happened (e.g. the conversation id changed between insert
        // and failure) the mark call must not throw, must not grow the
        // timeline, and must not synthesise a phantom FAILED bubble.
        val state = MutableStateFlow(Timeline("conv-sync"))
        val handler = newHandler(state, backgroundScope)

        handler.markOptimisticLocalFailedSync("ot-uuid-doesnt-exist")

        assertEquals(0, state.value.events.size)
    }

    @Test
    fun `markOptimisticLocalSentSync flips SENDING to SENT in place_letmamxwtn`() = runTest {
        val state = MutableStateFlow(Timeline("conv-sync"))
        val handler = newHandler(state, backgroundScope)
        val otid = "ot-uuid-5"
        handler.appendOptimisticLocalSync(otid, "hello", persistentListOf(), timelineNow())

        handler.markOptimisticLocalSentSync(otid)

        val after = state.value.findByOtid(otid)
        assertIs<TimelineEvent.Local>(after)
        assertEquals(DeliveryState.SENT, after.deliveryState)
    }

    @Test
    fun `appendOptimisticLocalSync telemetry fires once per real insert_letmamxwtn`() = runTest {
        val state = MutableStateFlow(Timeline("conv-sync"))
        val handler = newHandler(state, backgroundScope)
        val otid = "ot-uuid-6"

        handler.appendOptimisticLocalSync(otid, "hello", persistentListOf(), timelineNow())
        handler.appendOptimisticLocalSync(otid, "hello", persistentListOf(), timelineNow()) // idempotent

        val events = Telemetry.snapshot().filter { it.name == "send.optimisticLocalAppended" }
        assertEquals(1, events.size, "duplicate insert must not emit a second telemetry event")
    }

    private fun newHandler(
        state: MutableStateFlow<Timeline>,
        scope: CoroutineScope,
    ): TimelineStateTransitionHandler {
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(state.value),
            scope = scope,
        )
        scope.launch {
            processor.state.collect { state.value = it.timeline }
        }
        return TimelineStateTransitionHandler("conv-sync", processor)
    }
}
