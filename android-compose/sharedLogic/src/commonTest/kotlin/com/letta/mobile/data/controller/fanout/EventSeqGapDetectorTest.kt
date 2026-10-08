package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.controller.fanout.EventSeqGapDetector.Observation
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-bzvro.6 (F06): a lost frame shows as a gap in the connection's event_seq. */
class EventSeqGapDetectorTest {
    private val detector = EventSeqGapDetector()

    @Test
    fun oneTwoFourIsOneGap() {
        assertEquals(Observation.Start, detector.observe(generation = 1, seq = 1))
        assertEquals(Observation.InOrder, detector.observe(1, 2))
        assertEquals(Observation.Gap(expected = 3, received = 4), detector.observe(1, 4))
        assertEquals(Observation.InOrder, detector.observe(1, 5))
    }

    @Test
    fun duplicatesAndReplaysAreNeverGaps() {
        detector.observe(1, 5)
        detector.observe(1, 6)
        assertEquals(Observation.Stale, detector.observe(1, 6))
        assertEquals(Observation.Stale, detector.observe(1, 3))
        assertEquals(Observation.InOrder, detector.observe(1, 7))
    }

    @Test
    fun aNewGenerationRestartsTracking() {
        detector.observe(1, 40)
        assertEquals(Observation.Start, detector.observe(generation = 2, seq = 3))
        assertEquals(Observation.InOrder, detector.observe(2, 4))
    }

    @Test
    fun aRestartedCounterIsNotAGap() {
        detector.observe(1, 40)
        assertEquals(Observation.Start, detector.observe(1, 1))
        assertEquals(Observation.InOrder, detector.observe(1, 2))
    }

    @Test
    fun theFirstFrameSeenMidStreamIsAStart() {
        assertEquals(Observation.Start, detector.observe(1, 900))
        detector.reset()
        assertEquals(Observation.Start, detector.observe(1, 950))
    }
}
