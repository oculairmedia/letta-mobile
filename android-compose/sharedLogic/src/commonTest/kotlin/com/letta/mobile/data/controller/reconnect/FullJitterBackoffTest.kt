package com.letta.mobile.data.controller.reconnect

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FullJitterBackoffTest {
    @Test
    fun delaysAreFullJitterWithinTheExponentialCap() {
        val backoff = FullJitterBackoff(baseDelayMs = 1_000, maxDelayMs = 30_000, maxAttempts = 10, random = Random(42))
        repeat(200) {
            for (attempt in 0 until 10) {
                val cap = minOf(30_000L, 1_000L shl attempt)
                val delay = backoff.delayFor(attempt)
                assertTrue(delay != null && delay in 0..cap, "attempt $attempt produced $delay outside [0, $cap]")
            }
        }
    }

    @Test
    fun delayCapNeverExceedsMaxDelay() {
        val backoff = FullJitterBackoff(baseDelayMs = 1_000, maxDelayMs = 5_000, maxAttempts = 40, random = Random(7))
        for (attempt in 0 until 40) {
            val delay = backoff.delayFor(attempt)
            assertTrue(delay != null && delay <= 5_000, "attempt $attempt produced $delay above the cap")
        }
    }

    @Test
    fun exhaustedAttemptBudgetReturnsNull() {
        val backoff = FullJitterBackoff(maxAttempts = 3, random = Random(1))
        assertTrue(backoff.delayFor(2) != null)
        assertNull(backoff.delayFor(3))
        assertNull(backoff.delayFor(100))
    }

    @Test
    fun hugeAttemptNumbersDoNotOverflow() {
        val backoff = FullJitterBackoff(
            baseDelayMs = 1_000,
            maxDelayMs = 30_000,
            maxAttempts = Int.MAX_VALUE,
            random = Random(3),
        )
        val delay = backoff.delayFor(Int.MAX_VALUE - 1)
        assertTrue(delay != null && delay in 0..30_000)
    }

    @Test
    fun invalidConfigurationFailsFast() {
        assertFailsWith<IllegalArgumentException> { FullJitterBackoff(baseDelayMs = 0) }
        assertFailsWith<IllegalArgumentException> { FullJitterBackoff(baseDelayMs = 100, maxDelayMs = 50) }
        assertFailsWith<IllegalArgumentException> { FullJitterBackoff(maxAttempts = 0) }
    }

    @Test
    fun injectedRandomMakesDelaysDeterministic() {
        val a = FullJitterBackoff(random = Random(99))
        val b = FullJitterBackoff(random = Random(99))
        assertEquals(
            (0 until 10).map { a.delayFor(it) },
            (0 until 10).map { b.delayFor(it) },
        )
    }
}
