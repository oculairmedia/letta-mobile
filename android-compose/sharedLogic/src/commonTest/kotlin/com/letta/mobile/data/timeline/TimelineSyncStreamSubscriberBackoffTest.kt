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
        // With 1000 samples in [8000, 16000), we expect to see both extremes.
        // If the distribution collapses to a single value, the jitter source
        // is broken (e.g. someone removed the Random.nextLong call).
        val samples = (0 until 1000).map {
            streamErrorBackoffMs(random = Random.Default, maxMs = maxMs)
        }
        val min = samples.min()
        val max = samples.max()
        assertTrue(min in maxMs..(maxMs + 100), "expected min near floor ($maxMs), min=$min")
        assertTrue(max >= maxMs + 7000, "expected max near ceiling (${2 * maxMs}), max=$max")
    }
}