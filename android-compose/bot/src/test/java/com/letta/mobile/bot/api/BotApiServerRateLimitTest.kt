package com.letta.mobile.bot.api

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe

class BotApiServerRateLimitTest : WordSpec({
    "SlidingWindowRateLimiter" should {
        "allow requests until the client hits the window limit" {
            var now = 1_000L
            val limiter = SlidingWindowRateLimiter(
                maxRequests = 3,
                windowMillis = 60_000L,
                nowMillis = { now },
            )

            limiter.tryAcquire("device-a") shouldBe RateLimitDecision(true, 2, 60)
            limiter.tryAcquire("device-a") shouldBe RateLimitDecision(true, 1, 60)
            limiter.tryAcquire("device-a") shouldBe RateLimitDecision(true, 0, 60)
            limiter.tryAcquire("device-a") shouldBe RateLimitDecision(false, 0, 60)
        }

        "reopen the bucket after the window expires" {
            var now = 1_000L
            val limiter = SlidingWindowRateLimiter(
                maxRequests = 2,
                windowMillis = 10_000L,
                nowMillis = { now },
            )

            limiter.tryAcquire("device-a")
            limiter.tryAcquire("device-a")

            now = 12_000L

            limiter.tryAcquire("device-a") shouldBe RateLimitDecision(true, 1, 10)
        }

        "track clients independently" {
            var now = 1_000L
            val limiter = SlidingWindowRateLimiter(
                maxRequests = 1,
                windowMillis = 5_000L,
                nowMillis = { now },
            )

            limiter.tryAcquire("device-a") shouldBe RateLimitDecision(true, 0, 5)
            limiter.tryAcquire("device-b") shouldBe RateLimitDecision(true, 0, 5)
            limiter.tryAcquire("device-a") shouldBe RateLimitDecision(false, 0, 5)
        }
    }
})
