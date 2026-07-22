package com.letta.mobile.data.controller.reconnect

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FullJitterBackoffTest {

    @Test
    fun ceilingGrowsExponentiallyThenCaps() {
        val backoff = FullJitterBackoff(baseDelayMs = 100, maxDelayMs = 2_000, multiplier = 2.0)
        assertEquals(100, backoff.ceilingMs(0))
        assertEquals(200, backoff.ceilingMs(1))
        assertEquals(400, backoff.ceilingMs(2))
        assertEquals(800, backoff.ceilingMs(3))
        assertEquals(1_600, backoff.ceilingMs(4))
        assertEquals(2_000, backoff.ceilingMs(5)) // capped
        assertEquals(2_000, backoff.ceilingMs(50)) // stays capped, no overflow
    }

    @Test
    fun delayIsWithinCeiling() {
        val backoff = FullJitterBackoff(baseDelayMs = 100, maxDelayMs = 5_000, multiplier = 2.0)
        val rng = Random(42)
        repeat(1_000) {
            val attempt = it % 10
            val delay = backoff.delayMs(attempt, rng)
            assertTrue(delay in 0..backoff.ceilingMs(attempt), "delay $delay out of [0, ${backoff.ceilingMs(attempt)}] for attempt $attempt")
        }
    }

    @Test
    fun delayIsDeterministicForSeededRandom() {
        val backoff = FullJitterBackoff(baseDelayMs = 250, maxDelayMs = 30_000)
        val a = FullJitterBackoff(baseDelayMs = 250, maxDelayMs = 30_000)
        val seedForEach = { Random(7) }
        val seqA = (0..6).map { backoff.delayMs(it, seedForEach()) }
        val seqB = (0..6).map { a.delayMs(it, seedForEach()) }
        assertEquals(seqA, seqB)
    }

    @Test
    fun jitterProducesSpread() {
        // Full jitter must not collapse to a single value: many draws at the
        // same attempt should span a meaningful fraction of the ceiling.
        val backoff = FullJitterBackoff(baseDelayMs = 1_000, maxDelayMs = 1_000)
        val rng = Random(99)
        val draws = (0 until 200).map { backoff.delayMs(3, rng) }
        assertTrue(draws.min() < 250, "min ${draws.min()} not low enough — no jitter?")
        assertTrue(draws.max() > 750, "max ${draws.max()} not high enough — no jitter?")
    }

    @Test
    fun rejectsInvalidConfig() {
        assertFailsWith<IllegalArgumentException> { FullJitterBackoff(baseDelayMs = 0) }
        assertFailsWith<IllegalArgumentException> { FullJitterBackoff(baseDelayMs = 100, maxDelayMs = 50) }
        assertFailsWith<IllegalArgumentException> { FullJitterBackoff(multiplier = 0.5) }
        assertFailsWith<IllegalArgumentException> { FullJitterBackoff().ceilingMs(-1) }
    }
}
