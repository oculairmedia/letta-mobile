package com.letta.mobile.data.plugin.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewRateLimiterTest {
    @Test
    fun twentyMessagesASecondPassAndTheTwentyFirstDoesNot() {
        val limiter = ViewRateLimiter()
        val admitted = (0 until 25).count { limiter.tryAcquire(nowMs = it.toLong()) }
        assertEquals(LcpView.MAX_MESSAGES_PER_SECOND, admitted)
    }

    @Test
    fun theWindowSlides() {
        val limiter = ViewRateLimiter(limit = 2, windowMs = 1_000)
        assertTrue(limiter.tryAcquire(0))
        assertTrue(limiter.tryAcquire(500))
        assertFalse(limiter.tryAcquire(999))
        assertTrue(limiter.tryAcquire(1_000), "the message at 0 left the window")
        assertFalse(limiter.tryAcquire(1_400))
        assertTrue(limiter.tryAcquire(1_500))
    }

    @Test
    fun refusedMessagesDoNotCount() {
        val limiter = ViewRateLimiter(limit = 1, windowMs = 100)
        assertTrue(limiter.tryAcquire(0))
        repeat(50) { assertFalse(limiter.tryAcquire(50)) }
        assertTrue(limiter.tryAcquire(100))
    }

    @Test
    fun limitsArePositive() {
        assertFailsWith<IllegalArgumentException> { ViewRateLimiter(limit = 0) }
        assertFailsWith<IllegalArgumentException> { ViewRateLimiter(windowMs = 0) }
    }
}
