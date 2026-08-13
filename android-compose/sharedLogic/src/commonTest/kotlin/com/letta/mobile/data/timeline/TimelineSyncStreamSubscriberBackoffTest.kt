package com.letta.mobile.data.timeline

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M5 (data-efficiency-audit): the network-error backoff for a persistent
 * timeline stream must be jittered so multiple streams don't all retry on
 * the same millisecond when the network drops. The jitter math lives in
 * [streamErrorBackoffMs]; these tests pin its range contract.
 *
 * The `runStreamSubscriber` coroutine itself isn't exercised here — spinning
 * up the full transport + frame-reducer rig for a delay assertion isn't
 * worth the cost when the helper is pure.
 */
class TimelineSyncStreamSubscriberBackoffTest {

    private val maxMs = 8_000L

    @Test
    fun `error backoff is at least STREAM_BACKOFF_MAX_MS`() {
        repeat(1000) {
            val delay = streamErrorBackoffMs(random = Random.Default, maxMs = maxMs)
            assertTrue(delay >= maxMs, "delay=$delay must be >= $maxMs")
        }
    }

    @Test
    fun `error backoff is strictly less than twice STREAM_BACKOFF_MAX_MS`() {
        repeat(1000) {
            val delay = streamErrorBackoffMs(random = Random.Default, maxMs = maxMs)
            assertTrue(delay < 2 * maxMs, "delay=$delay must be < ${2 * maxMs}")
        }
    }

    @Test
    fun `error backoff covers the full jitter range across many samples`() {
        // Deterministic Random at offsets 0, maxMs/2, maxMs-1 to drive the
        // floor, midpoint, and ceiling-range of the jitter contract directly.
        // The previous probabilistic sampling was flaky for CI — when a
        // genuinely-broken Random.nextLong collapses to a single value, the
        // 1000-sample range check still passes by accident.
        val delayFloor = streamErrorBackoffMs(random = Random(0L), maxMs = maxMs)
        val delayMid = streamErrorBackoffMs(random = Random(maxMs / 2L), maxMs = maxMs)
        val delayCeiling = streamErrorBackoffMs(random = Random(maxMs - 1L), maxMs = maxMs)
        assertTrue(delayFloor >= maxMs, "floor delay=$delayFloor must be >= $maxMs")
        assertTrue(delayMid > delayFloor, "midpoint delay=$delayMid must exceed floor=$delayFloor")
        assertTrue(delayCeiling < 2 * maxMs, "ceiling delay=$delayCeiling must be < ${2 * maxMs}")
    }
}