package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-c4igq.8: characterizes the transport receive-flow drop + the
 * always-on keep-alive fix.
 *
 * IrohChannelTransport._events/_frameEvents are MutableSharedFlow(replay=0,
 * extraBufferCapacity=64). emit() to a replay=0 SharedFlow with ZERO subscribers
 * completes immediately and the value is LOST — the "frames go dark until the
 * next send re-attaches a collector" symptom. The fix launches always-on
 * keep-alive (drain-only) collectors (bound to the observer job's lifetime) so
 * subscriptionCount stays >= 1 and emit() SUSPENDS/buffers instead of dropping.
 * Crucially replay stays 0, so NO stale frame is retained — a suppressed terminal
 * is never masked (the h30cy suppress-terminal guardrail, which replay=1 broke).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohFrameFlowDropTest {

    @Test
    fun replayZeroSharedFlowDropsFrameEmittedWithNoCollector() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        // Emit while NO collector is subscribed — the no-collector window.
        flow.emit("frame-during-gap")
        val received = mutableListOf<String>()
        val collector = launch { flow.collect { received += it } }
        runCurrent()
        flow.emit("frame-after-attach")
        runCurrent()
        collector.cancel()
        // The gap frame is LOST; only the post-attach frame survives. This is the bug.
        assertEquals(listOf("frame-after-attach"), received)
    }

    @Test
    fun alwaysOnKeepAliveCollectorPreventsZeroSubscriberDrop() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        // Permanent keep-alive (drain-only), like the transport binds to observerJob.
        val keepAlive = launch { flow.collect { /* drain */ } }
        runCurrent()
        assertTrue(flow.subscriptionCount.value >= 1, "keep-alive holds a subscription so emit never sees zero subscribers")
        // A real collector attaches; frames emitted while it is present arrive live.
        val received = mutableListOf<String>()
        val real = launch { flow.collect { received += it } }
        runCurrent()
        flow.emit("live-frame")
        runCurrent()
        assertEquals(listOf("live-frame"), received)
        keepAlive.cancel(); real.cancel()
    }

    @Test
    fun replayStaysZeroSoNoStaleFrameIsRetained() = runTest(UnconfinedTestDispatcher()) {
        // Guardrail: unlike replay=1, replay=0 + keep-alive retains NO replayable
        // frame. A NEW collector attaching AFTER a frame was consumed sees nothing
        // stale — so a suppressed terminal is never masked by a replayed old frame.
        val flow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        val keepAlive = launch { flow.collect { /* drain */ } }
        runCurrent()
        flow.emit("old-terminal")
        runCurrent()
        val late = mutableListOf<String>()
        val lateCollector = launch { flow.collect { late += it } }
        runCurrent()
        assertEquals(emptyList(), late)
        keepAlive.cancel(); lateCollector.cancel()
    }
}
