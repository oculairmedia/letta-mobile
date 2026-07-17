package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-c4igq.8: characterizes the transport receive-flow drop mechanism.
 *
 * IrohChannelTransport._events/_frameEvents are MutableSharedFlow(extraBufferCapacity=64,
 * replay=0). emit() to a replay=0 SharedFlow with NO active collector completes
 * immediately and the value is LOST (no receiver, no replay). So any frame emitted
 * during a window where no collector is subscribed is silently dropped — the
 * "frames go dark until the next send re-attaches a collector" symptom.
 *
 * This is the shape the rendezvous-based FakeIrohTransport (plain MutableSharedFlow,
 * emit suspends until received) cannot exhibit. The fix (replay/conflation or a
 * single always-on relay collector) must make a frame survive a no-collector window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohFrameFlowDropTest {

    @Test
    fun replayZeroSharedFlowDropsFrameEmittedWithNoCollector() = runTest(UnconfinedTestDispatcher()) {
        // Faithful to the real transport: replay = 0, buffered, SUSPEND overflow.
        val flow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)

        // Emit while NO collector is subscribed — this is the no-collector window.
        flow.emit("frame-during-gap")

        // A collector attaches AFTER the emit (the "next send re-attaches" moment).
        val received = mutableListOf<String>()
        val collector = launch { flow.collect { received += it } }
        runCurrent()
        // A later frame arrives while the collector IS subscribed.
        flow.emit("frame-after-attach")
        runCurrent()
        collector.cancel()

        // The gap frame is LOST; only the post-attach frame survives. This is the bug.
        assertEquals(listOf("frame-after-attach"), received)
    }

    @Test
    fun replayOneDeliversGapFrameToLateCollectorExactlyOnce() = runTest(UnconfinedTestDispatcher()) {
        // c4igq.8 h30cy-contract: replay = 1 must deliver the gap frame to a
        // re-attaching collector, and a SINGLE collector must see it exactly once
        // (replay does not double-deliver to one subscriber). The reducer's
        // shouldDropDuplicateStreamMessage handles cross-collector idempotency for
        // seq-identified frames; this asserts the SharedFlow-level contract.
        val flow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64)
        flow.emit("gap-frame")

        val received = mutableListOf<String>()
        val collector = launch { flow.collect { received += it } }
        runCurrent()
        // A live frame after attach.
        flow.emit("live-frame")
        runCurrent()
        collector.cancel()

        // The late collector sees the replayed gap frame once + the live frame once.
        assertEquals(listOf("gap-frame", "live-frame"), received)
    }

    @Test
    fun replayOneSharedFlowSurvivesNoCollectorWindow() = runTest(UnconfinedTestDispatcher()) {
        // The candidate fix shape: replay = 1 lets a late collector still see the
        // most recent frame emitted during a no-collector window.
        val flow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64)

        flow.emit("frame-during-gap")

        val received = mutableListOf<String>()
        val collector = launch { flow.collect { received += it } }
        runCurrent()
        collector.cancel()

        // With replay = 1 the gap frame is delivered to the late collector.
        assertEquals(listOf("frame-during-gap"), received)
    }
}
